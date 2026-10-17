package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.optimizer.RenderOptimizer;
import com.xor.uhdfast.shaderpack.ShaderPackManager;

/**
 * GPU负载动态调整器 - 根据帧率自动调节GPU任务并发数
 *
 * 功能：
 * 1. 监控当前帧率
 * 2. 当帧率低于目标时，减少GPU任务并发数
 * 3. 当帧率高于目标时，增加GPU任务并发数
 * 4. 动态调整XeFenceScheduler的MAX_CONCURRENT值
 */
public class GPULoadBalancer {

    private static boolean initialized = false;
    private static boolean running = false;

    // 目标帧率配置（用户要求视距32时80+ FPS，设为略高以留余量）
    private static int targetFPS = 90;

    // 并发任务调整范围
    private static int minConcurrent = 2;
    private static int maxConcurrent = 16;
    private static int currentConcurrent = 8;

    // 调整阈值
    private static int fpsLowThreshold = 45; // 低于此值开始减少并发
    private static int fpsHighThreshold = 55; // 高于此值开始增加并发

    // 调整步长
    private static int adjustStep = 1;

    // 统计信息
    private static long lastAdjustTime = 0;
    private static int totalAdjustments = 0;
    private static int increaseCount = 0;
    private static int decreaseCount = 0;

    /**
     * 初始化负载均衡器
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("GPU Load Balancer disabled - not Xe architecture");
            return;
        }

        // 根据EU数量设置初始并发数
        int euCount = IntelXeDetector.getEUCount();
        if (euCount >= 96) {
            currentConcurrent = 8;
            maxConcurrent = 16;
        } else if (euCount >= 48) {
            currentConcurrent = 6;
            maxConcurrent = 12;
        } else {
            currentConcurrent = 4;
            maxConcurrent = 8;
        }

        minConcurrent = Math.max(2, currentConcurrent / 2);

        // 应用初始设置
        XeFenceScheduler.setMaxConcurrent(currentConcurrent);

        running = true;
        initialized = true;

        UHDFast.LOGGER.info("GPU Load Balancer initialized:");
        UHDFast.LOGGER.info("  Target FPS: {}", targetFPS);
        UHDFast.LOGGER.info("  Initial concurrent: {}", currentConcurrent);
        UHDFast.LOGGER.info("  Range: [{}, {}]", minConcurrent, maxConcurrent);
    }

    /**
     * 每帧调用 - 监控帧率并调整并发数
     */
    public static void update() {
        if (!running || !initialized) {
            return;
        }

        long currentTime = System.currentTimeMillis();

        // 每秒调整一次，避免频繁调整
        if (currentTime - lastAdjustTime < 1000) {
            return;
        }

        lastAdjustTime = currentTime;

        // 光影启用时降低 GPU 优化任务负载，避免与光影渲染争用 GPU 资源
        if (isShaderPackActive()) {
            int shaderMax = getAdjustedMaxConcurrent(maxConcurrent);
            if (currentConcurrent > shaderMax) {
                int oldConcurrent = currentConcurrent;
                currentConcurrent = shaderMax;
                XeFenceScheduler.setMaxConcurrent(currentConcurrent);
                totalAdjustments++;
                decreaseCount++;
                UHDFast.LOGGER.info(
                        "GPU Load Balancer: Shader pack active - reduced GPU optimization load {} -> {} (avoid shader contention)",
                        oldConcurrent, currentConcurrent);
            }
        }

        // 获取当前帧率
        float currentFPS = RenderOptimizer.getCurrentFPS();

        if (currentFPS <= 0) {
            return; // 帧率数据不可用
        }

        // 根据帧率调整并发数
        if (currentFPS < fpsLowThreshold) {
            // 帧率过低，减少并发
            decreaseConcurrent();
        } else if (currentFPS > fpsHighThreshold) {
            // 帧率较高，增加并发
            increaseConcurrent();
        }
        // 在阈值之间时保持不变
    }

