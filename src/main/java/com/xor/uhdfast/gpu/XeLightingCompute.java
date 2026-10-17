package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import net.minecraft.client.Camera;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU光照计算着色器
 * 将光照计算从CPU转移到GPU，利用并行计算加速
 */
public class XeLightingCompute {

    private static final String LIGHTING_COMPUTE_SHADER = """
            #version 430 core

            layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

            // 输入：场景深度缓冲
            layout(binding = 0) uniform sampler2D depthTexture;
            // 输入：法线缓冲
            layout(binding = 1) uniform sampler2D normalTexture;
            // 输入：反照率缓冲
            layout(binding = 2) uniform sampler2D albedoTexture;

            // 输出：光照结果
            layout(rgba16f, binding = 3) writeonly uniform image2D lightingOutput;

            // 光源数据
            layout(std430, binding = 4) buffer LightData {
                vec4 lightPositions[];  // xyz = position, w = type (0=point, 1=spot, 2=directional)
            };

            layout(std430, binding = 5) buffer LightColors {
                vec4 lightColors[];  // rgb = color, a = intensity
            };

            // 相机和光照参数
            layout(std140, binding = 6) uniform LightingParams {
                mat4 viewProjection;
                mat4 inverseViewProjection;
                vec4 cameraPos;
                vec4 ambientColor;
                vec4 lightDir;  // directional light direction
                int pointLightCount;
                int spotLightCount;
                int width;
                int height;
            };

            // 从深度缓冲重建世界位置
            vec3 reconstructWorldPos(vec2 uv, float depth) {
                vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec4 worldPos = inverseViewProjection * ndc;
                return worldPos.xyz / worldPos.w;
            }

            // 点光源衰减
            float attenuate(float distance, float radius) {
                float factor = distance / radius;
                float smoothFactor = clamp(1.0 - factor * factor, 0.0, 1.0);
                return smoothFactor * smoothFactor;
            }

            void main() {
                ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);

                if (pixel.x >= width || pixel.y >= height) return;

                vec2 uv = vec2(pixel) / vec2(width, height);

                float depth = texture(depthTexture, uv).r;

                // 跳过天空盒
                if (depth >= 1.0) {
                    imageStore(lightingOutput, pixel, vec4(0.0));
                    return;
                }

                vec3 worldPos = reconstructWorldPos(uv, depth);
                vec3 normal = texture(normalTexture, uv).rgb * 2.0 - 1.0;
                vec3 albedo = texture(albedoTexture, uv).rgb;

                // 环境光
                vec3 result = ambientColor.rgb * ambientColor.a * albedo;

                // 方向光 (太阳光)
                vec3 lightDirection = normalize(-lightDir.xyz);
                float NdotL = max(dot(normal, lightDirection), 0.0);
                vec3 dirLightColor = lightDir.w > 0.0 ? lightDir.xyz : vec3(1.0);
                result += dirLightColor * NdotL * albedo;

                // 点光源
                for (int i = 0; i < pointLightCount; i++) {
                    vec3 lightPos = lightPositions[i].xyz;
                    vec4 lightColorData = lightColors[i];

                    vec3 lightDir2 = lightPos - worldPos;
                    float distance = length(lightDir2);
                    lightDir2 = normalize(lightDir2);

                    float NdotL2 = max(dot(normal, lightDir2), 0.0);
                    float attenuation = attenuate(distance, 16.0);

                    vec3 diffuse = lightColorData.rgb * lightColorData.a * NdotL2 * attenuation;
                    result += diffuse * albedo;
                }

                imageStore(lightingOutput, pixel, vec4(result, 1.0));
            }
            """;

    private static int computeProgram = 0;
    private static int lightPositionsSSBO = 0;
    private static int lightColorsSSBO = 0;
    private static int paramBuffer = 0;
    private static int outputTexture = 0;

    private static boolean initialized = false;
    private static boolean active = false;

    private static final int MAX_LIGHTS = 256;

