package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * GPU层次化Z缓冲（Hierarchical Z-Buffer）构建与遮挡查询引擎。
 *
 * 纯GPU端组件，从深度缓冲构建多级Hi-Z缓冲，并完全在GPU上执行遮挡查询，
 * 无需CPU回读深度数据。
 *
 * 实现原理：
 * - 使用compute shader从深度纹理构建多级Hi-Z缓冲
 * - 对每个物体，将其包围球投影到屏幕空间，计算屏幕覆盖区域
 * - 根据覆盖区域选择合适的Hi-Z mip层级进行深度比较
 * - 物体深度 > Hi-Z深度（即物体在已渲染物体后面）→ 遮挡
 *
 * 基于Intel Xe架构优化，使用OpenGL 4.3 compute shader + persistent buffer
 */
public class XeHiZBuffer {

    // ========================================================================
    // 常量
    // ========================================================================

    /** 最大mip层级数（8x8 → 1x1 约简） */
    public static final int MAX_MIP_LEVELS = 8;

    // ========================================================================
    // Hi-Z构建计算着色器
    // ========================================================================

    private static final String HIZ_BUILD_COMPUTE_SHADER = """
            #version 430 core

            layout(local_size_x = 8, local_size_y = 8) in;

            // 输入：深度纹理
            layout(r32f, binding = 0) uniform readonly image2D depthTexture;

            // 输出：Hi-Z缓冲（每个vec4存储4个深度值，对应一个2x2 tile的4个象限）
            layout(std430, binding = 0) buffer HiZBuffer {
                vec4 levels[];
            };

            // 参数
            uniform HiZParams {
                int srcWidth;
                int srcHeight;
                int mipCount;
                float padding;
            };

            // 共享内存：缓存当前工作组的8x8 tile
            shared float tile[64];

            // 计算给定level的tile尺寸（以2x2块为单位）
            int getLevelTileWidth(int level) {
                int w = (srcWidth + (1 << level) - 1) >> level;
                return (w + 1) >> 1;
            }

            // 计算给定level的起始vec4索引
            int getLevelOffset(int level) {
                int offset = 0;
                for (int i = 0; i < level && i < mipCount; i++) {
                    int w = (srcWidth + (1 << i) - 1) >> i;
                    int h = (srcHeight + (1 << i) - 1) >> i;
                    int tw = (w + 1) >> 1;
                    int th = (h + 1) >> 1;
                    int totalFloats = tw * th;
                    int totalVec4s = (totalFloats + 3) / 4;
                    offset += totalVec4s;
                }
                return offset;
            }

            void main() {
                ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
                int tid = int(gl_LocalInvocationIndex);

                // 加载深度值到共享内存
                float depth = 0.0;
                if (coord.x < srcWidth && coord.y < srcHeight) {
                    depth = imageLoad(depthTexture, coord, 0).r;
                }
                tile[tid] = depth;
                barrier();

                // 构建level 0：2x2约简，8x8 tile → 4x4个2x2块
                if (tid < 16) {
                    int bx = (tid & 3) << 1;
                    int by = (tid >> 2) << 1;
                    float m00 = tile[by * 8 + bx];
                    float m01 = tile[by * 8 + bx + 1];
                    float m10 = tile[(by + 1) * 8 + bx];
                    float m11 = tile[(by + 1) * 8 + bx + 1];
                    float maxVal = max(max(m00, m01), max(m10, m11));

                    // 写入level 0
                    int tileX = int(gl_WorkGroupID.x) * 4 + (tid & 3);
                    int tileY = int(gl_WorkGroupID.y) * 4 + (tid >> 2);
                    int level0W = (srcWidth + 1) >> 1;
                    int idx = tileY * level0W + tileX;
                    int vecOffset = getLevelOffset(0);
                    int vecIdx = vecOffset + (idx / 4);
                    int compIdx = idx % 4;
                    vec4 v = levels[vecIdx];
                    v[compIdx] = maxVal;
                    levels[vecIdx] = v;
                }
                barrier();

                // 构建level 1：在共享内存中继续约简 4x4 → 2x2
                // 重新使用共享内存：将level 0的16个值存入tile前16个位置
                // 但不需要，直接用tile[0..15]作为level 0的结果
                // level 1: 4个2x2块（从level 0的4x4网格中取max）
                if (tid < 4) {
                    int bx = (tid & 1) << 1;
                    int by = (tid >> 1) << 1;
                    // 从共享内存中读取level 0的值（之前已计算）
                    float m00 = tile[by * 4 + bx];       // 使用tile[0..15]作为level 0的4x4网格
                    float m01 = tile[by * 4 + bx + 1];
                    float m10 = tile[(by + 1) * 4 + bx];
                    float m11 = tile[(by + 1) * 4 + bx + 1];
                    float maxVal = max(max(m00, m01), max(m10, m11));

                    // 写入level 1
                    int tileX = int(gl_WorkGroupID.x) * 2 + (tid & 1);
                    int tileY = int(gl_WorkGroupID.y) * 2 + (tid >> 1);
                    int level1W = ((srcWidth + 1) >> 1) + 1 >> 1;
                    if (level1W < 1) level1W = 1;
                    int idx = tileY * level1W + tileX;
                    int vecOffset = getLevelOffset(1);
                    int vecIdx = vecOffset + (idx / 4);
                    int compIdx = idx % 4;
                    vec4 v = levels[vecIdx];
                    v[compIdx] = maxVal;
                    levels[vecIdx] = v;
                }
                barrier();

                // 构建level 2：2x2 → 1x1
                if (tid < 1) {
                    // 从4个level 1的值中取max
                    float m00 = tile[0];
                    float m01 = tile[1];
                    float m10 = tile[2];
                    float m11 = tile[3];
                    float maxVal = max(max(m00, m01), max(m10, m11));

                    // 写入level 2
                    int tileX = int(gl_WorkGroupID.x);
                    int tileY = int(gl_WorkGroupID.y);
                    int level2W = (((srcWidth + 1) >> 1) + 1 >> 1) + 1 >> 1;
                    if (level2W < 1) level2W = 1;
                    int idx = tileY * level2W + tileX;
                    int vecOffset = getLevelOffset(2);
                    int vecIdx = vecOffset + (idx / 4);
                    int compIdx = idx % 4;
                    vec4 v = levels[vecIdx];
                    v[compIdx] = maxVal;
                    levels[vecIdx] = v;
                }
            }
            """;

