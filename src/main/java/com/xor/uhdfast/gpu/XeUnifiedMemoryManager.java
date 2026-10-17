package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;

import java.util.HashMap;
import java.util.Map;

public class XeUnifiedMemoryManager {

    private static final Map<String, MemoryAllocation> allocations = new HashMap<>();
    private static long totalAllocated = 0;
    private static long totalFreed = 0;
    private static long peakUsage = 0;
    private static long currentUsage = 0;
    private static int allocationCount = 0;
    private static int deallocationCount = 0;
    private static boolean initialized = false;

    private static final long MAX_UNIFIED_MEMORY = 4L * 1024 * 1024 * 1024;
    private static final long WARNING_THRESHOLD = (long) (MAX_UNIFIED_MEMORY * 0.8);
    private static final long CRITICAL_THRESHOLD = (long) (MAX_UNIFIED_MEMORY * 0.9);

    public static class MemoryAllocation {
        public final String id;
        public final long size;
        public final String type;
        public final long timestamp;
        public boolean freed = false;

        public MemoryAllocation(String id, long size, String type) {
            this.id = id;
            this.size = size;
            this.type = type;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Unified memory manager disabled - not Xe architecture");
            return;
        }

        UHDFast.LOGGER.info("Xe Unified Memory Manager initialized");
        UHDFast.LOGGER.info("  Xe shares system RAM and VRAM - unified memory architecture");
        UHDFast.LOGGER.info("  Max unified memory: {} GB", MAX_UNIFIED_MEMORY / (1024 * 1024 * 1024));
        UHDFast.LOGGER.info("  Warning threshold: {}%", 80);
        UHDFast.LOGGER.info("  Critical threshold: {}%", 90);

        initialized = true;
    }

    public static MemoryAllocation allocate(String id, long size, String type) {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return new MemoryAllocation(id, size, type);
        }

        MemoryAllocation allocation = new MemoryAllocation(id, size, type);
        allocations.put(id, allocation);

        totalAllocated += size;
        currentUsage += size;
        allocationCount++;

        if (currentUsage > peakUsage) {
            peakUsage = currentUsage;
        }

        if (currentUsage > CRITICAL_THRESHOLD) {
            UHDFast.LOGGER.warn("CRITICAL: Unified memory usage at {}% - triggering aggressive cleanup",
                    getUsagePercentage());
            triggerAggressiveCleanup();
        } else if (currentUsage > WARNING_THRESHOLD) {
            UHDFast.LOGGER.warn("WARNING: Unified memory usage at {}% - triggering cleanup",
                    getUsagePercentage());
            triggerCleanup();
        }

        UHDFast.LOGGER.debug("Memory allocated: {} ({} KB, type: {})", id, size / 1024, type);

        return allocation;
    }

    public static void free(String id) {
        MemoryAllocation allocation = allocations.remove(id);
        if (allocation != null && !allocation.freed) {
            allocation.freed = true;
            totalFreed += allocation.size;
            currentUsage -= allocation.size;
            deallocationCount++;

            UHDFast.LOGGER.debug("Memory freed: {} ({} KB)", id, allocation.size / 1024);
        }
    }

    private static void triggerCleanup() {
        int freedCount = 0;
        long freedBytes = 0;

        for (Map.Entry<String, MemoryAllocation> entry : allocations.entrySet()) {
            if (entry.getValue().type.equals("texture") || entry.getValue().type.equals("buffer")) {
                if (System.currentTimeMillis() - entry.getValue().timestamp > 30000) {
                    free(entry.getKey());
                    freedCount++;
                    freedBytes += entry.getValue().size;
                }
            }
        }

        if (freedCount > 0) {
            UHDFast.LOGGER.info("Cleanup freed {} allocations ({} KB)", freedCount, freedBytes / 1024);
        }
    }

    private static void triggerAggressiveCleanup() {
        int freedCount = 0;
        long freedBytes = 0;

        for (Map.Entry<String, MemoryAllocation> entry : new HashMap<>(allocations).entrySet()) {
            if (System.currentTimeMillis() - entry.getValue().timestamp > 10000) {
                free(entry.getKey());
                freedCount++;
                freedBytes += entry.getValue().size;
            }
        }

        if (freedCount > 0) {
            UHDFast.LOGGER.warn("Aggressive cleanup freed {} allocations ({} KB)", freedCount, freedBytes / 1024);
        }
    }

    public static long getTotalAllocated() {
        return totalAllocated;
    }

    public static long getTotalFreed() {
        return totalFreed;
    }

    public static long getPeakUsage() {
        return peakUsage;
    }

    public static long getCurrentUsage() {
        return currentUsage;
    }

    public static String getCurrentUsageFormatted() {
        double mb = currentUsage / (1024.0 * 1024.0);
        if (mb >= 1024) {
            return String.format("%.2f GB", mb / 1024.0);
        }
        return String.format("%.2f MB", mb);
    }

    public static double getUsagePercentage() {
        return (double) currentUsage / MAX_UNIFIED_MEMORY * 100.0;
    }

    public static int getAllocationCount() {
        return allocationCount;
    }

    public static int getDeallocationCount() {
        return deallocationCount;
    }

    public static int getActiveAllocationCount() {
        return allocations.size();
    }

    public static String getMemoryEfficiencyFormatted() {
        if (totalAllocated == 0) {
            return "0%";
        }
        double efficiency = (double) totalFreed / totalAllocated * 100.0;
        return String.format("%.1f%%", efficiency);
    }

    public static void resetStats() {
        allocations.clear();
        totalAllocated = 0;
        totalFreed = 0;
        peakUsage = 0;
        currentUsage = 0;
        allocationCount = 0;
        deallocationCount = 0;
    }
}