    private static final AtomicInteger computeDispatches = new AtomicInteger(0);
    private static long totalComputeTimeNs = 0;
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    // 光照结果跟踪
    private static boolean lastComputeSuccess = false;
    private static int consecutiveFailures = 0;
    private static final int MAX_FAILURES_BEFORE_FALLBACK = 3;

    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Xe Lighting Compute disabled - not Xe architecture");
            return;
        }

        if (!IntelXeDetector.supportsComputeShaders()) {
            UHDFast.LOGGER.info("Xe Lighting Compute disabled - compute shaders not supported");
            return;
        }

        try {
            compileComputeShader();
            createBuffers();

            initialized = true;
            active = true;

            UHDFast.LOGGER.info("Xe GPU Lighting Compute initialized");
            UHDFast.LOGGER.info("  Max lights: {}", MAX_LIGHTS);
            UHDFast.LOGGER.info("  Compute Shader: OpenGL 4.3");

        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to initialize Xe Lighting Compute: {}", e.getMessage());
            active = false;
        }
    }

    private static void compileComputeShader() {
        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, LIGHTING_COMPUTE_SHADER);
        GL43.glCompileShader(shader);

        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetShaderInfoLog(shader);
            GL43.glDeleteShader(shader);
            throw new RuntimeException("Lighting compute shader compilation failed: " + error);
        }

        computeProgram = GL43.glCreateProgram();
        GL43.glAttachShader(computeProgram, shader);
        GL43.glLinkProgram(computeProgram);

        if (GL43.glGetProgrami(computeProgram, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String error = GL43.glGetProgramInfoLog(computeProgram);
            GL43.glDeleteProgram(computeProgram);
            GL43.glDeleteShader(shader);
            throw new RuntimeException("Lighting compute program linking failed: " + error);
        }

        GL43.glDeleteShader(shader);
    }

    private static void createBuffers() {
        int[] buffers = new int[3];
        GL15.glGenBuffers(buffers);

        lightPositionsSSBO = buffers[0];
        lightColorsSSBO = buffers[1];
        paramBuffer = buffers[2];

        // 光源位置缓冲
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, lightPositionsSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, MAX_LIGHTS * 16, GL15.GL_DYNAMIC_DRAW);

        // 光源颜色缓冲
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, lightColorsSSBO);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, MAX_LIGHTS * 16, GL15.GL_DYNAMIC_DRAW);

        // 参数缓冲
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, paramBuffer);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, 256, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    /**
     * 执行GPU光照计算
     */
    public static void computeLighting(int depthTexture, int normalTexture, int albedoTexture,
            int width, int height, float[] lightPositions, float[] lightColors,
            int pointLightCount, float[] viewProjection, float[] inverseViewProjection,
            float[] cameraPos, float[] ambientColor, float[] lightDir) {
        if (!active || !initialized) {
            return;
        }

        long startTime = System.nanoTime();

        try {
            // 开始 GPU 命令批处理
            GPUCommandBatcher.beginBatch();

            // 确保输出纹理大小正确
            if (width != lastWidth || height != lastHeight) {
                if (outputTexture != 0) {
                    GL11.glDeleteTextures(outputTexture);
                }
                outputTexture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputTexture);
                GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA16F, width, height);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                lastWidth = width;
                lastHeight = height;
            }

            // 上传光源数据 - 使用缓存 FloatBuffer 避免每帧分配
            if (lightPositions != null && lightPositions.length > 0) {
                int floatCount = Math.min(lightPositions.length, MAX_LIGHTS * 4);
                ensureLightBufferCapacity(floatCount);
                cachedLightPosBuffer.clear();
                cachedLightPosBuffer.put(lightPositions, 0, floatCount);
                cachedLightPosBuffer.flip();
                GPUCommandBatcher.bufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, lightPositionsSSBO, 0, cachedLightPosBuffer);
            }

            if (lightColors != null && lightColors.length > 0) {
                int floatCount = Math.min(lightColors.length, MAX_LIGHTS * 4);
                ensureLightBufferCapacity(floatCount);
                cachedLightColorBuffer.clear();
                cachedLightColorBuffer.put(lightColors, 0, floatCount);
                cachedLightColorBuffer.flip();
                GPUCommandBatcher.bufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, lightColorsSSBO, 0, cachedLightColorBuffer);
            }

            // 上传参数 - 使用缓存 FloatBuffer
            ensureLightBufferCapacity(1); // 确保 param buffer 已分配
            cachedLightParamBuffer.clear();
            // viewProjection (16 floats)
            cachedLightParamBuffer.put(viewProjection, 0, 16);
            // inverseViewProjection (16 floats)
            cachedLightParamBuffer.put(inverseViewProjection, 0, 16);
            // cameraPos (4 floats)
            cachedLightParamBuffer.put(cameraPos, 0, 4);
            // ambientColor (4 floats)
            cachedLightParamBuffer.put(ambientColor, 0, 4);
            // lightDir (4 floats)
            cachedLightParamBuffer.put(lightDir, 0, 4);
            // counts and dimensions (4 ints)
            cachedLightParamBuffer.put(pointLightCount);
            cachedLightParamBuffer.put(0); // spotLightCount
            cachedLightParamBuffer.put(width);
            cachedLightParamBuffer.put(height);
            cachedLightParamBuffer.flip();
            GPUCommandBatcher.bufferSubData(GL31.GL_UNIFORM_BUFFER, paramBuffer, 0, cachedLightParamBuffer);

            // 绑定纹理
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, normalTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, albedoTexture);

            // 绑定输出图像
            GL42.glBindImageTexture(3, outputTexture, 0, false, 0, GL43.GL_WRITE_ONLY, GL30.GL_RGBA16F);

            // 绑定SSBO和UBO
            GPUCommandBatcher.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, lightPositionsSSBO);
            GPUCommandBatcher.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, lightColorsSSBO);
            GPUCommandBatcher.bindBufferBase(GL31.GL_UNIFORM_BUFFER, 6, paramBuffer);

            // 调度计算
            GPUCommandBatcher.useProgram(computeProgram);
            int dispatchX = (width + 15) / 16;
            int dispatchY = (height + 15) / 16;
            GPUCommandBatcher.dispatchCompute(dispatchX, dispatchY, 1);
            GPUCommandBatcher.memoryBarrier(MemoryBarrierOptimizer.BARRIER_IMAGE);

            // 提交批处理
            GPUCommandBatcher.endBatch();

            // 清理
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, 0);
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, 0);
            GL43.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 6, 0);
            GL20.glUseProgram(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            computeDispatches.incrementAndGet();
            lastComputeSuccess = true;
            consecutiveFailures = 0;

            long endTime = System.nanoTime();
            totalComputeTimeNs += (endTime - startTime);

        } catch (Exception e) {
            lastComputeSuccess = false;
            consecutiveFailures++;
            if (consecutiveFailures <= 3) {
                UHDFast.LOGGER.warn("GPU lighting compute failed (attempt {}): {}", consecutiveFailures,
                        e.getMessage());
            }
            if (consecutiveFailures >= MAX_FAILURES_BEFORE_FALLBACK) {
                UHDFast.LOGGER.warn("GPU lighting disabled after {} consecutive failures", consecutiveFailures);
                active = false;
            }
        }
    }

    public static int getOutputTexture() {
        return outputTexture;
    }

    public static boolean isActive() {
        return active && initialized;
    }

    public static int getComputeDispatches() {
        return computeDispatches.get();
    }

    public static long getTotalComputeTimeMs() {
        return totalComputeTimeNs / 1_000_000;
    }

    public static void resetStats() {
        computeDispatches.set(0);
        totalComputeTimeNs = 0;
    }

    public static void destroy() {
        if (computeProgram != 0) {
            GL43.glDeleteProgram(computeProgram);
            computeProgram = 0;
        }
        int[] buffers = new int[] { lightPositionsSSBO, lightColorsSSBO, paramBuffer };
        GL15.glDeleteBuffers(buffers);
        if (outputTexture != 0) {
            GL11.glDeleteTextures(outputTexture);
            outputTexture = 0;
        }
        // 释放缓存 FloatBuffer
        if (cachedLightPosBuffer != null) { MemoryUtil.memFree(cachedLightPosBuffer); cachedLightPosBuffer = null; }
        if (cachedLightColorBuffer != null) { MemoryUtil.memFree(cachedLightColorBuffer); cachedLightColorBuffer = null; }
        if (cachedLightParamBuffer != null) { MemoryUtil.memFree(cachedLightParamBuffer); cachedLightParamBuffer = null; }
        cachedLightBufferCapacity = 0;
        initialized = false;
        active = false;
    }

    /**
 * 使用Camera和光源列表执行GPU光照计算（高层API）
 * 
 * @param camera Minecraft相机
 * @param lights 光源列表
 * @return 计算是否成功
 * 
 * 优化：使用静态缓存数组避免每帧分配
 */
