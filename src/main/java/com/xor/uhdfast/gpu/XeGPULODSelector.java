package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * GPU自适应屏幕空间LOD选择器 - 异步双缓冲版本
 * 
 * 使用GPU计算着色器计算每个Chunk的实际屏幕空间覆盖面积，
 * 动态选择适当的LOD级别，并提供GPU反馈缓冲区用于需要重建的Chunk。
 * 
 * 实现原理：
 * - 第N帧：提交GPU计算任务，不等待结果
 * - 第N+1帧：读取第N帧的结果并使用
 */
public class XeGPULODSelector {

    // ========================================================================
    // LOD级别常量
    // ========================================================================

    /** LOD 0 (HIGH): 屏幕面积 > 5% - 完整细节 */
    public static final int LOD_HIGH = 0;
    /** LOD 1 (MEDIUM): 屏幕面积 1-5% - 简化几何体 */
    public static final int LOD_MEDIUM = 1;
    /** LOD 2 (LOW): 屏幕面积 0.1-1% - 简化几何体 */
    public static final int LOD_LOW = 2;
    /** LOD 3 (MINIMAL): 屏幕面积 < 0.1% - 最小几何体 */
    public static final int LOD_MINIMAL = 3;

    // ========================================================================
    // LOD选择计算着色器
    // ========================================================================

    private static final String LOD_SELECT_COMPUTE_SHADER = """
            #version 430 core

            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

            // 输入：Chunk包围盒数据 (x, y, z, radius) per chunk
            layout(std430, binding = 0) buffer ChunkBounds {
                vec4 bounds[];  // xyz = center, w = radius
            };

            // 输入：可见性标志
            layout(std430, binding = 1) buffer VisibilityBuffer {
                uint visible[];
            };

            // 输出：LOD级别 (0=HIGH, 1=MEDIUM, 2=LOW, 3=MINIMAL)
            layout(std430, binding = 2) buffer LODOutput {
                uint lod[];
            };

            // 输出：需要重建的Chunk索引
            layout(std430, binding = 3) buffer RebuildFeedback {
                uint rebuildChunks[];
            };

            // 输出：原子计数器 - 需要重建的Chunk数量
            layout(std430, binding = 4) buffer RebuildCount {
                uint count;
            };

            // Uniform参数
            uniform LODParams {
                mat4 viewProj;
                vec4 viewport;   // x=width, y=height, z=unused, w=viewportHeight
                int objectCount;
                float fovScale;
                int padding[2];
            };

            void main() {
                uint idx = gl_GlobalInvocationID.x;

                if (int(idx) >= objectCount) return;

                // 不可见的Chunk设为MINIMAL
                if (visible[idx] == 0u) {
                    lod[idx] = 3u;
                    return;
                }

                vec3 center = bounds[idx].xyz;
                float radius = bounds[idx].w;

                // 将包围球中心投影到裁剪空间
                vec4 clipPos = viewProj * vec4(center, 1.0);

                // 处理相机后方的情况
                if (clipPos.w <= 0.0) {
                    lod[idx] = 3u;
                    return;
                }

                // 计算屏幕空间覆盖半径: radius / distance * viewport.w * fovScale
                float distance = abs(clipPos.w);
                if (distance < 0.001) distance = 0.001;

                float coverageRadius = radius / distance * viewport.w * fovScale;
                float screenArea = 3.14159265 * coverageRadius * coverageRadius;

                float totalArea = viewport.x * viewport.y;

                // 根据屏幕面积映射到LOD级别
                uint selectedLod;
                if (screenArea > totalArea * 0.05) {
                    selectedLod = 0u; // HIGH
                } else if (screenArea > totalArea * 0.01) {
                    selectedLod = 1u; // MEDIUM
                } else if (screenArea > totalArea * 0.001) {
                    selectedLod = 2u; // LOW
                } else {
                    selectedLod = 3u; // MINIMAL
                }

                lod[idx] = selectedLod;

                // 简化为：始终写入重建反馈（由CPU端决定是否实际重建）
                uint feedbackIdx = atomicAdd(count, 1u);
                rebuildChunks[feedbackIdx] = idx;
            }
            """;