    // ========================================================================
    // 遮挡查询计算着色器
    // ========================================================================

    private static final String OCCLUSION_TEST_COMPUTE_SHADER = """
            #version 430 core

            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

            // 输入：对象包围盒数据 (x, y, z, radius) per object
            layout(std430, binding = 0) buffer ObjectBounds {
                vec4 bounds[];  // xyz = center, w = radius
            };

            // 输入：Hi-Z缓冲
            layout(std430, binding = 1) buffer HiZBuffer {
                vec4 levels[];
            };

            // 输出：可见性标志
            layout(std430, binding = 2) buffer VisibilityBuffer {
                uint visible[];
            };

            // 参数
            uniform OcclusionParams {
                mat4 viewProj;
                vec4 viewport;  // x = width, y = height, z = 0, w = 0
                int mipCount;
                int objectCount;
                int padding[2];
            };

            // 计算给定level的起始vec4索引（与HiZ构建着色器一致）
            int getLevelOffset(int level, int srcWidth, int srcHeight) {
                int offset = 0;
                for (int i = 0; i < level && i < mipCount; i++) {
                    int w = (srcWidth + (1 << i) - 1) >> i;
                    int h = (srcHeight + (1 << i) - 1) >> i;
                    int tw = (w + 1) >> 1;
                    int th = (h + 1) >> 1;
                    int totalFloats = tw * th;
                    int totalVec4s = (totalFloats + 3) / 4;
                    offset += totalVec4s;
                }
                return offset;
            }

            // 从Hi-Z缓冲的指定level采样
            float sampleHiZ(int level, int x, int y, int srcWidth, int srcHeight) {
                int w = (srcWidth + (1 << level) - 1) >> level;
                int h = (srcHeight + (1 << level) - 1) >> level;
                int tw = (w + 1) >> 1;
                int th = (h + 1) >> 1;

                // 边界钳制
                x = clamp(x, 0, tw - 1);
                y = clamp(y, 0, th - 1);

                int idx = y * tw + x;
                int vecOffset = getLevelOffset(level, srcWidth, srcHeight);
                int vecIdx = vecOffset + (idx / 4);
                int compIdx = idx % 4;
                return levels[vecIdx][compIdx];
            }

            void main() {
                uint idx = gl_GlobalInvocationID.x;

                if (int(idx) >= objectCount) return;

                vec4 bound = bounds[idx];
                vec3 center = bound.xyz;
                float radius = bound.w;

                // 将包围球中心投影到裁剪空间
                vec4 clipPos = viewProj * vec4(center, 1.0);

                // 如果在相机后面，标记为可见（不遮挡）
                if (clipPos.w <= 0.0) {
                    visible[idx] = 1u;
                    return;
                }

                // NDC坐标
                vec3 ndc = clipPos.xyz / clipPos.w;

                // 屏幕空间坐标
                vec2 screenPos = vec2(
                    (ndc.x * 0.5 + 0.5) * viewport.x,
                    (ndc.y * 0.5 + 0.5) * viewport.y
                );

                // 计算屏幕空间包围盒
                // 将半径投影到屏幕空间
                // 近似：在近平面处，1单位半径对应的屏幕像素数
                float projScale = viewport.x / (2.0 * clipPos.w);
                float screenRadius = radius * abs(projScale);

                // 如果屏幕半径太小（物体太小），标记为可见
                if (screenRadius < 1.0) {
                    visible[idx] = 1u;
                    return;
                }

                // 屏幕空间包围盒（以2x2块为单位）
                int minX = int(max(0.0, screenPos.x - screenRadius));
                int minY = int(max(0.0, screenPos.y - screenRadius));
                int maxX = int(min(viewport.x - 1.0, screenPos.x + screenRadius));
                int maxY = int(min(viewport.y - 1.0, screenPos.y + screenRadius));

                int screenW = int(viewport.x);
                int screenH = int(viewport.y);

                // 确定合适的Hi-Z mip层级
                int coverageX = maxX - minX + 1;
                int coverageY = maxY - minY + 1;
                int maxCoverage = max(coverageX, coverageY);

                // 根据覆盖大小选择mip层级：使2x2块数量 ≈ 1
                int mipLevel = 0;
                int mipSize = maxCoverage;
                while (mipSize > 2 && mipLevel < mipCount - 1) {
                    mipSize = (mipSize + 1) >> 1;
                    mipLevel++;
                }

                // 将屏幕坐标转换为Hi-Z坐标（2x2块为单位）
                int hizX = (minX + maxX) / 2;
                int hizY = (minY + maxY) / 2;

                // 在所选mip层级采样Hi-Z深度
                float hizDepth = sampleHiZ(mipLevel, hizX, hizY, screenW, screenH);

                // 计算物体最近深度（中心 - 半径，在相机空间中）
                // 需要将clipPos.w（即视图空间Z的负值）转换
                // 使用NDC Z与Hi-Z深度比较
                float objectDepth = ndc.z;

                // 物体深度 > Hi-Z深度 → 物体在已渲染表面后面 → 遮挡
                // 注意：OpenGL中NDC Z范围[-1, 1]，越近值越小
                // Hi-Z存储的是最大深度（最远），所以如果物体最近点 > Hi-Z，则被遮挡
                // 使用保守估计：物体的最近深度（center - radius）在屏幕空间
                // 简化为：使用物体中心深度 - 半径的深度影响
                float closestDepth = ndc.z - (screenRadius / viewport.y) * 2.0;

                if (closestDepth > hizDepth) {
                    visible[idx] = 0u; // 遮挡
                } else {
                    visible[idx] = 1u; // 可见
                }
            }
            """;

