package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU视锥剔除计算着色器 - 异步双缓冲版本
 * 
 * 实现原理：
 * - 第N帧：提交GPU计算任务，不等待结果
 * - 第N+1帧：读取第N-1帧的结果并使用
 * 
 * 优势：
 * - 完全不阻塞渲染线程
 * - GPU和CPU并行工作
 * - 延迟一帧对渲染影响很小（相机移动缓慢）
 */
public class XeFrustumCuller {

    // ========================================================================
    // 动态最大对象数 - 根据渲染距离自动调整
    // ========================================================================

    /** 基础最大对象数（renderDistance=8 时的默认值） */
    private static final int BASE_MAX_OBJECTS = 16384;

    /** 当前实际最大对象数 */
    private static int currentMaxObjects = BASE_MAX_OBJECTS;

    /** 上一次设置的渲染距离 */
    private static int lastRenderDistance = 0;

    /**
     * 根据渲染距离更新缓冲区容量。
     * 渲染距离每增加 1，section 数量按 (2*D+1)^2 * (maxY-minY) 增长。
     * 使用 (2*D+1)^2 * 28 估算，向上取整到 2 的幂。
     *
     * @param renderDistance 当前渲染距离（区块数）
     */
    public static void updateMaxObjects(int renderDistance) {
        if (renderDistance == lastRenderDistance) {
            return;
        }
        lastRenderDistance = renderDistance;

        // 估算需要的 section 数量: (2*D+1)^2 * 28 (Y方向约28层)
        int estimatedSections = (2 * renderDistance + 1) * (2 * renderDistance + 1) * 28;

        // 向上取整到 2 的幂，最小 16384
        int newMax = BASE_MAX_OBJECTS;
        while (newMax < estimatedSections) {
            newMax *= 2;
        }
        // 防止过度分配，上限 524288 (32 距离约需 118k, 64 距离约需 424k)
        if (newMax > 524288) {
            newMax = 524288;
        }

        if (newMax > currentMaxObjects) {
            currentMaxObjects = newMax;
            UHDFast.LOGGER.info("XeFrustumCuller: maxObjects increased to {} (renderDistance={}, estimated={})",
                    currentMaxObjects, renderDistance, estimatedSections);
            // 重建缓冲区以匹配新大小
            if (initialized) {
                recreateBuffers();
            }
        }
    }

    /**
     * 获取当前最大对象数
     */
    public static int getMaxObjects() {
        return currentMaxObjects;
    }