    // ========================================================================
    // 状态字段
    // ========================================================================

    private static boolean initialized = false;
    private static boolean active = false;

    private static int computeProgram = 0;

    // 使用PersistentBufferPool管理的持久映射缓冲区
    // 输入：Chunk包围盒数据 (CPU写入 -> GPU读取)
    private static PersistentBufferPool.PersistentBuffer lodInputBuffer;

    // 输出：LOD级别 (GPU写入 -> CPU读取)
    private static PersistentBufferPool.PersistentBuffer lodOutputBuffer;

    // 输出：重建反馈 (GPU写入 -> CPU读取)
    // 布局: [0] = count (atomic), [1..N] = chunk indices
    private static PersistentBufferPool.PersistentBuffer rebuildFeedbackBuffer;

    private static int currentMaxChunks = 0;

    // 缓存的LOD级别数组（复用，避免每帧GC）
    private static int[] lodLevels = new int[0];

    // 优先级排序的重建列表
    private static int[] rebuildPriorityList = new int[0];

    // 每帧最大重建数量（可配置）
    private static int maxRebuildPerFrame = 16;

    // 总重建请求数
    private static int totalRebuildRequests = 0;

    // 缓冲区大小常量
    private static final int CHUNK_BOUND_SIZE = 16; // vec4 = 4 floats
    private static final int LOD_OUTPUT_SIZE = 4; // uint = 4 bytes
    private static final int FEEDBACK_HEADER_SIZE = 4; // uint count = 4 bytes

    // 暂存可见性缓冲区（临时使用）
    private static PersistentBufferPool.PersistentBuffer tempVisibilityBuffer;

    // Uniform缓冲区大小 (std140)
    // mat4(64) + vec4(16) + int(16) + float(16) + int[2](32) = 144
    // 实际使用: mat4(64) + vec4(16) + int(4) + float(4) + int[2](8) = 96, 加上std140对齐
    private static final int UNIFORM_SIZE = 128;
    private static PersistentBufferPool.PersistentBuffer uniformBuffer;

    // 异步双缓冲状态
    private static boolean pendingResult = false;
    private static long pendingFenceSync = 0;
    private static int pendingChunkCount = 0;
    private static int[] pendingLodLevels = new int[0];
    private static int[] pendingRebuildIndices = new int[0];
    private static int pendingRebuildCount = 0;
    // 暂存上一帧的chunk bounds用于排序
    private static float[] pendingChunkBounds = new float[0];

    // ========================================================================
    // 初始化
    // ========================================================================

    /**
     * 初始化LOD选择器
     *
     * @param maxChunks 最大Chunk数量
     */
    public static void initialize(int maxChunks) {
        if (initialized) {
            if (maxChunks > currentMaxChunks) {
                updateMaxChunks(maxChunks);
            }
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Xe GPU LOD Selector disabled - not Xe architecture");
            return;
        }

        if (!IntelXeDetector.supportsComputeShaders()) {
            UHDFast.LOGGER.info("Xe GPU LOD Selector disabled - compute shaders not supported");
            return;
        }

        try {
            compileComputeShader();
            currentMaxChunks = Math.max(maxChunks, 1024);
            createBuffers();

            initialized = true;
            active = true;

            UHDFast.LOGGER.info("Xe GPU LOD Selector initialized (async double-buffered)");
            UHDFast.LOGGER.info("  Max chunks: {}", currentMaxChunks);
            UHDFast.LOGGER.info("  Max rebuild per frame: {}", maxRebuildPerFrame);
            UHDFast.LOGGER.info("  EU Count: {}", IntelXeDetector.getEUCount());

        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to initialize Xe GPU LOD Selector: {}", e.getMessage());
            active = false;
        }
    }