    // ========================================================================
    // 字段
    // ========================================================================

    private static int hizBuildProgram = 0;
    private static int occlusionTestProgram = 0;

    private static boolean initialized = false;
    private static boolean active = false;

    // persistent缓冲区
    private static PersistentBufferPool.PersistentBuffer depthInputBuffer;
    private static PersistentBufferPool.PersistentBuffer hizLevelsBuffer;
    private static PersistentBufferPool.PersistentBuffer objectBoundsBuffer;
    private static PersistentBufferPool.PersistentBuffer occlusionParamsBuffer;
    private static PersistentBufferPool.PersistentBuffer visibilityBuffer;

    // 当前深度缓冲尺寸
    private static int width = 0;
    private static int height = 0;

    // 当前帧索引（双缓冲）
    private static int currentFrame = 0;

    // 深度缓冲是否就绪
    private static boolean depthAvailable = false;

    // ========================================================================
    // 初始化与销毁
    // ========================================================================

    /**
     * 初始化Hi-Z缓冲引擎。
     * 编译两个compute shader，创建持久映射缓冲区。
     * 仅在Intel Xe架构上启用。
     */
    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Xe Hi-Z Buffer disabled - not Xe architecture");
            return;
        }

        if (!IntelXeDetector.supportsComputeShaders()) {
            UHDFast.LOGGER.info("Xe Hi-Z Buffer disabled - compute shaders not supported");
            return;
        }

        try {
            compileShaders();
            createBuffers();

            initialized = true;
            active = true;

            UHDFast.LOGGER.info("Xe GPU Hi-Z Buffer initialized");
            UHDFast.LOGGER.info("  Max mip levels: {}", MAX_MIP_LEVELS);
            UHDFast.LOGGER.info("  EU Count: {}", IntelXeDetector.getEUCount());
            UHDFast.LOGGER.info("  Compute Shader: OpenGL 4.3");

        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to initialize Xe Hi-Z Buffer: {}", e.getMessage());
            active = false;
        }
    }

    /**
     * 编译两个compute shader程序
     */
    private static void compileShaders() {
        // 编译Hi-Z构建shader
        int hizShader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(hizShader, HIZ_BUILD_COMPUTE_SHADER);
        GL43.glCompileShader(hizShader);

        if (GL43.glGetShaderi(hizShader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(hizShader);
            GL43.glDeleteShader(hizShader);
            throw new RuntimeException("Hi-Z build compute shader compilation failed: " + error);
        }

        hizBuildProgram = GL43.glCreateProgram();
        GL43.glAttachShader(hizBuildProgram, hizShader);
        GL43.glLinkProgram(hizBuildProgram);

        if (GL43.glGetProgrami(hizBuildProgram, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(hizBuildProgram);
            GL43.glDeleteProgram(hizBuildProgram);
            hizBuildProgram = 0;
            GL43.glDeleteShader(hizShader);
            throw new RuntimeException("Hi-Z build program linking failed: " + error);
        }

        GL43.glDeleteShader(hizShader);

        // 编译遮挡查询shader
        int occlusionShader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(occlusionShader, OCCLUSION_TEST_COMPUTE_SHADER);
        GL43.glCompileShader(occlusionShader);

        if (GL43.glGetShaderi(occlusionShader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(occlusionShader);
            GL43.glDeleteShader(occlusionShader);
            GL43.glDeleteProgram(hizBuildProgram);
            hizBuildProgram = 0;
            throw new RuntimeException("Occlusion test compute shader compilation failed: " + error);
        }

        occlusionTestProgram = GL43.glCreateProgram();
        GL43.glAttachShader(occlusionTestProgram, occlusionShader);
        GL43.glLinkProgram(occlusionTestProgram);

        if (GL43.glGetProgrami(occlusionTestProgram, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(occlusionTestProgram);
            GL43.glDeleteProgram(occlusionTestProgram);
            occlusionTestProgram = 0;
            GL43.glDeleteShader(occlusionShader);
            GL43.glDeleteProgram(hizBuildProgram);
            hizBuildProgram = 0;
            throw new RuntimeException("Occlusion test program linking failed: " + error);
        }

        GL43.glDeleteShader(occlusionShader);
    }

    /**
     * 创建持久映射缓冲区
     */
    private static void createBuffers() {
        // 输入深度缓冲（上传）：CPU写入影像，GPU读取
        // 固定大小，最大支持4K纹理
        depthInputBuffer = PersistentBufferPool.acquire(
                4096L * 4096 * 4, // 4K x 4K x float
                PersistentBufferPool.BufferUsage.UPLOAD);

        // Hi-Z层级缓冲（回读）：GPU写入，CPU读取
        // 最大空间：所有层级的总和，每个层级约 1/4 previous
        // 初始分配 4M 字节
        hizLevelsBuffer = PersistentBufferPool.acquire(
                4L * 1024 * 1024,
                PersistentBufferPool.BufferUsage.DOWNLOAD);

        // 物体包围盒缓冲（上传）
        objectBoundsBuffer = PersistentBufferPool.acquire(
                4L * 1024 * 1024, // 约256K个物体
                PersistentBufferPool.BufferUsage.UPLOAD);

        // 遮挡参数缓冲（上传）
        occlusionParamsBuffer = PersistentBufferPool.acquire(
                1024L,
                PersistentBufferPool.BufferUsage.UPLOAD);

        // 可见性结果缓冲（回读）
        visibilityBuffer = PersistentBufferPool.acquire(
                4L * 1024 * 1024, // 约1M个物体
                PersistentBufferPool.BufferUsage.DOWNLOAD);
    }

    // ========================================================================
    // 核心API
    // ========================================================================

    /**
     * 从深度纹理构建Hi-Z缓冲。
     * 调度Hi-Z构建compute shader，读取深度纹理并写入多级Hi-Z缓冲。
     *
     * @param depthTexture 深度纹理ID（GL_TEXTURE_2D, r32f格式）
     * @param srcWidth     深度纹理宽度
     * @param srcHeight    深度纹理高度
     */
    public static void buildFromDepthBuffer(int depthTexture, int srcWidth, int srcHeight) {
        if (!active || !initialized) {
            return;
        }

        try {
            width = srcWidth;
            height = srcHeight;

            // 计算mip层级数
            int maxDim = Math.max(srcWidth, srcHeight);
            int mipCount = 1;
            while (maxDim > 1 && mipCount < MAX_MIP_LEVELS) {
                maxDim >>= 1;
                mipCount++;
            }

            GL20.glUseProgram(hizBuildProgram);

            // 绑定深度纹理
            GL43.glBindImageTexture(0, depthTexture, 0, false, 0,
                    GL30.GL_READ_ONLY, GL30.GL_R32F);

            // 绑定Hi-Z输出缓冲
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0,
                    hizLevelsBuffer.glBufferId);

            // 设置uniform参数
            int paramsLoc = GL43.glGetUniformLocation(hizBuildProgram, "HiZParams");
            if (paramsLoc >= 0) {
                GL43.glUniform4i(paramsLoc, srcWidth, srcHeight, mipCount, 0);
            }

            // 调度compute shader
            int dispatchX = (srcWidth + 7) / 8;
            int dispatchY = (srcHeight + 7) / 8;
            GL43.glDispatchCompute(dispatchX, dispatchY, 1);

            // 内存屏障：确保Hi-Z缓冲SSBO写入完成，供后续遮挡查询使用
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            // 解绑
            GL43.glBindImageTexture(0, 0, 0, false, 0, GL30.GL_READ_ONLY, GL30.GL_R32F);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GL20.glUseProgram(0);

            depthAvailable = true;

        } catch (Exception e) {
            UHDFast.LOGGER.warn("Hi-Z build failed: {}", e.getMessage());
            depthAvailable = false;
        }
    }

    /**
     * 执行遮挡查询测试。
     * 将物体包围盒投影到屏幕空间，与Hi-Z缓冲比较，判断遮挡关系。
     *
     * @param objectBounds    物体包围盒数据 (x, y, z, radius) per object
     * @param objectCount     物体数量
     * @param viewProj        视图投影矩阵 (16 floats, column-major)
     * @param viewportWidth   视口宽度
     * @param viewportHeight  视口高度
     * @param visibilityOut   输出可见性数组（长度为objectCount）
     */
    @SuppressWarnings("all")
    public static void testBounds(float[] objectBounds, int objectCount,
            float[] viewProj, float viewportWidth, float viewportHeight,
            boolean[] visibilityOut) {
        if (!active || !initialized || !depthAvailable || objectCount <= 0) {
            // 不可用或没有数据时，全部标记为可见
            if (visibilityOut != null) {
                for (int i = 0; i < visibilityOut.length; i++) {
                    visibilityOut[i] = true;
                }
            }
            return;
        }

        try {
            int uploadCount = Math.min(objectCount, (int) (objectBoundsBuffer.sizeBytes / 16));

            // 上传物体包围盒数据
            objectBoundsBuffer.writeFloats(objectBounds, 0, uploadCount * 4);

            // 计算mip层级数
            int maxDim = Math.max(width, height);
            int mipCount = 1;
            while (maxDim > 1 && mipCount < MAX_MIP_LEVELS) {
                maxDim >>= 1;
                mipCount++;
            }

            GL20.glUseProgram(occlusionTestProgram);

            // 绑定SSBO
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0,
                    objectBoundsBuffer.glBufferId);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1,
                    hizLevelsBuffer.glBufferId);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2,
                    visibilityBuffer.glBufferId);

            // 上传uniform参数
            int paramsLoc = GL43.glGetUniformLocation(occlusionTestProgram, "OcclusionParams");
            if (paramsLoc >= 0) {
                // 使用持久缓冲的floatView上传矩阵和参数
                occlusionParamsBuffer.getFloatView().clear();
                FloatBuffer uniformView = occlusionParamsBuffer.getFloatView();

                // viewProj矩阵 (16 floats)
                uniformView.put(viewProj, 0, 16);

                // viewport (4 floats)
                uniformView.put(viewportWidth);
                uniformView.put(viewportHeight);
                uniformView.put(0.0f);
                uniformView.put(0.0f);

                // mipCount, objectCount, padding
                uniformView.put((float) mipCount);
                uniformView.put((float) uploadCount);
                uniformView.put(0.0f);
                uniformView.put(0.0f);

                uniformView.flip();

                // 通过UBO传递uniform
                GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER,
                        occlusionParamsBuffer.glBufferId);
                GL43.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0,
                        occlusionParamsBuffer.glBufferId);
                GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
            }

            // 调度compute shader
            int dispatchX = (uploadCount + 63) / 64;
            GL43.glDispatchCompute(dispatchX, 1, 1);

            // 内存屏障：确保可见性写入完成
            GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            // 插入fence同步，等待GPU完成
            long fenceSync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fenceSync != 0) {
                // 等待GPU完成（最多100ms）
                int waitStatus = GL32.glClientWaitSync(fenceSync,
                        GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
                GL32.glDeleteSync(fenceSync);

                if (waitStatus == GL32.GL_ALREADY_SIGNALED
                        || waitStatus == GL32.GL_CONDITION_SATISFIED) {
                    // GPU已完成，读取结果
                    IntBuffer visibilityView = visibilityBuffer.getIntView();
                    int readCount = Math.min(uploadCount, visibilityOut.length);
                    for (int i = 0; i < readCount; i++) {
                        visibilityOut[i] = visibilityView.get(i) != 0;
                    }
                    // 超出部分标记为可见
                    for (int i = readCount; i < visibilityOut.length; i++) {
                        visibilityOut[i] = true;
                    }
                } else {
                    // GPU超时或失败，全部标记为可见
                    for (int i = 0; i < visibilityOut.length; i++) {
                        visibilityOut[i] = true;
                    }
                    UHDFast.LOGGER.debug("Occlusion query sync wait status: {}",
                            waitStatus);
                }
            } else {
                // fence创建失败，全部标记为可见
                for (int i = 0; i < visibilityOut.length; i++) {
                    visibilityOut[i] = true;
                }
            }

            // 解绑
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, 0);
            GL43.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, 0);
            GL20.glUseProgram(0);

            currentFrame = (currentFrame + 1) % 2;

        } catch (Exception e) {
            UHDFast.LOGGER.warn("Occlusion test failed: {}", e.getMessage());
            // 出错时全部标记为可见
            for (int i = 0; i < visibilityOut.length; i++) {
                visibilityOut[i] = true;
            }
        }
    }

    // ========================================================================
    // 状态查询
    // ========================================================================

    /**
     * @return 深度缓冲是否已就绪（Hi-Z已构建）
     */
    public static boolean isDepthAvailable() {
        return depthAvailable;
    }

    /**
     * @return 当前深度缓冲宽度
     */
    public static int getWidth() {
        return width;
    }

    /**
     * @return 当前深度缓冲高度
     */
    public static int getHeight() {
        return height;
    }

    /**
     * @return 当前帧索引
     */
    public static int getCurrentFrame() {
        return currentFrame;
    }

    /**
     * @return Hi-Z 层级缓冲的 GL 缓冲区 ID（用于绑定为 SSBO）
     */
    public static int getHiZBufferId() {
        return hizLevelsBuffer != null ? hizLevelsBuffer.glBufferId : 0;
    }

    /**
     * @return 当前 Hi-Z 缓冲的 mip 层级数量
     */
    public static int getHizMipCount() {
        if (!depthAvailable || width <= 0 || height <= 0) {
            return 0;
        }
        int maxDim = Math.max(width, height);
        int mipCount = 1;
        while (maxDim > 1 && mipCount < MAX_MIP_LEVELS) {
            maxDim >>= 1;
            mipCount++;
        }
        return mipCount;
    }

    /**
     * @return 当前视口宽度
     */
    public static int getViewportWidth() {
        return width;
    }

    /**
     * @return 当前视口高度
     */
    public static int getViewportHeight() {
        return height;
    }

    public static boolean isActive() {
        return active && initialized;
    }

    // ========================================================================
    // 重置与清理
    // ========================================================================

    /**
     * 重置状态
     */
    public static void resetStats() {
        depthAvailable = false;
        width = 0;
        height = 0;
        currentFrame = 0;
    }

    /**
     * 销毁所有GPU资源
     */
    public static void destroy() {
        if (hizBuildProgram != 0) {
            GL43.glDeleteProgram(hizBuildProgram);
            hizBuildProgram = 0;
        }
        if (occlusionTestProgram != 0) {
            GL43.glDeleteProgram(occlusionTestProgram);
            occlusionTestProgram = 0;
        }

        // 释放持久映射缓冲区
        if (depthInputBuffer != null) {
            PersistentBufferPool.release(depthInputBuffer);
            depthInputBuffer = null;
        }
        if (hizLevelsBuffer != null) {
            PersistentBufferPool.release(hizLevelsBuffer);
            hizLevelsBuffer = null;
        }
        if (objectBoundsBuffer != null) {
            PersistentBufferPool.release(objectBoundsBuffer);
            objectBoundsBuffer = null;
        }
        if (occlusionParamsBuffer != null) {
            PersistentBufferPool.release(occlusionParamsBuffer);
            occlusionParamsBuffer = null;
        }
        if (visibilityBuffer != null) {
            PersistentBufferPool.release(visibilityBuffer);
            visibilityBuffer = null;
        }

        initialized = false;
        active = false;
        depthAvailable = false;
        width = 0;
        height = 0;
        currentFrame = 0;
    }
}