    private static final String FRUSTUM_CULL_COMPUTE_SHADER = """
            #version 430 core

            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

            // 输入：对象包围盒数据 (x, y, z, radius) per object
            layout(std430, binding = 0) buffer ObjectBounds {
                vec4 bounds[];  // xyz = center, w = radius
            };

            // 输出：可见性标志
            layout(std430, binding = 1) buffer VisibilityBuffer {
                uint visible[];
            };

            // 视锥面参数 + Hi-Z 参数
            layout(std140, binding = 2) uniform FrustumPlanes {
                vec4 planes[6];  // xyz = normal, w = distance
                mat4 viewProjection;
                vec4 cameraPos;
                float nearPlane;
                float farPlane;
                int objectCount;
                int enableHiZ;
                int hizMipCount;
                int viewportWidth;
                int viewportHeight;
                int pad;
            };

            // Hi-Z 缓冲（由 XeHiZBuffer 构建的多级深度缓冲）
            layout(std430, binding = 3) buffer HiZBuffer {
                vec4 hizLevels[];
            };

            // Hi-Z 统计（原子计数器，记录被 Hi-Z 剔除的物体数）
            layout(std430, binding = 4) buffer HiZStats {
                uint hizCulled;
            };

            // 球体-视锥面碰撞检测
            bool sphereInFrustum(vec3 center, float radius) {
                for (int i = 0; i < 6; i++) {
                    float dist = dot(planes[i].xyz, center) + planes[i].w;
                    if (dist < -radius) {
                        return false;
                    }
                }
                return true;
            }

            // 距离剔除
            bool withinDistance(vec3 center, float maxDistSq) {
                vec3 delta = center - cameraPos.xyz;
                float distSq = dot(delta, delta);
                return distSq <= maxDistSq;
            }

            // 计算给定 level 在 Hi-Z 缓冲中的起始 vec4 索引
            int getLevelOffset(int level) {
                int offset = 0;
                for (int i = 0; i < level && i < hizMipCount; i++) {
                    int w = (viewportWidth + (1 << i) - 1) >> i;
                    int h = (viewportHeight + (1 << i) - 1) >> i;
                    int tw = (w + 1) >> 1;
                    int th = (h + 1) >> 1;
                    int totalFloats = tw * th;
                    int totalVec4s = (totalFloats + 3) / 4;
                    offset += totalVec4s;
                }
                return offset;
            }

            // 从 Hi-Z 缓冲的指定 mip level 采样深度值
            float sampleHiZ(int level, int x, int y) {
                int w = (viewportWidth + (1 << level) - 1) >> level;
                int h = (viewportHeight + (1 << level) - 1) >> level;
                int tw = (w + 1) >> 1;
                int th = (h + 1) >> 1;

                // 边界钳制
                x = clamp(x, 0, tw - 1);
                y = clamp(y, 0, th - 1);

                int idx = y * tw + x;
                int vecOffset = getLevelOffset(level);
                int vecIdx = vecOffset + (idx / 4);
                int compIdx = idx % 4;
                return hizLevels[vecIdx][compIdx];
            }

            void main() {
                uint idx = gl_GlobalInvocationID.x;

                if (int(idx) >= objectCount) return;

                vec3 center = bounds[idx].xyz;
                float radius = bounds[idx].w;

                // GPU并行执行视锥剔除
                bool frustumVisible = sphereInFrustum(center, radius);

                // GPU执行距离剔除
                bool distanceVisible = withinDistance(center, farPlane * farPlane);

                bool isVisible = frustumVisible && distanceVisible;

                // Hi-Z 遮挡测试（仅当物体通过视锥和距离剔除时执行）
                if (enableHiZ != 0 && isVisible) {
                    // 将包围球中心投影到裁剪空间
                    vec4 clipPos = viewProjection * vec4(center, 1.0);

                    // 如果在相机后面，不遮挡
                    if (clipPos.w > 0.0) {
                        // NDC 坐标
                        vec3 ndc = clipPos.xyz / clipPos.w;

                        // 屏幕空间坐标 [0, viewportSize]
                        vec2 screenPos = vec2(
                            (ndc.x * 0.5 + 0.5) * viewportWidth,
                            (ndc.y * 0.5 + 0.5) * viewportHeight
                        );

                        // 计算屏幕空间包围半径
                        float projScale = viewportWidth / (2.0 * clipPos.w);
                        float screenRadius = radius * abs(projScale);

                        // 如果屏幕半径太小（物体太小），跳过 Hi-Z 测试
                        if (screenRadius >= 1.0) {
                            // 屏幕空间包围盒
                            int minX = int(max(0.0, screenPos.x - screenRadius));
                            int minY = int(max(0.0, screenPos.y - screenRadius));
                            int maxX = int(min(float(viewportWidth - 1), screenPos.x + screenRadius));
                            int maxY = int(min(float(viewportHeight - 1), screenPos.y + screenRadius));

                            int coverageX = maxX - minX + 1;
                            int coverageY = maxY - minY + 1;
                            int maxCoverage = max(coverageX, coverageY);

                            // 根据屏幕覆盖范围选择合适的 Hi-Z mip 层级
                            int mipLevel = 0;
                            int mipSize = maxCoverage;
                            while (mipSize > 2 && mipLevel < hizMipCount - 1) {
                                mipSize = (mipSize + 1) >> 1;
                                mipLevel++;
                            }

                            // 在所选 mip 层级采样 Hi-Z 深度
                            int hizX = (minX + maxX) / 2;
                            int hizY = (minY + maxY) / 2;
                            float hizDepth = sampleHiZ(mipLevel, hizX, hizY);

                            // 计算物体最近深度（保守估计）
                            float closestDepth = ndc.z - (screenRadius / viewportHeight) * 2.0;

                            // 物体最近深度 > Hi-Z 深度 → 物体在已渲染表面之后 → 遮挡
                            if (closestDepth > hizDepth) {
                                isVisible = false;
                                atomicAdd(hizCulled, 1u);
                            }
                        }
                    }
                }

                // 写入可见性结果
                visible[idx] = isVisible ? 1u : 0u;
            }
            """;

