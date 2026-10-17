package com.xor.uhdfast.optimizer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

public class RenderOptimizer {

    private static boolean optimizationsApplied = false;

    private static int lastBoundTexture = -1;
    private static int lastBoundVAO = -1;
    private static int lastProgram = -1;

    private static long frameCount = 0;
    private static long totalStateChanges = 0;
    private static long savedStateChanges = 0;
    private static long lastFrameTime = 0;
    private static float currentFPS = 0;

    public static void applyOptimizations() {
        if (optimizationsApplied) {
            return;
        }

        UHDFast.LOGGER.info("Applying aggressive render optimizations...");

        optimizeOpenGLState();
        optimizeTextureSettings();
        optimizeRenderPipeline();
        optimizeMemoryUsage();
        enableSmartCulling();

        optimizationsApplied = true;
        UHDFast.LOGGER.info("Render optimizations applied successfully");
    }

    private static void optimizeOpenGLState() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // 移除冗余的 depth test 开关对
        RenderSystem.enableDepthTest();

        // 移除冗余的 cull 开关对（GlStateManager._enableCull 和 RenderSystem.enableCull 是同一个操作）
        GlStateManager._enableCull();

        GL11.glHint(GL11.GL_PERSPECTIVE_CORRECTION_HINT, GL11.GL_FASTEST);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_FASTEST);
        GL11.glHint(GL11.GL_POLYGON_SMOOTH_HINT, GL11.GL_FASTEST);

        UHDFast.LOGGER.info("OpenGL state optimized");
    }

    private static void optimizeTextureSettings() {
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);

        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, -0.5f);

        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        UHDFast.LOGGER.info("Texture settings optimized");
    }

    private static void optimizeRenderPipeline() {
        GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);

        UHDFast.LOGGER.info("Render pipeline optimized");
    }

    private static void optimizeMemoryUsage() {
        // 移除System.gc()调用，避免在加载地形时造成卡顿
        // System.gc()会导致JVM完全停止数秒，表现为"加载地形中"卡住
        UHDFast.LOGGER.info("Memory optimization applied (no forced GC)");
    }

    private static void enableSmartCulling() {
        UHDFast.LOGGER.info("Smart frustum culling enabled");
    }

    public static boolean shouldRenderChunk(int chunkX, int chunkZ, double playerX, double playerZ) {
        if (!UHDFastConfig.enableRenderDistanceOptimization.get()) {
            return true;
        }

        double dx = (chunkX * 16 + 8) - playerX;
        double dz = (chunkZ * 16 + 8) - playerZ;
        double distanceSq = dx * dx + dz * dz;

        double maxDist = 128 * 16.0;
        double renderDist = maxDist * UHDFastConfig.renderDistanceMultiplier.get();

        if (distanceSq > renderDist * renderDist) {
            return false;
        }

        // 用平方距离替代 sqrt：64^2 = 4096
        if (distanceSq < 4096.0) {
            return true;
        }

        // 用点积替代 atan2/toDegrees 进行视锥剔除
        Minecraft mc = Minecraft.getInstance();
        if (mc.cameraEntity != null) {
            float playerYaw = mc.cameraEntity.getYRot();
            // 将 yaw 转换为弧度，计算视线方向向量
            float yawRad = (float) Math.toRadians(playerYaw + 180.0f);
            float lookX = -(float) Math.sin(yawRad);
            float lookZ = (float) Math.cos(yawRad);

            // 未归一化的点积：dot = look·dir
            // 由于 |look|=1 且 |dir|>0，dot 的符号与 cos(angle) 相同
            double dot = lookX * dx + lookZ * dz;

            // dot < 0 表示在背后（angle > 90°），无需 sqrt 归一化
            if (dot < 0.0) {
                // 背后区域：远距离剔除
                if (distanceSq > 65536.0) { // 256^2
                    return false;
                }
                // 侧面衰减
                double sideThreshold = (renderDist * 0.5) * (renderDist * 0.5);
                if (distanceSq > sideThreshold) {
                    return false;
                }
            }
        }

        return true;
    }

    public static boolean shouldRenderEntity(double entityX, double entityY, double entityZ,
            double playerX, double playerY, double playerZ) {
        if (!UHDFastConfig.enableEntityRenderOptimization.get()) {
            return true;
        }

        double dx = entityX - playerX;
        double dy = entityY - playerY;
        double dz = entityZ - playerZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;

        double maxDist = 128.0 * UHDFastConfig.renderDistanceMultiplier.get();
        return distanceSq <= maxDist * maxDist;
    }

    public static void bindTextureOptimized(int textureId) {
        if (lastBoundTexture == textureId) {
            savedStateChanges++;
            return;
        }
        lastBoundTexture = textureId;
        totalStateChanges++;
        RenderSystem.bindTexture(textureId);
    }

    public static void bindVAOOptimized(int vaoId) {
        if (lastBoundVAO == vaoId) {
            savedStateChanges++;
            return;
        }
        lastBoundVAO = vaoId;
        totalStateChanges++;
        GL30.glBindVertexArray(vaoId);
    }

    public static void useProgramOptimized(int programId) {
        if (lastProgram == programId) {
            savedStateChanges++;
            return;
        }
        lastProgram = programId;
        totalStateChanges++;
        GL20.glUseProgram(programId);
    }

    public static void onFrameStart() {
        long now = System.nanoTime();
        if (lastFrameTime > 0) {
            long delta = now - lastFrameTime;
            currentFPS = 1_000_000_000.0f / delta;
        }
        lastFrameTime = now;
    }

    public static void onFrameEnd() {
        frameCount++;

        // 只在有实际状态变化时记录日志，且频率降低到每1800帧（~30秒）
        if (frameCount % 1800 == 0) {
            if (totalStateChanges > 0 || savedStateChanges > 0) {
                UHDFast.LOGGER.info("Render stats: {} state changes, {} saved ({}% reduction)",
                        totalStateChanges, savedStateChanges,
                        totalStateChanges > 0 ? (savedStateChanges * 100 / (totalStateChanges + savedStateChanges)) : 0);
            }
        }
    }

    public static float getCurrentFPS() {
        return currentFPS;
    }

    public static float getRenderDistanceMultiplier() {
        try {
            if (UHDFastConfig.renderDistanceMultiplier != null) {
                return UHDFastConfig.renderDistanceMultiplier.get().floatValue();
            }
        } catch (Exception ignored) {
        }
        return 1.5f;
    }

    public static int getMaxRenderDistance() {
        return 128;
    }

    public static int getOptimalChunkSectionRenderDistance() {
        return Math.max(2, (int) (8 * getRenderDistanceMultiplier()));
    }

    public static long getFrameCount() {
        return frameCount;
    }

    public static long getTotalStateChanges() {
        return totalStateChanges;
    }

    public static long getSavedStateChanges() {
        return savedStateChanges;
    }

    // ========== 帧时间平滑算法 ==========
    // 滑动窗口大小：记录最近N帧的帧时间，用于平滑预测
    private static final int FRAME_TIME_WINDOW = 10;
    private static final long[] frameTimeHistory = new long[FRAME_TIME_WINDOW];
    private static int frameTimeIndex = 0;
    private static int frameTimeCount = 0;
    // 平滑后的帧时间（纳秒），使用指数加权移动平均
    private static long smoothedFrameTimeNanos = 0;
    private static final long MAX_FRAME_TIME_NS = 100_000_000L; // 100ms，超过此值视为异常卡顿

    /**
     * 更新帧时间历史，使用指数加权移动平均（EWMA）平滑帧时间。
     * EWMA相比简单平均更能反映最近的帧时间变化。
     */
    private static void updateSmoothedFrameTime(long actualFrameTimeNanos) {
        // 限制异常值，避免单个卡顿帧污染平滑值
        long clampedTime = Math.min(actualFrameTimeNanos, MAX_FRAME_TIME_NS);

        // 记录到滑动窗口
        frameTimeHistory[frameTimeIndex] = clampedTime;
        frameTimeIndex = (frameTimeIndex + 1) % FRAME_TIME_WINDOW;
        if (frameTimeCount < FRAME_TIME_WINDOW) {
            frameTimeCount++;
        }

        // 计算滑动窗口平均值
        long sum = 0;
        for (int i = 0; i < frameTimeCount; i++) {
            sum += frameTimeHistory[i];
        }
        long windowAvg = sum / Math.max(1, frameTimeCount);

        // EWMA：权重0.3给新值，0.7给历史平滑值
        if (smoothedFrameTimeNanos == 0) {
            smoothedFrameTimeNanos = windowAvg;
        } else {
            smoothedFrameTimeNanos = (long) (0.3 * windowAvg + 0.7 * smoothedFrameTimeNanos);
        }
    }

    public static void applyFrameRateLimit() {
        try {
            if (!UHDFastConfig.enableFrameRateLimiter.get()) {
                return;
            }
        } catch (IllegalStateException e) {
            // Config not loaded yet, skip
            return;
        }

        int frameRateLimit = UHDFastConfig.maxFrameRate.get();
        if (frameRateLimit <= 0) {
            return;
        }

        long targetFrameTimeNanos = 1_000_000_000L / frameRateLimit;
        long elapsedNanos = System.nanoTime() - frameStartTime;

        // 更新平滑帧时间（用于帧时间平滑算法）
        updateSmoothedFrameTime(elapsedNanos);

        // 使用平滑后的帧时间计算剩余睡眠时间，防止单帧卡顿导致后续帧补偿过度
        long sleepNanos = targetFrameTimeNanos - Math.min(elapsedNanos, smoothedFrameTimeNanos);

        if (sleepNanos > 1_000_000) {
            // 短等待（< 2ms）：使用 busy-wait，避免 Thread.sleep 的上下文切换开销
            if (sleepNanos < 2_000_000) {
                long deadline = System.nanoTime() + sleepNanos;
                while (System.nanoTime() < deadline) {
                    // busy-wait: 极短等待，精度高
                    Thread.onSpinWait();
                }
            } else {
                // 长等待（>= 2ms）：使用 Thread.sleep 让出 CPU
                try {
                    long sleepMillis = sleepNanos / 1_000_000;
                    int sleepNanosRemainder = (int) (sleepNanos % 1_000_000);
                    Thread.sleep(sleepMillis, sleepNanosRemainder);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static long frameStartTime = 0;

    public static void markFrameStart() {
        frameStartTime = System.nanoTime();
    }
}
