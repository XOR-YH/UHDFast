package com.xor.uhdfast.optimizer;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import com.xor.uhdfast.gpu.XeChunkGPUAccelerator;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChunkOptimizer {

    private static final double CHUNK_SIZE = 16.0;

    private static int optimizedChunkRebuildCount = 0;
    private static int gpuAcceleratedChunkCount = 0;
    private static int fallbackChunkCount = 0;
    private static int skippedChunkCount = 0;

    private static final PriorityQueue<ChunkLoadTask> chunkLoadQueue = new PriorityQueue<>();
    private static final ConcurrentLinkedQueue<ChunkLoadResult> chunkResults = new ConcurrentLinkedQueue<>();

    private static final Set<Long> loadedChunkKeys = Collections.synchronizedSet(new HashSet<>());
    private static final Map<Long, Long> chunkLastAccessTime = Collections.synchronizedMap(new HashMap<>());

    public static class ChunkLoadTask implements Comparable<ChunkLoadTask> {
        public final int chunkX;
        public final int chunkZ;
        public final double squaredDistance;
        public final int priority;

        public ChunkLoadTask(int chunkX, int chunkZ, double squaredDistance, int priority) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.squaredDistance = squaredDistance;
            this.priority = priority;
        }

        @Override
        public int compareTo(ChunkLoadTask o) {
            int priorityCompare = Integer.compare(this.priority, o.priority);
            if (priorityCompare != 0)
                return priorityCompare;
            return Double.compare(this.squaredDistance, o.squaredDistance);
        }
    }

    public static class ChunkLoadResult {
        public final int chunkX;
        public final int chunkZ;
        public final boolean success;
        public final long loadTimeNs;

        public ChunkLoadResult(int chunkX, int chunkZ, boolean success, long loadTimeNs) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.success = success;
            this.loadTimeNs = loadTimeNs;
        }
    }

    public static boolean shouldRebuildChunk(int chunkX, int chunkZ, double playerX, double playerZ) {
        if (!UHDFastConfig.enableChunkLoadingOptimization.get()) {
            return true;
        }

        double dx = (chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0) - playerX;
        double dz = (chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0) - playerZ;
        double squaredDistance = dx * dx + dz * dz;

        double maxDist = 128;
        double threshold = maxDist * CHUNK_SIZE * UHDFastConfig.renderDistanceMultiplier.get();
        double squaredThreshold = threshold * threshold;

        if (squaredDistance > squaredThreshold) {
            skippedChunkCount++;
            return false;
        }

        // 近距离 chunk 始终重建（不检查时间）
        // 64^2 = 4096 对应 64 blocks 距离
        if (squaredDistance <= 4096.0) {
            return true;
        }

        // 用平方距离替代 sqrt
        long chunkKey = getChunkKey(chunkX, chunkZ);
        Long lastAccess = chunkLastAccessTime.get(chunkKey);
        if (lastAccess != null) {
            long timeSinceAccess = System.currentTimeMillis() - lastAccess;
            if (timeSinceAccess < 1000 && squaredDistance > 65536.0) { // 256^2 = 65536
                return false;
            }
        }

        chunkLastAccessTime.put(chunkKey, System.currentTimeMillis());
        return true;
    }

    public static boolean shouldRebuildChunkSquared(int chunkX, int chunkZ, double playerX, double playerZ,
            double renderDistanceMultiplier) {
        double dx = (chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0) - playerX;
        double dz = (chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0) - playerZ;
        double squaredDistance = dx * dx + dz * dz;

        double maxDist = 128;
        double threshold = maxDist * CHUNK_SIZE * renderDistanceMultiplier;
        return squaredDistance <= threshold * threshold;
    }

    public static int getOptimalChunkSectionRenderDistance() {
        return Math.max(2, (int) (4 * UHDFastConfig.renderDistanceMultiplier.get()));
    }

    public static void onChunkRebuilt() {
        optimizedChunkRebuildCount++;
        if (optimizedChunkRebuildCount % 100 == 0) {
            UHDFast.LOGGER.debug("Chunk rebuilds optimized: {}", optimizedChunkRebuildCount);
        }
    }

    public static boolean isGPUAccelerationEnabled() {
        return UHDFastConfig.enableXeChunkGPUAcceleration.get()
                && XeChunkGPUAccelerator.isActive();
    }

    public static XeChunkGPUAccelerator.ChunkGPUResult buildChunkWithGPU(byte[] blockData, int chunkX, int chunkZ) {
        if (isGPUAccelerationEnabled()) {
            try {
                XeChunkGPUAccelerator.ChunkGPUResult result = XeChunkGPUAccelerator.buildChunkGPU(blockData, chunkX,
                        chunkZ);
                if (result != null && result.success) {
                    gpuAcceleratedChunkCount++;
                    return result;
                }
                fallbackChunkCount++;
            } catch (Exception e) {
                UHDFast.LOGGER.debug("GPU chunk build failed, falling back to CPU: {}", e.getMessage());
                fallbackChunkCount++;
            }
        }
        return null;
    }

    public static void processPendingChunkResults() {
        if (isGPUAccelerationEnabled()) {
            try {
                XeChunkGPUAccelerator.processPendingResults();
            } catch (Exception e) {
                UHDFast.LOGGER.debug("Failed to process GPU chunk results: {}", e.getMessage());
            }
        }
    }

    public static void enqueueChunkLoad(int chunkX, int chunkZ, double playerX, double playerZ) {
        double dx = (chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0) - playerX;
        double dz = (chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0) - playerZ;
        double squaredDistance = dx * dx + dz * dz;

        int priority = getChunkPriority(squaredDistance);

        synchronized (chunkLoadQueue) {
            chunkLoadQueue.offer(new ChunkLoadTask(chunkX, chunkZ, squaredDistance, priority));
        }
    }

    private static int getChunkPriority(double squaredDistance) {
        // 用平方距离替代 sqrt：64^2=4096, 128^2=16384, 256^2=65536
        if (squaredDistance < 4096.0)
            return 0;
        if (squaredDistance < 16384.0)
            return 1;
        if (squaredDistance < 65536.0)
            return 2;
        return 3;
    }

    public static ChunkLoadTask pollNextChunkToLoad() {
        synchronized (chunkLoadQueue) {
            return chunkLoadQueue.poll();
        }
    }

    public static void submitChunkResult(int chunkX, int chunkZ, boolean success, long loadTimeNs) {
        chunkResults.offer(new ChunkLoadResult(chunkX, chunkZ, success, loadTimeNs));
        long chunkKey = getChunkKey(chunkX, chunkZ);
        if (success) {
            loadedChunkKeys.add(chunkKey);
        }
    }

    public static ChunkLoadResult pollChunkResult() {
        return chunkResults.poll();
    }

    public static int getQueueSize() {
        synchronized (chunkLoadQueue) {
            return chunkLoadQueue.size();
        }
    }

    public static void clearChunkLoadQueue() {
        synchronized (chunkLoadQueue) {
            chunkLoadQueue.clear();
        }
    }

    public static int getGpuAcceleratedChunkCount() {
        return gpuAcceleratedChunkCount;
    }

    public static int getFallbackChunkCount() {
        return fallbackChunkCount;
    }

    public static int getSkippedChunkCount() {
        return skippedChunkCount;
    }

    public static void resetGPUStats() {
        gpuAcceleratedChunkCount = 0;
        fallbackChunkCount = 0;
        skippedChunkCount = 0;
        synchronized (chunkLoadQueue) {
            chunkLoadQueue.clear();
        }
        chunkResults.clear();
        loadedChunkKeys.clear();
        chunkLastAccessTime.clear();
        XeChunkGPUAccelerator.resetStats();
    }

    private static long getChunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}