    private static int computeProgram = 0;

    private static boolean initialized = false;
    private static boolean active = false;

    private static final int BOUND_SIZE = 16; // vec4 = 4 floats
    private static final int VISIBILITY_SIZE = 4; // uint = 4 bytes
    private static final int UNIFORM_SIZE = 6 * 16 + 64 + 16 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4; // planes + VP + camera + params + HiZ params + pad

    // 使用PersistentBufferPool管理的持久映射缓冲区
    // 优化：直接使用持久映射缓冲区ID作为SSBO，消除glBufferSubData的GPU拷贝
    private static PersistentBufferPool.PersistentBuffer persistentBoundsBuffer;
    private static PersistentBufferPool.PersistentBuffer persistentUniformBuffer;
    private static PersistentBufferPool.PersistentBuffer persistentVisibilityBuffer;

    // Hi-Z 遮挡剔除专用缓冲
    private static PersistentBufferPool.PersistentBuffer hizStatsBuffer;

    // ========================================================================
    // Hi-Z 遮挡剔除状态
    // ========================================================================

    /** Hi-Z 遮挡剔除是否启用 */
    private static boolean hizEnabled = false;

    /** 当前帧被 Hi-Z 剔除的物体数量 */
    private static int hizCulledCount = 0;

    /** 累计被 Hi-Z 剔除的物体数量 */
    private static int totalHizCulled = 0;

    /** 缓存的视图投影矩阵（用于 Hi-Z 测试） */
    private static final float[] lastViewProj = new float[16];

    /** 优化：批量读取GPU结果用的缓存int数组，避免每帧new int[] */
    private static int[] bulkReadBuffer = new int[16384];

    /** 当前深度纹理 ID */
    private static int depthTextureId = 0;

    private static final AtomicInteger totalCulled = new AtomicInteger(0);
    private static final AtomicInteger totalVisible = new AtomicInteger(0);
    private static final AtomicInteger frameCulled = new AtomicInteger(0);
    private static final AtomicInteger frameVisible = new AtomicInteger(0);
    private static long totalComputeTimeNs = 0;
    private static int dispatchCount = 0;

    // 异步双缓冲状态
    private static boolean pendingResult = false; // 是否有待读取的结果
    private static long pendingFenceSync = 0; // 待读取结果的fence sync
    private static int pendingObjectCount = 0; // 待读取结果的对象数量
    private static boolean[] lastFrameVisibility = null; // 上一帧的剔除结果（由readPendingResults填充）
    private static boolean hasValidVisibility = false; // 是否已成功回读过GPU结果
    private static int lastFrameObjectCount = 0; // 上一帧的对象数量