    /**
     * 编译计算着色器
     */
    private static void compileComputeShader() {
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, LOD_SELECT_COMPUTE_SHADER);
        GL43.glCompileShader(shader);

        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(shader);
            GL43.glDeleteShader(shader);
            throw new RuntimeException("LOD select compute shader compilation failed: " + error);
        }

        computeProgram = GL43.glCreateProgram();
        GL43.glAttachShader(computeProgram, shader);
        GL43.glLinkProgram(computeProgram);

        if (GL43.glGetProgrami(computeProgram, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(computeProgram);
            GL43.glDeleteProgram(computeProgram);
            GL43.glDeleteShader(shader);
            throw new RuntimeException("LOD select program linking failed: " + error);
        }

        GL43.glDeleteShader(shader);
    }

    /**
     * 创建持久映射缓冲区
     */
    private static void createBuffers() {
        // 输入：Chunk包围盒 (CPU -> GPU)
        lodInputBuffer = PersistentBufferPool.acquire(
                (long) currentMaxChunks * CHUNK_BOUND_SIZE,
                PersistentBufferPool.BufferUsage.UPLOAD);

        // 输出：LOD级别 (GPU -> CPU)
        lodOutputBuffer = PersistentBufferPool.acquire(
                (long) currentMaxChunks * LOD_OUTPUT_SIZE,
                PersistentBufferPool.BufferUsage.DOWNLOAD);

        // 输出：重建反馈 (GPU -> CPU)
        // 布局: [count(1 uint)] + [chunk indices(maxChunks uints)]
        rebuildFeedbackBuffer = PersistentBufferPool.acquire(
                (long) (currentMaxChunks + 1) * LOD_OUTPUT_SIZE,
                PersistentBufferPool.BufferUsage.DOWNLOAD);

        // Uniform缓冲区
        uniformBuffer = PersistentBufferPool.acquire(
                UNIFORM_SIZE,
                PersistentBufferPool.BufferUsage.UPLOAD);

        // 预分配数组
        lodLevels = new int[currentMaxChunks];
        rebuildPriorityList = new int[currentMaxChunks];
        pendingLodLevels = new int[currentMaxChunks];
        pendingRebuildIndices = new int[currentMaxChunks];
        pendingChunkBounds = new float[currentMaxChunks * 4];

        // 初始化LOD级别为MINIMAL
        Arrays.fill(lodLevels, LOD_MINIMAL);
    }

    /**
     * 重新创建缓冲区以匹配新的最大Chunk数
     */
    private static void recreateBuffers() {
        // 释放旧缓冲区
        if (lodInputBuffer != null) {
            PersistentBufferPool.release(lodInputBuffer);
        }
        if (lodOutputBuffer != null) {
            PersistentBufferPool.release(lodOutputBuffer);
        }
        if (rebuildFeedbackBuffer != null) {
            PersistentBufferPool.release(rebuildFeedbackBuffer);
        }
        if (uniformBuffer != null) {
            PersistentBufferPool.release(uniformBuffer);
        }

        // 释放临时可见性缓冲区
        if (tempVisibilityBuffer != null) {
            PersistentBufferPool.release(tempVisibilityBuffer);
            tempVisibilityBuffer = null;
        }

        // 重新创建
        createBuffers();

        UHDFast.LOGGER.info("XeGPULODSelector: Buffers recreated (maxChunks={})", currentMaxChunks);
    }

    // ========================================================================
    // 更新最大Chunk数
    // ========================================================================

    /**
     * 更新最大Chunk数量，必要时重新分配缓冲区
     *
     * @param maxChunks 新的最大Chunk数
     */
    public static void updateMaxChunks(int maxChunks) {
        if (maxChunks <= currentMaxChunks) {
            return;
        }

        // 向上取整到2的幂，最小1024
        int newMax = 1024;
        while (newMax < maxChunks) {
            newMax *= 2;
        }
        // 防止过度分配
        if (newMax > 524288) {
            newMax = 524288;
        }

        if (newMax > currentMaxChunks) {
            currentMaxChunks = newMax;
            UHDFast.LOGGER.info("XeGPULODSelector: maxChunks increased to {}", currentMaxChunks);
            if (initialized) {
                recreateBuffers();
            }
        }
    }

    // ========================================================================
    // GPU LOD选择主方法
    // ========================================================================

    /**
     * 提交GPU LOD选择任务（异步，不阻塞）
     * 
     * 调用时机：每帧开始时调用
     * 行为：
     * 1. 如果有上一帧的待读取结果，先读取（此时GPU已完成）
     * 2. 提交当前帧的GPU计算任务
     * 3. 立即返回，不等待结果
     *
     * @param chunkBounds     Chunk包围盒数组 (x, y, z, radius) per chunk
     * @param chunkCount      Chunk数量
     * @param visibility      可见性布尔数组
     * @param viewProjMatrix  视图投影矩阵 (16 floats)
     * @param viewportWidth   视口宽度
     * @param viewportHeight  视口高度
     */
    public static void selectLOD(float[] chunkBounds, int chunkCount, boolean[] visibility,
            float[] viewProjMatrix, float viewportWidth, float viewportHeight) {
        if (!active || !initialized || chunkCount <= 0) {
            return;
        }

        try {
            // 步骤1：读取上一帧的结果（如果有）
            if (pendingResult) {
                int status = GL32.glClientWaitSync(pendingFenceSync, 0, 0);

                if (status == GL32.GL_ALREADY_SIGNALED) {
                    readPendingResults();
                    GL32.glDeleteSync(pendingFenceSync);
                    pendingFenceSync = 0;
                    pendingResult = false;
                } else if (status == GL32.GL_TIMEOUT_EXPIRED) {
                    GL32.glDeleteSync(pendingFenceSync);
                    pendingFenceSync = 0;
                    pendingResult = false;
                    UHDFast.LOGGER.debug("GPU LOD select task timeout, discarding result");
                } else if (status == GL32.GL_WAIT_FAILED) {
                    GL32.glDeleteSync(pendingFenceSync);
                    pendingFenceSync = 0;
                    pendingResult = false;
                    UHDFast.LOGGER.warn("GPU LOD select sync wait failed");
                }
            }

            // 步骤2：提交当前帧的GPU计算任务
            int uploadCount = Math.min(chunkCount, currentMaxChunks);

            // 写入Chunk包围盒到输入缓冲区
            lodInputBuffer.writeFloats(chunkBounds, 0, uploadCount * 4);

            // 创建/复用临时可见性缓冲区
            int visibilityBytes = currentMaxChunks * LOD_OUTPUT_SIZE;
            if (tempVisibilityBuffer == null || tempVisibilityBuffer.sizeBytes < visibilityBytes) {
                if (tempVisibilityBuffer != null) {
                    PersistentBufferPool.release(tempVisibilityBuffer);
                }
                tempVisibilityBuffer = PersistentBufferPool.acquire(
                        visibilityBytes,
                        PersistentBufferPool.BufferUsage.UPLOAD);
            }

            // 将boolean[]可见性写入临时缓冲区
            IntBuffer visibilityView = tempVisibilityBuffer.getIntView();
            for (int i = 0; i < uploadCount; i++) {
                visibilityView.put(i, visibility != null && i < visibility.length && visibility[i] ? 1 : 0);
            }
            // 超出部分设为不可见
            for (int i = uploadCount; i < currentMaxChunks; i++) {
                visibilityView.put(i, 0);
            }

            // 重置原子计数器（重建反馈计数）
            rebuildFeedbackBuffer.getIntView().put(0, 0);

            // 上传Uniform数据
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniformBuffer.glBufferId);
            FloatBuffer uniformView = uniformBuffer.getFloatView();
            uniformView.clear();

            // viewProj矩阵 (16 floats, offset 0-15)
            uniformView.put(viewProjMatrix, 0, 16);

            // viewport (4 floats, offset 16-19)
            uniformView.put(viewportWidth);   // x
            uniformView.put(viewportHeight);  // y
            uniformView.put(0.0f);            // z (unused)
            uniformView.put(viewportHeight);  // w

            // objectCount (offset 20)
            uniformView.put(uploadCount);

            // fovScale (offset 21)
            // 使用默认fovScale，根据视口宽高比计算
            float fovScale = 1.0f;
            if (viewportHeight > 0 && viewportWidth > 0) {
                fovScale = viewportHeight / Math.min(viewportWidth, viewportHeight);
            }
            uniformView.put(fovScale);

            // padding[2] (offset 22-23)
            uniformView.put(0);
            uniformView.put(0);

            uniformView.flip();
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

            // 绑定SSBO
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, lodInputBuffer.glBufferId);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, tempVisibilityBuffer.glBufferId);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, lodOutputBuffer.glBufferId);

            // 重建反馈：binding 3 = chunk indices (从偏移4开始), binding 4 = count (从偏移0开始)
            GL43.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 3,
                    rebuildFeedbackBuffer.glBufferId, FEEDBACK_HEADER_SIZE,
                    (long) currentMaxChunks * LOD_OUTPUT_SIZE);
            GL43.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 4,
                    rebuildFeedbackBuffer.glBufferId, 0, FEEDBACK_HEADER_SIZE);

            // 绑定Uniform
            GL43.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 5, uniformBuffer.glBufferId);

            // 设置Uniform block binding
            int uniformBlockIndex = GL43.glGetUniformBlockIndex(computeProgram, "LODParams");
            if (uniformBlockIndex != GL31.GL_INVALID_INDEX) {
                GL31.glUniformBlockBinding(computeProgram, uniformBlockIndex, 5);
            }

            // 调度计算着色器
            GL20.glUseProgram(computeProgram);
            int dispatchX = (uploadCount + 63) / 64;
            GL43.glDispatchCompute(dispatchX, 1, 1);

            // 内存屏障：确保所有SSBO写入（LOD输出、重建反馈、原子计数器）在fence之前完成
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            // 插入fence同步对象（不等待GPU完成）
            pendingFenceSync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (pendingFenceSync == 0) {
                throw new RuntimeException("Failed to create fence sync");
            }

            // 保存待处理的Chunk数据用于下一帧读取
            pendingChunkCount = uploadCount;
            if (pendingChunkBounds.length < uploadCount * 4) {
                pendingChunkBounds = new float[uploadCount * 4];
            }
            System.arraycopy(chunkBounds, 0, pendingChunkBounds, 0, uploadCount * 4);
            pendingResult = true;

            // 清理状态
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, 0);
            GL43.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 5, 0);
            GL20.glUseProgram(0);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

        } catch (Exception e) {
            UHDFast.LOGGER.warn("GPU LOD selection submit failed: {}", e.getMessage());
            if (pendingFenceSync != 0) {
                GL32.glDeleteSync(pendingFenceSync);
                pendingFenceSync = 0;
            }
            pendingResult = false;
        }
    }

    /**
     * 读取待处理的GPU结果
     */
    @SuppressWarnings("all")
    private static void readPendingResults() {
        try {
            if (pendingChunkCount <= 0) {
                return;
            }

            int count = pendingChunkCount;

            // 读取LOD输出缓冲区
            IntBuffer lodView = lodOutputBuffer.getIntView();
            for (int i = 0; i < count; i++) {
                int lod = lodView.get(i);
                // 限制有效范围
                if (lod < LOD_HIGH || lod > LOD_MINIMAL) {
                    lod = LOD_MINIMAL;
                }
                pendingLodLevels[i] = lod;
            }
            // 复制到全局缓存
            if (lodLevels.length < count) {
                lodLevels = new int[count];
            }
            System.arraycopy(pendingLodLevels, 0, lodLevels, 0, count);
            // 超出部分设为MINIMAL
            for (int i = count; i < lodLevels.length; i++) {
                lodLevels[i] = LOD_MINIMAL;
            }

            // 读取重建反馈缓冲区
            IntBuffer feedbackView = rebuildFeedbackBuffer.getIntView();
            int rebuildCount = feedbackView.get(0);
            if (rebuildCount > 0) {
                // 限制重建数量
                int actualRebuildCount = Math.min(rebuildCount, currentMaxChunks);

                // 读取重建索引
                for (int i = 0; i < actualRebuildCount; i++) {
                    int chunkIdx = feedbackView.get(i + 1);
                    if (chunkIdx >= 0 && chunkIdx < count) {
                        pendingRebuildIndices[i] = chunkIdx;
                    }
                }

                // 按优先级排序（距离相机更近的Chunk优先）
                // 使用pendingChunkBounds计算距离
                sortRebuildByPriority(pendingRebuildIndices, actualRebuildCount, pendingChunkBounds);

                // 复制到全局重建优先级列表
                if (rebuildPriorityList.length < actualRebuildCount) {
                    rebuildPriorityList = new int[actualRebuildCount];
                }
                System.arraycopy(pendingRebuildIndices, 0, rebuildPriorityList, 0, actualRebuildCount);
                pendingRebuildCount = actualRebuildCount;
                totalRebuildRequests += actualRebuildCount;
            } else {
                pendingRebuildCount = 0;
            }

        } catch (Exception e) {
            UHDFast.LOGGER.warn("Failed to read GPU LOD results: {}", e.getMessage());
            pendingRebuildCount = 0;
        }
    }

    /**
     * 按优先级排序重建列表（距离相机更近的Chunk优先）
     * 使用Chunk包围盒中心到原点的距离作为近似
     *
     * @param indices     Chunk索引数组
     * @param count       索引数量
     * @param chunkBounds Chunk包围盒数据
     */
    private static void sortRebuildByPriority(int[] indices, int count, float[] chunkBounds) {
        if (count <= 1) {
            return;
        }

        // 计算每个Chunk的距离并排序（简单选择排序，重建列表通常较小）
        float[] distances = new float[count];
        for (int i = 0; i < count; i++) {
            int idx = indices[i];
            int boundOffset = idx * 4;
            if (boundOffset + 2 < chunkBounds.length) {
                float dx = chunkBounds[boundOffset];
                float dy = chunkBounds[boundOffset + 1];
                float dz = chunkBounds[boundOffset + 2];
                distances[i] = dx * dx + dy * dy + dz * dz;
            } else {
                distances[i] = Float.MAX_VALUE;
            }
        }

        // 插入排序（距离近的优先）
        for (int i = 1; i < count; i++) {
            float keyDist = distances[i];
            int keyIdx = indices[i];
            int j = i - 1;
            while (j >= 0 && distances[j] > keyDist) {
                distances[j + 1] = distances[j];
                indices[j + 1] = indices[j];
                j--;
            }
            distances[j + 1] = keyDist;
            indices[j + 1] = keyIdx;
        }
    }

    // ========================================================================
    // 查询接口
    // ========================================================================

    /**
     * 获取指定Chunk的LOD级别
     *
     * @param chunkIndex Chunk索引
     * @return LOD级别 (0=HIGH, 1=MEDIUM, 2=LOW, 3=MINIMAL)
     */
    public static int getLODLevel(int chunkIndex) {
        if (chunkIndex >= 0 && chunkIndex < lodLevels.length) {
            return lodLevels[chunkIndex];
        }
        return LOD_MINIMAL;
    }

    /**
     * 获取所有Chunk的LOD级别数组
     *
     * @return LOD级别数组
     */
    public static int[] getLODLevels() {
        return lodLevels;
    }

    /**
     * 获取优先级排序的重建Chunk索引列表
     *
     * @return 重建Chunk索引数组
     */
    public static int[] getRebuildPriorityList() {
        return rebuildPriorityList;
    }

    /**
     * 获取需要重建的Chunk数量
     *
     * @return 重建Chunk数量
     */
    public static int getRebuildPriorityCount() {
        return pendingRebuildCount;
    }

    /**
     * 设置每帧最大重建数量
     *
     * @param max 最大重建数量
     */
    public static void setMaxRebuildPerFrame(int max) {
        maxRebuildPerFrame = Math.max(1, max);
    }

    /**
     * 消费并返回本帧的重建预算
     *
     * @return 本帧可用的重建预算（实际需要重建的数量和每帧最大限制中的较小值）
     */
    public static int consumeRebuildBudget() {
        int budget = Math.min(pendingRebuildCount, maxRebuildPerFrame);
        // 从列表中移除已消费的预算
        if (budget > 0 && rebuildPriorityList.length > budget) {
            System.arraycopy(rebuildPriorityList, budget, rebuildPriorityList, 0,
                    pendingRebuildCount - budget);
        }
        pendingRebuildCount -= budget;
        if (pendingRebuildCount < 0) {
            pendingRebuildCount = 0;
        }
        return budget;
    }

    // ========================================================================
    // 状态查询
    // ========================================================================

    /**
     * 检查LOD选择器是否已激活
     *
     * @return 是否已激活并初始化
     */
    public static boolean isActive() {
        return active && initialized;
    }

    /**
     * 获取当前最大Chunk数
     */
    public static int getCurrentMaxChunks() {
        return currentMaxChunks;
    }

    /**
     * 获取总重建请求数
     */
    public static int getTotalRebuildRequests() {
        return totalRebuildRequests;
    }

    // ========================================================================
    // 清理
    // ========================================================================

    /**
     * 销毁LOD选择器，释放所有资源
     */
    public static void destroy() {
        if (computeProgram != 0) {
            GL43.glDeleteProgram(computeProgram);
            computeProgram = 0;
        }

        // 清理pending sync
        if (pendingFenceSync != 0) {
            GL32.glDeleteSync(pendingFenceSync);
            pendingFenceSync = 0;
        }

        // 释放持久映射缓冲区回池
        if (lodInputBuffer != null) {
            PersistentBufferPool.release(lodInputBuffer);
            lodInputBuffer = null;
        }
        if (lodOutputBuffer != null) {
            PersistentBufferPool.release(lodOutputBuffer);
            lodOutputBuffer = null;
        }
        if (rebuildFeedbackBuffer != null) {
            PersistentBufferPool.release(rebuildFeedbackBuffer);
            rebuildFeedbackBuffer = null;
        }
        if (uniformBuffer != null) {
            PersistentBufferPool.release(uniformBuffer);
            uniformBuffer = null;
        }
        if (tempVisibilityBuffer != null) {
            PersistentBufferPool.release(tempVisibilityBuffer);
            tempVisibilityBuffer = null;
        }

        initialized = false;
        active = false;
        pendingResult = false;
        pendingRebuildCount = 0;
        lodLevels = new int[0];
        rebuildPriorityList = new int[0];
        pendingLodLevels = new int[0];
        pendingRebuildIndices = new int[0];
        pendingChunkBounds = new float[0];
        currentMaxChunks = 0;
    }

    /**
     * 重置统计信息
     */
    public static void resetStats() {
        totalRebuildRequests = 0;
        pendingResult = false;
        pendingRebuildCount = 0;
        if (lodLevels.length > 0) {
            Arrays.fill(lodLevels, LOD_MINIMAL);
        }
        if (pendingFenceSync != 0) {
            GL32.glDeleteSync(pendingFenceSync);
            pendingFenceSync = 0;
        }
    }
}