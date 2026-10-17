package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

/**
 * GPU光照集成管理器
 * 负责将XeLightingCompute集成到Minecraft渲染管线
 */
public class XeLightingIntegration {

    private static boolean initialized = false;
    private static boolean active = false;

    // 光照参数缓存
    private static final float[] viewProjection = new float[16];
    private static final float[] inverseViewProjection = new float[16];
    private static final float[] cameraPos = new float[4];
    private static final float[] ambientColor = new float[4];
    private static final float[] lightDir = new float[4];

    // 光源数据
    private static float[] lightPositions = null;
    private static float[] lightColors = null;
    private static int pointLightCount = 0;

    // 默认纹理ID（当G-Buffer不可用时使用）
    private static int fallbackDepthTexture = 0;
    private static int fallbackNormalTexture = 0;
    private static int fallbackAlbedoTexture = 0;
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    /**
     * 初始化光照集成
     */
    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!UHDFastConfig.enableXeComputePipeline.get()) {
            UHDFast.LOGGER.info("Xe Lighting Integration disabled by config");
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Xe Lighting Integration disabled - not Xe architecture");
            return;
        }

        try {
            // 初始化底层GPU光照计算
            XeLightingCompute.initialize();

            if (XeLightingCompute.isActive()) {
                initialized = true;
                active = true;
                createFallbackTextures();
                UHDFast.LOGGER.info("Xe Lighting Integration initialized successfully");
            } else {
                UHDFast.LOGGER.warn("Xe Lighting Compute is not active, integration disabled");
            }
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to initialize Xe Lighting Integration: {}", e.getMessage());
            active = false;
        }
    }

    /**
     * 创建备用纹理（当G-Buffer不可用时）
     */
    private static void createFallbackTextures() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getWindow() != null) {
                lastWidth = mc.getWindow().getWidth();
                lastHeight = mc.getWindow().getHeight();

                // 创建默认深度纹理
                fallbackDepthTexture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackDepthTexture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT,
                        lastWidth, lastHeight, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (FloatBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

                // 创建默认法线纹理
                fallbackNormalTexture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackNormalTexture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGB16F,
                        lastWidth, lastHeight, 0, GL11.GL_RGB, GL11.GL_FLOAT, (FloatBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

                // 创建默认反照率纹理
                fallbackAlbedoTexture = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackAlbedoTexture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8,
                        lastWidth, lastHeight, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

                UHDFast.LOGGER.info("Fallback textures created: {}x{}", lastWidth, lastHeight);
            }
        } catch (Exception e) {
            UHDFast.LOGGER.warn("Failed to create fallback textures: {}", e.getMessage());
        }
    }

    /**
     * 在渲染帧开始时调用，收集光照参数并执行GPU光照计算
     */
    public static void onFrameRender() {
        if (!active || !initialized) {
            return;
        }

        long startTime = System.nanoTime();

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.cameraEntity == null) {
                return;
            }

            // 获取窗口尺寸
            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();

            // 检查是否需要重建备用纹理
            if (width != lastWidth || height != lastHeight) {
                cleanupFallbackTextures();
                createFallbackTextures();
            }

            // 收集相机和矩阵数据
            collectCameraData(mc);

            // 收集真实光照数据（从Minecraft世界）
            collectLightingData(mc);

            // 执行GPU光照计算
            // 注意：由于Minecraft没有标准的G-Buffer，我们使用备用纹理
            // 在实际应用中，可能需要从shader mod（如Iris/Oculus）获取G-Buffer
            XeLightingCompute.computeLighting(
                    fallbackDepthTexture,
                    fallbackNormalTexture,
                    fallbackAlbedoTexture,
                    width,
                    height,
                    lightPositions,
                    lightColors,
                    pointLightCount,
                    viewProjection,
                    inverseViewProjection,
                    cameraPos,
                    ambientColor,
                    lightDir);

            // 检查GPU计算是否成功
            if (!XeLightingCompute.wasLastComputeSuccessful()) {
                UHDFast.LOGGER.warn("GPU lighting compute failed, falling back to CPU lighting");
                // CPU回退：使用Minecraft原生光照系统
                // 这里不需要额外操作，Minecraft会自动使用CPU光照
            } else {
                long endTime = System.nanoTime();
                long durationMs = (endTime - startTime) / 1_000_000;
                if (durationMs > 5) {
                    UHDFast.LOGGER.debug("GPU lighting frame took {} ms", durationMs);
                }
            }

        } catch (Exception e) {
            UHDFast.LOGGER.warn("GPU lighting frame update failed: {}", e.getMessage());
            // CPU回退：Minecraft原生光照系统会自动处理
        }
    }

    /**
     * 收集相机和矩阵数据
     */
    private static void collectCameraData(Minecraft mc) {
        try {
            // 获取相机位置
            var cameraEntity = mc.cameraEntity;
            if (cameraEntity != null) {
                cameraPos[0] = (float) cameraEntity.getX();
                cameraPos[1] = (float) cameraEntity.getY();
                cameraPos[2] = (float) cameraEntity.getZ();
                cameraPos[3] = 1.0f;
            }

            // 使用RenderSystem获取当前投影矩阵
            try {
                Matrix4f projMatrix = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
                if (projMatrix != null) {
                    matrix4fToArray(projMatrix, viewProjection);

                    // 计算逆矩阵
                    Matrix4f invProj = new Matrix4f();
                    projMatrix.invert(invProj);
                    matrix4fToArray(invProj, inverseViewProjection);
                } else {
                    setIdentityMatrix(viewProjection);
                    setIdentityMatrix(inverseViewProjection);
                }
            } catch (Exception e) {
                // 如果RenderSystem不可用，使用单位矩阵
                setIdentityMatrix(viewProjection);
                setIdentityMatrix(inverseViewProjection);
            }

            // 设置环境光颜色（简化版本）
            // 在实际应用中，应该从Minecraft的光照系统获取真实的环境光
            ambientColor[0] = 0.2f;
            ambientColor[1] = 0.2f;
            ambientColor[2] = 0.3f;
            ambientColor[3] = 1.0f;

            // 设置太阳光方向（简化：假设从上方照射）
            // 在实际应用中，应该从世界的时间计算太阳位置
            lightDir[0] = 0.0f;
            lightDir[1] = -1.0f;
            lightDir[2] = 0.0f;
            lightDir[3] = 1.0f; // 启用方向光

        } catch (Exception e) {
            UHDFast.LOGGER.debug("Failed to collect camera data: {}", e.getMessage());
            // 使用默认值
            cameraPos[0] = 0;
            cameraPos[1] = 64;
            cameraPos[2] = 0;
            cameraPos[3] = 1.0f;

            // 单位矩阵
            setIdentityMatrix(viewProjection);
            setIdentityMatrix(inverseViewProjection);

            ambientColor[0] = 0.2f;
            ambientColor[1] = 0.2f;
            ambientColor[2] = 0.3f;
            ambientColor[3] = 1.0f;

            lightDir[0] = 0.0f;
            lightDir[1] = -1.0f;
            lightDir[2] = 0.0f;
            lightDir[3] = 1.0f;
        }
    }

    /**
     * 收集光照数据（从Minecraft世界获取真实光源）
     */
    @SuppressWarnings("null")
    private static void collectLightingData(Minecraft mc) {
        try {
            // 初始化光源数组（最大支持256个光源）
            if (lightPositions == null) {
                lightPositions = new float[256 * 4]; // 256个光源 * 4个分量
                lightColors = new float[256 * 4];
            }

            pointLightCount = 0;

            // 从Minecraft世界获取真实光源
            if (mc.level != null && mc.cameraEntity != null) {
                var playerPos = mc.cameraEntity.blockPosition();
                var level = mc.level;

                // 收集玩家周围的方块光源
                int searchRadius = 16;
                for (int x = -searchRadius; x <= searchRadius; x += 4) {
                    for (int y = -searchRadius; y <= searchRadius; y += 4) {
                        for (int z = -searchRadius; z <= searchRadius; z += 4) {
                            var blockPos = playerPos.offset(x, y, z);

                            // 获取方块光照等级
                            int blockLight = level != null ? level.getBrightness(LightLayer.BLOCK, blockPos) : 0;

                            // 如果有光源（光照等级>0）
                            if (blockLight > 0 && pointLightCount < 256) {
                                int idx = pointLightCount * 4;

                                // 光源位置
                                lightPositions[idx] = blockPos.getX() + 0.5f;
                                lightPositions[idx + 1] = blockPos.getY() + 0.5f;
                                lightPositions[idx + 2] = blockPos.getZ() + 0.5f;
                                lightPositions[idx + 3] = 0.0f; // 类型：点光源

                                // 光源颜色（根据光照等级）
                                float intensity = blockLight / 15.0f;
                                lightColors[idx] = 1.0f * intensity; // R
                                lightColors[idx + 1] = 0.9f * intensity; // G
                                lightColors[idx + 2] = 0.8f * intensity; // B
                                lightColors[idx + 3] = intensity; // 强度

                                pointLightCount++;
                            }
                        }
                    }
                }

                // 如果没有找到光源，创建一个默认光源
                if (pointLightCount == 0) {
                    lightPositions[0] = cameraPos[0];
                    lightPositions[1] = cameraPos[1] + 10;
                    lightPositions[2] = cameraPos[2];
                    lightPositions[3] = 0.0f;

                    lightColors[0] = 1.0f;
                    lightColors[1] = 0.9f;
                    lightColors[2] = 0.8f;
                    lightColors[3] = 1.0f;

                    pointLightCount = 1;
                }
            }

        } catch (Exception e) {
            UHDFast.LOGGER.debug("Failed to collect lighting data: {}", e.getMessage());
            // 使用默认光源
            if (lightPositions == null) {
                lightPositions = new float[4];
                lightColors = new float[4];
            }
            lightPositions[0] = cameraPos[0];
            lightPositions[1] = cameraPos[1] + 10;
            lightPositions[2] = cameraPos[2];
            lightPositions[3] = 0.0f;

            lightColors[0] = 1.0f;
            lightColors[1] = 0.9f;
            lightColors[2] = 0.8f;
            lightColors[3] = 1.0f;

            pointLightCount = 1;
        }
    }

    /**
     * 将Matrix4f转换为float数组
     */
    private static void matrix4fToArray(Matrix4f matrix, float[] array) {
        array[0] = matrix.m00();
        array[1] = matrix.m10();
        array[2] = matrix.m20();
        array[3] = matrix.m30();
        array[4] = matrix.m01();
        array[5] = matrix.m11();
        array[6] = matrix.m21();
        array[7] = matrix.m31();
        array[8] = matrix.m02();
        array[9] = matrix.m12();
        array[10] = matrix.m22();
        array[11] = matrix.m32();
        array[12] = matrix.m03();
        array[13] = matrix.m13();
        array[14] = matrix.m23();
        array[15] = matrix.m33();
    }

    /**
     * 设置单位矩阵
     */
    private static void setIdentityMatrix(float[] matrix) {
        for (int i = 0; i < 16; i++) {
            matrix[i] = (i % 5 == 0) ? 1.0f : 0.0f;
        }
    }

    /**
     * 清理备用纹理
     */
    private static void cleanupFallbackTextures() {
        if (fallbackDepthTexture != 0) {
            GL11.glDeleteTextures(fallbackDepthTexture);
            fallbackDepthTexture = 0;
        }
        if (fallbackNormalTexture != 0) {
            GL11.glDeleteTextures(fallbackNormalTexture);
            fallbackNormalTexture = 0;
        }
        if (fallbackAlbedoTexture != 0) {
            GL11.glDeleteTextures(fallbackAlbedoTexture);
            fallbackAlbedoTexture = 0;
        }
    }

    /**
     * 获取GPU光照计算的输出纹理
     */
    public static int getOutputTexture() {
        if (!active || !initialized) {
            return 0;
        }
        return XeLightingCompute.getOutputTexture();
    }

    /**
     * 检查光照集成是否激活
     */
    public static boolean isActive() {
        return active && initialized;
    }

    /**
     * 销毁资源
     */
    public static void destroy() {
        if (!initialized) {
            return;
        }

        try {
            cleanupFallbackTextures();
            XeLightingCompute.destroy();
            initialized = false;
            active = false;
            UHDFast.LOGGER.info("Xe Lighting Integration destroyed");
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to destroy Xe Lighting Integration: {}", e.getMessage());
        }
    }

    /**
     * 获取统计信息
     */
    public static String getStats() {
        if (!active || !initialized) {
            return "GPU Lighting: Disabled";
        }
        return String.format("GPU Lighting: Active (Dispatches: %d, Time: %d ms)",
                XeLightingCompute.getComputeDispatches(),
                XeLightingCompute.getTotalComputeTimeMs());
    }
}