    /**
     * 减少GPU任务并发数
     */
    private static void decreaseConcurrent() {
        if (currentConcurrent > minConcurrent) {
            int oldConcurrent = currentConcurrent;
            currentConcurrent = Math.max(minConcurrent, currentConcurrent - adjustStep);

            if (currentConcurrent != oldConcurrent) {
                XeFenceScheduler.setMaxConcurrent(currentConcurrent);
                totalAdjustments++;
                decreaseCount++;

                UHDFast.LOGGER.debug("GPU Load Balancer: Decreased concurrent {} -> {} (FPS low)",
                        oldConcurrent, currentConcurrent);
            }
        }
    }

    /**
     * 增加GPU任务并发数
     */
    private static void increaseConcurrent() {
        // 光影模式下使用降低后的上限，避免增加并发挤占光影渲染
        int effectiveMax = isShaderPackActive() ? getAdjustedMaxConcurrent(maxConcurrent) : maxConcurrent;
        if (currentConcurrent < effectiveMax) {
            int oldConcurrent = currentConcurrent;
            currentConcurrent = Math.min(effectiveMax, currentConcurrent + adjustStep);

            if (currentConcurrent != oldConcurrent) {
                XeFenceScheduler.setMaxConcurrent(currentConcurrent);
                totalAdjustments++;
                increaseCount++;

                UHDFast.LOGGER.debug("GPU Load Balancer: Increased concurrent {} -> {} (FPS high)",
                        oldConcurrent, currentConcurrent);
            }
        }
    }

    // ========================================================================
    // 配置方法
    // ========================================================================

    public static void setTargetFPS(int fps) {
        targetFPS = fps;
        fpsLowThreshold = (int) (fps * 0.75);
        fpsHighThreshold = (int) (fps * 0.92);
    }

    public static void setConcurrentRange(int min, int max) {
        minConcurrent = min;
        maxConcurrent = max;
        currentConcurrent = Math.max(minConcurrent, Math.min(maxConcurrent, currentConcurrent));
        XeFenceScheduler.setMaxConcurrent(currentConcurrent);
    }

    // ========================================================================
    // 状态查询方法
    // ========================================================================

    /**
     * 查询当前光影包是否处于启用状态。
     *
     * @return 光影包已加载且启用时返回 true
     */
    public static boolean isShaderPackActive() {
        return ShaderPackManager.getInstance().isShaderPackLoaded();
    }

    /**
     * 根据光影启用状态调整最大并发数。
     * <p>
     * 光影启用时将基础并发上限减半（不低于 1），以降低 GPU 优化任务优先级，
     * 避免与光影渲染管线争用 GPU 资源。
     * </p>
     *
     * @param baseMax 基础最大并发数
     * @return 调整后的最大并发数
     */
    public static int getAdjustedMaxConcurrent(int baseMax) {
        if (isShaderPackActive()) {
            return Math.max(1, baseMax / 2);
        }
        return baseMax;
    }

    public static int getCurrentConcurrent() {
        return currentConcurrent;
    }

    public static int getTargetFPS() {
        return targetFPS;
    }

    public static int getTotalAdjustments() {
        return totalAdjustments;
    }

    public static int getIncreaseCount() {
        return increaseCount;
    }

    public static int getDecreaseCount() {
        return decreaseCount;
    }

    public static boolean isRunning() {
        return running;
    }

    public static String getStatus() {
        return String.format("LoadBalancer: concurrent=%d/%d target=%dfps adjustments=%d (+%d/-%d)",
                currentConcurrent, maxConcurrent, targetFPS,
                totalAdjustments, increaseCount, decreaseCount);
    }

    /**
     * 清理负载均衡器
     */
    public static synchronized void cleanup() {
        running = false;
        UHDFast.LOGGER.info("GPU Load Balancer cleaned up");
    }
}
