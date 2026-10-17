package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GPU内存使用监控器
 *
 * 功能：
 * 1. 跟踪所有GPU资源的内存使用（SSBO、UBO、纹理等）
 * 2. 定期报告GPU内存使用情况
 * 3. 在PerformanceHUD中显示GPU内存使用统计
 * 4. 当内存使用超过阈值时发出警告
 */
public class GPUMemoryMonitor {

    private static boolean initialized = false;

    // 内存使用统计（按类型）
    private static final Map<String, AtomicLong> memoryUsageByType = new HashMap<>();
    private static final Map<String, AtomicLong> allocationCountByType = new HashMap<>();

    // 总体统计
    private static final AtomicLong totalGPUMemoryUsed = new AtomicLong(0);
    private static final AtomicLong totalAllocations = new AtomicLong(0);
    private static final AtomicLong totalDeallocations = new AtomicLong(0);
    private static final AtomicLong peakMemoryUsage = new AtomicLong(0);

    // 阈值配置
    private static long warningThresholdMB = 512; // 警告阈值
    private static long criticalThresholdMB = 768; // 严重警告阈值
    private static long maxMemoryMB = 1024; // 最大内存限制

    // 报告统计
    private static long lastReportTime = 0;
    private static int reportIntervalMs = 10000; // 每10秒报告一次
    private static int warningCount = 0;
    private static int criticalCount = 0;

    /**
     * 初始化GPU内存监控器
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("GPU Memory Monitor disabled - not Xe architecture");
            return;
        }

        // 根据GPU能力调整阈值
        int euCount = IntelXeDetector.getEUCount();
        if (euCount >= 96) {
            maxMemoryMB = 1024;
            warningThresholdMB = 768;
            criticalThresholdMB = 896;
        } else if (euCount >= 48) {
            maxMemoryMB = 768;
            warningThresholdMB = 512;
            criticalThresholdMB = 640;
        } else {
            maxMemoryMB = 512;
            warningThresholdMB = 384;
            criticalThresholdMB = 448;
        }

        initialized = true;
        lastReportTime = System.currentTimeMillis();

        UHDFast.LOGGER.info("GPU Memory Monitor initialized:");
        UHDFast.LOGGER.info("  Max memory: {} MB", maxMemoryMB);
        UHDFast.LOGGER.info("  Warning threshold: {} MB", warningThresholdMB);
        UHDFast.LOGGER.info("  Critical threshold: {} MB", criticalThresholdMB);
    }

    /**
     * 分配GPU内存
     *
     * @param type      资源类型（ssbo, ubo, texture, buffer等）
     * @param sizeBytes 分配的字节数
     */
    public static void allocate(String type, long sizeBytes) {
        if (!initialized) {
            return;
        }

        // 更新按类型的统计
        memoryUsageByType.computeIfAbsent(type, k -> new AtomicLong(0)).addAndGet(sizeBytes);
        allocationCountByType.computeIfAbsent(type, k -> new AtomicLong(0)).incrementAndGet();

        // 更新总体统计
        long currentTotal = totalGPUMemoryUsed.addAndGet(sizeBytes);
        totalAllocations.incrementAndGet();

        // 更新峰值
        if (currentTotal > peakMemoryUsage.get()) {
            peakMemoryUsage.set(currentTotal);
        }

        // 检查阈值
        long currentMB = currentTotal / (1024 * 1024);
        if (currentMB >= criticalThresholdMB) {
            criticalCount++;
            UHDFast.LOGGER.error("CRITICAL: GPU memory usage at {} MB (threshold: {} MB)",
                    currentMB, criticalThresholdMB);
            triggerAggressiveCleanup();
        } else if (currentMB >= warningThresholdMB) {
            warningCount++;
            UHDFast.LOGGER.warn("WARNING: GPU memory usage at {} MB (threshold: {} MB)",
                    currentMB, warningThresholdMB);
            triggerCleanup();
        }
    }

    /**
     * 释放GPU内存
     *
     * @param type      资源类型
     * @param sizeBytes 释放的字节数
     */
    public static void deallocate(String type, long sizeBytes) {
        if (!initialized) {
            return;
        }

        // 更新按类型的统计
        AtomicLong typeUsage = memoryUsageByType.get(type);
        if (typeUsage != null) {
            typeUsage.addAndGet(-sizeBytes);
        }

        // 更新总体统计
        totalGPUMemoryUsed.addAndGet(-sizeBytes);
        totalDeallocations.incrementAndGet();
    }