public static boolean computeLighting(Camera camera, List<LightSource> lights) {
    if (!active || !initialized) {
        lastComputeSuccess = false;
        return false;
    }

    long startTime = System.nanoTime();

    try {
        // 复用静态数组，避免每帧分配
        if (cachedCamPos == null) cachedCamPos = new float[4];
        if (cachedAmbient == null) cachedAmbient = new float[]{0.2f, 0.2f, 0.3f, 1.0f};
        if (cachedDir == null) cachedDir = new float[]{0.0f, -1.0f, 0.0f, 1.0f};
        if (cachedVP == null) cachedVP = new float[16];
        if (cachedIVP == null) cachedIVP = new float[16];

        // 从Camera提取位置
        if (camera != null && camera.getPosition() != null) {
            var pos = camera.getPosition();
            cachedCamPos[0] = (float) pos.x;
            cachedCamPos[1] = (float) pos.y;
            cachedCamPos[2] = (float) pos.z;
            cachedCamPos[3] = 1.0f;
        }

        // 从光源列表提取数据
        int pointCount = 0;

        if (lights != null && !lights.isEmpty()) {
            int maxLights = Math.min(lights.size(), MAX_LIGHTS);
            // 确保缓存数组足够大
            if (cachedPositions == null || cachedPositions.length < maxLights * 4) {
                cachedPositions = new float[maxLights * 4];
                cachedColors = new float[maxLights * 4];
            }

            for (int i = 0; i < lights.size(); i++) {
                LightSource light = lights.get(i);
                if (light.type == LightSource.LightType.POINT) {
                    int idx = pointCount * 4;
                    cachedPositions[idx] = light.posX;
                    cachedPositions[idx + 1] = light.posY;
                    cachedPositions[idx + 2] = light.posZ;
                    cachedPositions[idx + 3] = 0.0f;

                    cachedColors[idx] = light.colorR;
                    cachedColors[idx + 1] = light.colorG;
                    cachedColors[idx + 2] = light.colorB;
                    cachedColors[idx + 3] = light.intensity;
                    pointCount++;
                } else if (light.type == LightSource.LightType.DIRECTIONAL) {
                    cachedDir[0] = light.dirX;
                    cachedDir[1] = light.dirY;
                    cachedDir[2] = light.dirZ;
                    cachedDir[3] = light.intensity;
                    cachedAmbient[0] = light.colorR * 0.3f;
                    cachedAmbient[1] = light.colorG * 0.3f;
                    cachedAmbient[2] = light.colorB * 0.3f;
                    cachedAmbient[3] = 1.0f;
                }
            }
        }

        // 获取窗口尺寸
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        int width = 1920, height = 1080;
        if (mc.getWindow() != null) {
            width = mc.getWindow().getWidth();
            height = mc.getWindow().getHeight();
        }

        // 使用单位矩阵作为viewProjection（简化）
        // 复用缓存数组，仅第一次设置
        if (cachedVP[0] == 0) {
            cachedVP[0] = 1; cachedVP[5] = 1; cachedVP[10] = 1; cachedVP[15] = 1;
            cachedIVP[0] = 1; cachedIVP[5] = 1; cachedIVP[10] = 1; cachedIVP[15] = 1;
        }

        // 调用底层computeLighting
        computeLighting(0, 0, 0, width, height,
                pointCount > 0 ? cachedPositions : null, cachedColors,
                pointCount, cachedVP, cachedIVP, cachedCamPos, cachedAmbient, cachedDir);

        lastComputeSuccess = true;
        consecutiveFailures = 0;

        long endTime = System.nanoTime();
        UHDFast.LOGGER.debug("GPU lighting computed in {} ms", (endTime - startTime) / 1_000_000);

        return true;

    } catch (Exception e) {
        lastComputeSuccess = false;
        consecutiveFailures++;
        UHDFast.LOGGER.warn("GPU lighting compute failed (attempt {}): {}",
                consecutiveFailures, e.getMessage());

        if (consecutiveFailures >= MAX_FAILURES_BEFORE_FALLBACK) {
            UHDFast.LOGGER.warn("GPU lighting disabled after {} consecutive failures, falling back to CPU",
                    consecutiveFailures);
            active = false;
        }
        return false;
    }
}