    // ========== 修复：保存与GPU结果对应的section keys，避免异步错位 ==========
    /** 当前提交GPU任务时的section keys（暂存，供下次readPendingResults使用） */
    private static long[] pendingSectionKeys = null;
    private static int pendingSectionKeyCount = 0;
    /** 上一帧读回GPU结果时对应的section keys */
    private static long[] lastFrameSectionKeys = null;
    private static int lastFrameSectionKeyCount = 0;

    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Xe Frustum Culler disabled - not Xe architecture");
            return;
        }

        if (!IntelXeDetector.supportsComputeShaders()) {
            UHDFast.LOGGER.info("Xe Frustum Culler disabled - compute shaders not supported");
            return;
        }

        try {
            compileComputeShader();
            createBuffers();

            // 从PersistentBufferPool获取持久映射缓冲区
            // bounds: CPU写入 -> GPU读取 (UPLOAD)
            persistentBoundsBuffer = PersistentBufferPool.acquire(
                    (long) currentMaxObjects * BOUND_SIZE,
                    PersistentBufferPool.BufferUsage.UPLOAD);

            // uniform: CPU写入 -> GPU读取 (UPLOAD)
            persistentUniformBuffer = PersistentBufferPool.acquire(
                    UNIFORM_SIZE,
                    PersistentBufferPool.BufferUsage.UPLOAD);

            // visibility: GPU写入 -> CPU读取 (DOWNLOAD)
            persistentVisibilityBuffer = PersistentBufferPool.acquire(
                    (long) currentMaxObjects * VISIBILITY_SIZE,
                    PersistentBufferPool.BufferUsage.DOWNLOAD);

            // Hi-Z 统计缓冲（原子计数器，CPU写入重置 + GPU原子递增 + CPU读取）
            hizStatsBuffer = PersistentBufferPool.acquire(
                    4L, // 一个 uint
                    PersistentBufferPool.BufferUsage.PERSISTENT);

            // 预分配 lastFrameVisibility 数组，避免每帧分配
            // 注意：初始化为null，由readPendingResults在首次成功回读后填充
            lastFrameVisibility = null;
            hasValidVisibility = false;

            initialized = true;
            active = true;

            UHDFast.LOGGER.info("Xe GPU Frustum Culler initialized (async double-buffered)");
            UHDFast.LOGGER.info("  Max objects: {}", currentMaxObjects);
            UHDFast.LOGGER.info("  EU Count: {}", IntelXeDetector.getEUCount());
            UHDFast.LOGGER.info("  Compute Shader: OpenGL 4.3");
            UHDFast.LOGGER.info("  Using PersistentBufferPool for zero-copy transfers");

        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to initialize Xe Frustum Culler: {}", e.getMessage());
            active = false;
        }
    }

    private static void compileComputeShader() {
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, FRUSTUM_CULL_COMPUTE_SHADER);
        GL43.glCompileShader(shader);

        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(shader);
            GL43.glDeleteShader(shader);
            throw new RuntimeException("Frustum cull compute shader compilation failed: " + error);
        }

        computeProgram = GL43.glCreateProgram();
        GL43.glAttachShader(computeProgram, shader);
        GL43.glLinkProgram(computeProgram);

        if (GL43.glGetProgrami(computeProgram, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(computeProgram);
            GL43.glDeleteProgram(computeProgram);
            GL43.glDeleteShader(shader);
            throw new RuntimeException("Frustum cull program linking failed: " + error);
        }

        GL43.glDeleteShader(shader);
    }

    private static void createBuffers() {
        // 优化：不再创建独立的SSBO，直接使用持久映射缓冲区作为SSBO
        // 持久映射缓冲区已通过glBufferStorage创建，可直接绑定为SSBO
        UHDFast.LOGGER.debug("XeFrustumCuller: Using persistent buffers as SSBOs, no separate SSBOs needed");
    }

    /**
     * 重建缓冲区以匹配新的 currentMaxObjects。
     * 优化：持久缓冲区已是SSBO，只需重新分配持久缓冲区
     */
    private static void recreateBuffers() {
        // 重建 PersistentBuffer 池中的缓冲区（这些也是SSBO）
        if (persistentBoundsBuffer != null) {
            PersistentBufferPool.release(persistentBoundsBuffer);
        }
        if (persistentUniformBuffer != null) {
            PersistentBufferPool.release(persistentUniformBuffer);
        }
        if (persistentVisibilityBuffer != null) {
            PersistentBufferPool.release(persistentVisibilityBuffer);
        }

        persistentBoundsBuffer = PersistentBufferPool.acquire(
                (long) currentMaxObjects * BOUND_SIZE,
                PersistentBufferPool.BufferUsage.UPLOAD);
        persistentUniformBuffer = PersistentBufferPool.acquire(
                UNIFORM_SIZE,
                PersistentBufferPool.BufferUsage.UPLOAD);
        persistentVisibilityBuffer = PersistentBufferPool.acquire(
                (long) currentMaxObjects * VISIBILITY_SIZE,
                PersistentBufferPool.BufferUsage.DOWNLOAD);

        // 重新分配 lastFrameVisibility 数组
        lastFrameVisibility = null;
        hasValidVisibility = false;

        // 重置异步状态：旧buffer的fence sync对新buffer无效
        if (pendingFenceSync != 0) {
            GL32.glDeleteSync(pendingFenceSync);
            pendingFenceSync = 0;
        }
        pendingResult = false;
        pendingObjectCount = 0;

        // 重置section key状态
        pendingSectionKeys = null;
        pendingSectionKeyCount = 0;
        lastFrameSectionKeys = null;
        lastFrameSectionKeyCount = 0;

        UHDFast.LOGGER.info("XeFrustumCuller: Buffers recreated (maxObjects={})", currentMaxObjects);
    }

    /**
     * 提交GPU视锥剔除任务（异步，不阻塞）- 向后兼容版本（无section keys）
     * 
     * 调用时机：每帧开始时调用
     * 行为：
     * 1. 如果有上一帧的待读取结果，先读取（此时GPU已完成）
     * 2. 提交当前帧的GPU计算任务
     * 3. 立即返回，不等待结果
     * 
     * @param objectBounds  对象包围盒数组 (x, y, z, radius) per object
     * @param objectCount   对象数量
     * @param frustumPlanes 视锥面参数 (6个面)
     * @param cameraPos     相机位置
     * @param maxDistance   最大可见距离
     */
    public static void submitCullTask(float[] objectBounds, int objectCount, float[] frustumPlanes,
            float[] cameraPos, float maxDistance) {
        submitCullTask(objectBounds, objectCount, frustumPlanes, cameraPos, maxDistance, null, 0, 0, null, 0);
    }

    /**
     * 提交GPU视锥剔除任务（异步，不阻塞）- 完整版本，支持 Hi-Z 遮挡剔除和section keys对齐
     * 
     * 调用时机：每帧开始时调用
     * 行为：
     * 1. 如果有上一帧的待读取结果，先读取（此时GPU已完成）
     * 2. 提交当前帧的GPU计算任务，包括视锥剔除、距离剔除和 Hi-Z 遮挡剔除
     * 3. 立即返回，不等待结果
     * 
     * @param objectBounds  对象包围盒数组 (x, y, z, radius) per object
     * @param objectCount   对象数量
     * @param frustumPlanes 视锥面参数 (6个面)
     * @param cameraPos     相机位置
     * @param maxDistance   最大可见距离
     * @param viewProj      视图投影矩阵 (16 floats, column-major)，用于 Hi-Z 测试。为 null 时禁用 Hi-Z
     * @param depthTexId    深度纹理 ID（用于 Hi-Z 参考）
     * @param hizMipCount   Hi-Z 缓冲的 mip 层级数量
     * @param sectionKeys   当前提交的section keys数组（用于与GPU结果对齐，可为null）
     * @param sectionKeyCount section keys数量
     */
    public static void submitCullTask(float[] objectBounds, int objectCount, float[] frustumPlanes,
            float[] cameraPos, float maxDistance, float[] viewProj, int depthTexId, int hizMipCount,
            long[] sectionKeys, int sectionKeyCount) {
        if (!active || !initialized || objectCount <= 0) {
            return;
        }

        long startTime = System.nanoTime();

        try {
            // 步骤1：读取上一帧的结果（如果有）
            if (pendingResult) {
                // 检查GPU是否完成（不阻塞）
                int status = GL32.glClientWaitSync(pendingFenceSync, 0, 0); // 立即返回

                if (status == GL32.GL_ALREADY_SIGNALED) {
                    // GPU已完成，读取结果
                    readPendingResults();
                    GL32.glDeleteSync(pendingFenceSync);
                    pendingFenceSync = 0;
                    pendingResult = false;
                } else if (status == GL32.GL_TIMEOUT_EXPIRED) {
                    // GPU还没完成，删除旧的sync并继续（结果将被丢弃）
                    GL32.glDeleteSync(pendingFenceSync);
                    pendingFenceSync = 0;
                    pendingResult = false;
                    UHDFast.LOGGER.debug("GPU cull task timeout, discarding result");
                } else if (status == GL32.GL_WAIT_FAILED) {
                    // 出错，清理
                    GL32.glDeleteSync(pendingFenceSync);
                    pendingFenceSync = 0;
                    pendingResult = false;
                    UHDFast.LOGGER.warn("GPU cull sync wait failed");
                }
            }

            // 步骤1.5：保存当前提交的section keys（供下一帧读回时使用）
            // 必须在readPendingResults之后保存，因为readPendingResults会读取pendingSectionKeys
            // 注意：必须复制数组，因为调用方复用同一数组，下一帧会被覆盖
            if (sectionKeys != null && sectionKeyCount > 0) {
                if (pendingSectionKeys == null || pendingSectionKeys.length < sectionKeyCount) {
                    pendingSectionKeys = new long[sectionKeyCount];
                }
                System.arraycopy(sectionKeys, 0, pendingSectionKeys, 0, sectionKeyCount);
                pendingSectionKeyCount = sectionKeyCount;
            }

            // 步骤2：提交当前帧的GPU计算任务
            int uploadCount = Math.min(objectCount, currentMaxObjects);

            // 优化：直接写入持久映射缓冲区（零拷贝上传到GPU），
            // 持久缓冲区本身就是SSBO，无需glBufferSubData复制到独立SSBO
            persistentBoundsBuffer.writeFloats(objectBounds, 0, uploadCount * 4);

            // 判断 Hi-Z 是否启用：viewProj 不为 null 且 hizEnabled 为 true
            boolean hiZActive = hizEnabled && viewProj != null && hizMipCount > 0
                    && XeHiZBuffer.isActive() && XeHiZBuffer.getHiZBufferId() != 0;

            // 重置 Hi-Z 原子计数器
            if (hiZActive && hizStatsBuffer != null) {
                hizStatsBuffer.getIntView().put(0).position(0);
            }

            // 上传视锥参数到持久映射UBO
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, persistentUniformBuffer.glBufferId);
            FloatBuffer uniformView = persistentUniformBuffer.getFloatView();
            uniformView.clear();

            // 6个视锥面 (24 floats, offset 0-23)
            uniformView.put(frustumPlanes, 0, 24);

            // viewProjection矩阵 (16 floats, offset 24-39)
            if (hiZActive && viewProj != null) {
                uniformView.put(viewProj, 0, 16);
            } else {
                for (int i = 0; i < 16; i++) {
                    uniformView.put(0.0f);
                }
            }

            // cameraPos (4 floats, offset 40-43)
            uniformView.put(cameraPos[0]);
            uniformView.put(cameraPos[1]);
            uniformView.put(cameraPos[2]);
            uniformView.put(1.0f); // padding/w

            // nearPlane, farPlane, objectCount (offset 44-46)
            uniformView.put(0.1f); // near
            uniformView.put(maxDistance); // far
            uniformView.put(uploadCount); // objectCount

            // Hi-Z 参数 (offset 47-51)
            uniformView.put(hiZActive ? 1 : 0); // enableHiZ
            uniformView.put(hiZActive ? hizMipCount : 0); // hizMipCount
            uniformView.put(hiZActive ? XeHiZBuffer.getViewportWidth() : 0); // viewportWidth
            uniformView.put(hiZActive ? XeHiZBuffer.getViewportHeight() : 0); // viewportHeight
            uniformView.put(0); // pad

            uniformView.flip();
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

            // 绑定 SSBO: bounds (0), visibility (1), HiZ (3), HiZStats (4)
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, persistentBoundsBuffer.glBufferId);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, persistentVisibilityBuffer.glBufferId);
            GL43.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 2, persistentUniformBuffer.glBufferId);

            if (hiZActive) {
                GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, XeHiZBuffer.getHiZBufferId());
                GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, hizStatsBuffer.glBufferId);
            }

            // 调度计算
            GL20.glUseProgram(computeProgram);
            int dispatchX = (uploadCount + 63) / 64;
            GL43.glDispatchCompute(dispatchX, 1, 1);

            // 内存屏障：确保SSBO写入在fence sync之前完成
            // 包括可见性缓冲(SSBO)和Hi-Z统计缓冲(SSBO)的写入
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            // 插入fence同步对象（不等待GPU完成）
            pendingFenceSync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (pendingFenceSync == 0) {
                throw new RuntimeException("Failed to create fence sync");
            }

            pendingObjectCount = uploadCount;
            pendingResult = true;

            // 清理状态
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GL43.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 2, 0);
            if (hiZActive) {
                GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, 0);
                GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, 0);
            }
            GL20.glUseProgram(0);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

            long endTime = System.nanoTime();
            totalComputeTimeNs += (endTime - startTime);
            dispatchCount++;

        } catch (Exception e) {
            UHDFast.LOGGER.warn("GPU frustum culling submit failed: {}", e.getMessage());
            // 清理可能的残留状态
            if (pendingFenceSync != 0) {
                GL32.glDeleteSync(pendingFenceSync);
                pendingFenceSync = 0;
            }
            pendingResult = false;
        }
    }

    /**
     * 读取待处理的GPU结果
     * 优化：批量读取GPU结果，使用int[]批量get减少JNI调用次数
     */
    private static void readPendingResults() {
        try {
            // 优化：直接读取持久映射可见性缓冲区（零拷贝读回）
            // 持久缓冲区是COHERENT的，GPU写入的数据对CPU立即可见
            IntBuffer visibilityView = persistentVisibilityBuffer.getIntView();
            if (lastFrameVisibility == null || lastFrameVisibility.length < pendingObjectCount) {
                lastFrameVisibility = new boolean[pendingObjectCount];
            }
            lastFrameObjectCount = pendingObjectCount;
            int visibleCount = 0;
            int culledCount = 0;

            // 优化：批量读取到int数组，减少逐次JNI调用的开销
            int count = pendingObjectCount;
            // 确保缓存数组足够大
            if (bulkReadBuffer.length < count) {
                bulkReadBuffer = new int[count];
            }
            visibilityView.get(bulkReadBuffer, 0, count);
            for (int i = 0; i < count; i++) {
                lastFrameVisibility[i] = bulkReadBuffer[i] != 0;
                if (lastFrameVisibility[i]) {
                    visibleCount++;
                } else {
                    culledCount++;
                }
            }
            // 将超出部分标记为可见（安全回退）
            for (int i = pendingObjectCount; i < lastFrameVisibility.length; i++) {
                lastFrameVisibility[i] = true;
            }

            totalVisible.addAndGet(visibleCount);
            totalCulled.addAndGet(culledCount);
            frameVisible.set(visibleCount);
            frameCulled.set(culledCount);

            // 标记已有有效结果
            hasValidVisibility = true;

            // 保存与可见性结果对应的section keys（复制，因为pendingSectionKeys下一帧会被覆盖）
            if (pendingSectionKeys != null && pendingSectionKeyCount > 0) {
                if (lastFrameSectionKeys == null || lastFrameSectionKeys.length < pendingSectionKeyCount) {
                    lastFrameSectionKeys = new long[pendingSectionKeyCount];
                }
                System.arraycopy(pendingSectionKeys, 0, lastFrameSectionKeys, 0, pendingSectionKeyCount);
                lastFrameSectionKeyCount = pendingSectionKeyCount;
            }

            // 读取 Hi-Z 原子计数器
            if (hizEnabled && hizStatsBuffer != null) {
                hizCulledCount = hizStatsBuffer.getIntView().get(0);
                totalHizCulled += hizCulledCount;
            } else {
                hizCulledCount = 0;
            }

        } catch (Exception e) {
            UHDFast.LOGGER.warn("Failed to read GPU cull results: {}", e.getMessage());
            lastFrameVisibility = null;
            lastFrameObjectCount = 0;
        }
    }

    /**
     * 获取上一帧的剔除结果
     * 优化：只有在成功回读过GPU结果后才返回有效数据，避免首次帧全false导致全剔除
     * 
     * @return 可见性布尔数组，如果还没有有效结果返回null
     */
    public static boolean[] getLastFrameVisibility() {
        return hasValidVisibility ? lastFrameVisibility : null;
    }

    /**
     * 获取与上一帧剔除结果对应的section keys
     * 修复：与getLastFrameVisibility()配对使用，确保结果与正确的section一一对应
     * 
     * @return section keys数组，如果不可用返回null
     */
    public static long[] getLastFrameSectionKeys() {
        return hasValidVisibility ? lastFrameSectionKeys : null;
    }

    /**
     * 获取上一帧剔除结果的section key数量
     */
    public static int getLastFrameSectionKeyCount() {
        return hasValidVisibility ? lastFrameSectionKeyCount : 0;
    }

    /**
     * 获取上一帧的对象数量
     */
    public static int getLastFrameObjectCount() {
        return lastFrameObjectCount;
    }

    public static boolean isActive() {
        return active && initialized;
    }

    public static int getTotalCulled() {
        return totalCulled.get();
    }

    public static int getTotalVisible() {
        return totalVisible.get();
    }

    public static int getFrameCulled() {
        return frameCulled.get();
    }

    public static int getFrameVisible() {
        return frameVisible.get();
    }

    public static double getCullRate() {
        int total = totalVisible.get() + totalCulled.get();
        return total > 0 ? (double) totalCulled.get() / total * 100.0 : 0.0;
    }

    public static long getTotalComputeTimeMs() {
        return totalComputeTimeNs / 1_000_000;
    }

    public static int getDispatchCount() {
        return dispatchCount;
    }

    public static void onFrameStart() {
        frameCulled.set(0);
        frameVisible.set(0);
    }

    // ========================================================================
    // Hi-Z 遮挡剔除控制
    // ========================================================================

    /**
     * 启用或禁用 Hi-Z 遮挡剔除。
     * 默认禁用，需要显式启用。
     *
     * @param enable true 启用，false 禁用
     */
    public static void enableHiZ(boolean enable) {
        if (enable != hizEnabled) {
            hizEnabled = enable;
            if (enable) {
                UHDFast.LOGGER.info("XeFrustumCuller: Hi-Z occlusion culling enabled");
            } else {
                UHDFast.LOGGER.info("XeFrustumCuller: Hi-Z occlusion culling disabled");
            }
        }
    }

    /**
     * @return Hi-Z 遮挡剔除是否已启用
     */
    public static boolean isHiZEnabled() {
        return hizEnabled;
    }

    /**
     * 设置深度纹理 ID（用于 Hi-Z 参考）。
     * Hi-Z 缓冲由 XeHiZBuffer 管理，此 ID 仅用于跟踪。
     *
     * @param texId 深度纹理 ID
     */
    public static void setDepthTexture(int texId) {
        depthTextureId = texId;
    }

    /**
     * @return 当前深度纹理 ID
     */
    public static int getDepthTextureId() {
        return depthTextureId;
    }

    /**
     * 设置视图投影矩阵（用于 Hi-Z 测试）。
     * 矩阵会被缓存，用于后续的 Hi-Z 遮挡剔除计算。
     *
     * @param viewProj 视图投影矩阵 (16 floats, column-major)
     */
    public static void setViewProjMatrix(float[] viewProj) {
        if (viewProj != null && viewProj.length >= 16) {
            System.arraycopy(viewProj, 0, lastViewProj, 0, 16);
        }
    }

    /**
     * @return 缓存的视图投影矩阵
     */
    public static float[] getLastViewProj() {
        return lastViewProj;
    }

    /**
     * @return 当前帧被 Hi-Z 剔除的物体数量
     */
    public static int getHizCulledCount() {
        return hizCulledCount;
    }

    /**
     * @return 累计被 Hi-Z 剔除的物体数量
     */
    public static int getTotalHizCulled() {
        return totalHizCulled;
    }

    /**
     * @return Hi-Z 遮挡剔除率（百分比），0 表示无数据
     */
    public static double getHizCullRate() {
        int total = totalVisible.get() + totalCulled.get();
        if (total <= 0 || totalHizCulled <= 0) {
            return 0.0;
        }
        return (double) totalHizCulled / total * 100.0;
    }

    public static void resetStats() {
        totalCulled.set(0);
        totalVisible.set(0);
        frameCulled.set(0);
        frameVisible.set(0);
        totalComputeTimeNs = 0;
        dispatchCount = 0;
        lastFrameVisibility = null;
        lastFrameObjectCount = 0;
        pendingResult = false;
        if (pendingFenceSync != 0) {
            GL32.glDeleteSync(pendingFenceSync);
            pendingFenceSync = 0;
        }
        // 重置 Hi-Z 统计
        hizCulledCount = 0;
        totalHizCulled = 0;
        hizEnabled = false;
        depthTextureId = 0;
    }

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
        if (persistentBoundsBuffer != null) {
            PersistentBufferPool.release(persistentBoundsBuffer);
            persistentBoundsBuffer = null;
        }
        if (persistentUniformBuffer != null) {
            PersistentBufferPool.release(persistentUniformBuffer);
            persistentUniformBuffer = null;
        }
        if (persistentVisibilityBuffer != null) {
            PersistentBufferPool.release(persistentVisibilityBuffer);
            persistentVisibilityBuffer = null;
        }
        if (hizStatsBuffer != null) {
            PersistentBufferPool.release(hizStatsBuffer);
            hizStatsBuffer = null;
        }

        initialized = false;
        active = false;
        lastFrameVisibility = null;
        lastFrameObjectCount = 0;
        hizCulledCount = 0;
        totalHizCulled = 0;
        hizEnabled = false;
        depthTextureId = 0;
    }
}