    /**
     * 触发清理（当接近警告阈值时）
     */
    private static void triggerCleanup() {
        UHDFast.LOGGER.info("GPU Memory Monitor: Triggering cleanup...");
        // 这里可以触发纹理缓存清理、SSBO池清理等
        // 具体实现取决于各个GPU资源管理器
    }

    /**
     * 触发强制清理（当达到严重阈值时）
     */
    private static void triggerAggressiveCleanup() {
        UHDFast.LOGGER.warn("GPU Memory Monitor: Triggering AGGRESSIVE cleanup!");
        // 强制清理所有可释放的GPU资源
    }

    /**
     * 定期报告（每10秒）
     */
    public static void periodicReport() {
        if (!initialized) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastReportTime < reportIntervalMs) {
            return;
        }

        lastReportTime = currentTime;

        long currentMB = totalGPUMemoryUsed.get() / (1024 * 1024);
        long peakMB = peakMemoryUsage.get() / (1024 * 1024);

        UHDFast.LOGGER.info("=== GPU Memory Report ===");
        UHDFast.LOGGER.info("Current usage: {} MB / {} MB", currentMB, maxMemoryMB);
        UHDFast.LOGGER.info("Peak usage: {} MB", peakMB);
        UHDFast.LOGGER.info("Total allocations: {}", totalAllocations.get());
        UHDFast.LOGGER.info("Total deallocations: {}", totalDeallocations.get());

        // 按类型报告
        for (Map.Entry<String, AtomicLong> entry : memoryUsageByType.entrySet()) {
            long typeMB = entry.getValue().get() / (1024 * 1024);
            if (typeMB > 0) {
                AtomicLong count = allocationCountByType.get(entry.getKey());
                UHDFast.LOGGER.info("  {}: {} MB ({} allocations)",
                        entry.getKey(), typeMB, count != null ? count.get() : 0);
            }
        }

        UHDFast.LOGGER.info("Warnings: {}, Critical: {}", warningCount, criticalCount);
        UHDFast.LOGGER.info("========================");
    }

    // ========================================================================
    // 状态查询方法
    // ========================================================================

    public static long getTotalMemoryUsed() {
        return totalGPUMemoryUsed.get();
    }

    public static long getTotalMemoryUsedMB() {
        return totalGPUMemoryUsed.get() / (1024 * 1024);
    }

    public static long getPeakMemoryUsage() {
        return peakMemoryUsage.get();
    }

    public static long getPeakMemoryUsageMB() {
        return peakMemoryUsage.get() / (1024 * 1024);
    }

    public static long getMaxMemoryMB() {
        return maxMemoryMB;
    }

    public static double getUsagePercentage() {
        return (double) totalGPUMemoryUsed.get() / (maxMemoryMB * 1024 * 1024) * 100.0;
    }

    public static long getMemoryByType(String type) {
        AtomicLong usage = memoryUsageByType.get(type);
        return usage != null ? usage.get() : 0;
    }

    public static long getMemoryByTypeMB(String type) {
        return getMemoryByType(type) / (1024 * 1024);
    }

    public static long getAllocationCountByType(String type) {
        AtomicLong count = allocationCountByType.get(type);
        return count != null ? count.get() : 0;
    }

    public static long getTotalAllocations() {
        return totalAllocations.get();
    }

    public static long getTotalDeallocations() {
        return totalDeallocations.get();
    }

    public static int getWarningCount() {
        return warningCount;
    }

    public static int getCriticalCount() {
        return criticalCount;
    }

    public static String getMemoryUsageFormatted() {
        long currentMB = totalGPUMemoryUsed.get() / (1024 * 1024);
        return String.format("%d MB / %d MB (%.1f%%)", currentMB, maxMemoryMB, getUsagePercentage());
    }

    public static String getMemoryByTypeFormatted(String type) {
        long mb = getMemoryByTypeMB(type);
        long count = getAllocationCountByType(type);
        return String.format("%d MB (%d allocs)", mb, count);
    }

    /**
     * 重置统计信息
     */
    public static void resetStats() {
        memoryUsageByType.clear();
        allocationCountByType.clear();
        totalGPUMemoryUsed.set(0);
        totalAllocations.set(0);
        totalDeallocations.set(0);
        peakMemoryUsage.set(0);
        warningCount = 0;
        criticalCount = 0;
    }

    /**
     * 清理监控器
     */
    public static synchronized void cleanup() {
        resetStats();
        initialized = false;
        UHDFast.LOGGER.info("GPU Memory Monitor cleaned up");
    }
}