/** 缓存数组，避免每帧分配 */
private static float[] cachedCamPos;
private static float[] cachedPositions;
private static float[] cachedColors;
private static float[] cachedAmbient;
private static float[] cachedDir;
private static float[] cachedVP;
private static float[] cachedIVP;

/** 缓存 FloatBuffer，避免 MemoryUtil.memAllocFloat 在热路径中创建 */
private static java.nio.FloatBuffer cachedLightPosBuffer;
private static java.nio.FloatBuffer cachedLightColorBuffer;
private static java.nio.FloatBuffer cachedLightParamBuffer;
private static int cachedLightBufferCapacity = 0;

private static void ensureLightBufferCapacity(int needed) {
    if (cachedLightBufferCapacity < needed) {
        int newCapacity = Math.max(needed, 1024);
        if (cachedLightPosBuffer != null) MemoryUtil.memFree(cachedLightPosBuffer);
        if (cachedLightColorBuffer != null) MemoryUtil.memFree(cachedLightColorBuffer);
        if (cachedLightParamBuffer != null) MemoryUtil.memFree(cachedLightParamBuffer);
        cachedLightPosBuffer = MemoryUtil.memAllocFloat(newCapacity);
        cachedLightColorBuffer = MemoryUtil.memAllocFloat(newCapacity);
        cachedLightParamBuffer = MemoryUtil.memAllocFloat(64);
        cachedLightBufferCapacity = newCapacity;
    }
}

    /**
     * 获取光照计算结果纹理ID
     */
    public static int getLightingResult() {
        return outputTexture;
    }

    /**
     * 检查是否初始化成功
     */
    public static boolean isInitialized() {
        return initialized && active;
    }

    /**
     * 检查上次计算是否成功
     */
    public static boolean wasLastComputeSuccessful() {
        return lastComputeSuccess;
    }

    /**
     * 重置失败计数
     */
    public static void resetFailureCount() {
        consecutiveFailures = 0;
        lastComputeSuccess = false;
    }
}
