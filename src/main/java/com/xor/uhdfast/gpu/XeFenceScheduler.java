package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.shaderpack.ShaderPackManager;
import org.lwjgl.opengl.*;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xe Fence Scheduler - async GPU operation scheduling with fence
 * synchronization.
 *
 * Based on Intel oneAPI optimization best practices:
 * - "Asynchronous and Overlapping Data Transfers"
 * - "Host/Device Coordination"
 *
 * Uses OpenGL fence sync objects to:
 * 1. Track GPU completion without blocking CPU
 * 2. Overlap multiple chunk generations
 * 3. Defer result readback until GPU is done
 * 4. Implement work stealing between GPU and CPU
 *
 * Key benefit: CPU can prepare next chunk while GPU computes current one.
 */
public class XeFenceScheduler {

    // ========================================================================
    // GPU task representation
    // ========================================================================

    /**
     * Represents a pending GPU chunk generation task.
     */
    public static class GpuTask {
        public final int chunkX;
        public final int chunkZ;
        public final long seed;
        public final float seaLevel;
        public final CompletableFuture<Void> future;
        public long fence = 0;
        public long submitTimeNs;

        public GpuTask(int chunkX, int chunkZ, long seed, float seaLevel) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.seed = seed;
            this.seaLevel = seaLevel;
            this.future = new CompletableFuture<>();
        }
    }

    // ========================================================================
    // Task queue and processing
    // ========================================================================

    /** Maximum pending GPU tasks (prevents memory exhaustion) - 根据EU数量动态调整 */
    private static int MAX_PENDING = 128;

    /** Maximum concurrent GPU tasks - 根据EU数量动态调整 */
    private static int MAX_CONCURRENT = 8;

    /** 光影模式下保存的原始 MAX_CONCURRENT，用于退出光影模式时恢复；0 表示未进入光影模式 */
    private static int originalMaxConcurrent = 0;

    /** Task queue for pending GPU work */
    private static final Queue<GpuTask> taskQueue = new ConcurrentLinkedQueue<>();

    /** Tasks submitted to GPU, waiting for fence sync */
    private static final Queue<GpuTask> pendingTasks = new ConcurrentLinkedQueue<>();

    /** Completed tasks ready for result pickup */
    private static final Queue<GpuTask> completedTasks = new ConcurrentLinkedQueue<>();

    /** Worker thread for processing task queue */
    private static volatile Thread workerThread;
    private static volatile boolean running = false;

    /** Statistics */
    private static final AtomicInteger totalSubmitted = new AtomicInteger(0);
    private static final AtomicInteger totalCompleted = new AtomicInteger(0);
    private static final AtomicInteger totalRejected = new AtomicInteger(0);
    private static final AtomicInteger totalFailed = new AtomicInteger(0);
    private static final AtomicInteger maxConcurrent = new AtomicInteger(0);
    private static volatile long totalGpuTimeNs = 0;

    // ========================================================================
    // Task completion callback
    // ========================================================================

    /**
     * Callback interface for GPU task completion notification.
     */
    @FunctionalInterface
    public interface TaskCompletionCallback {
        /**
         * Called when a GPU task completes.
         *
         * @param task    the completed task
         * @param success true if the task completed successfully, false if it failed
         */
        void onTaskCompleted(GpuTask task, boolean success);
    }

    /** Registered callback listeners */
    private static final List<TaskCompletionCallback> callbacks = new CopyOnWriteArrayList<>();

    /**
     * Register a callback listener for task completion notifications.
     *
     * @param callback the callback to register
     */
    public static void addCallback(TaskCompletionCallback callback) {
        if (callback != null) {
            callbacks.add(callback);
        }
    }

    /**
     * Remove a previously registered callback listener.
     *
     * @param callback the callback to remove
     */
    public static void removeCallback(TaskCompletionCallback callback) {
        callbacks.remove(callback);
    }

    /**
     * Notify all registered callbacks that a task has completed.
     */
    private static void notifyCallbacks(GpuTask task, boolean success) {
        for (TaskCompletionCallback callback : callbacks) {
            try {
                callback.onTaskCompleted(task, success);
            } catch (Exception e) {
                UHDFast.LOGGER.debug("XeFenceScheduler: Callback error: {}", e.getMessage());
            }
        }
    }

    private static volatile long minGpuTimeNs = Long.MAX_VALUE;
    private static volatile long maxGpuTimeNs = 0;
    private static volatile long lastGpuTimeNs = 0;

    /**
     * Initialize the fence scheduler.
     * Dynamically configures MAX_PENDING and MAX_CONCURRENT based on GPU EU count.
     */
    public static synchronized void initialize() {
        if (running)
            return;

        // 根据EU数量动态调整并发参数
        int euCount = IntelXeDetector.getEUCount();
        if (euCount > 0) {
            MAX_PENDING = euCount * 2;
            MAX_CONCURRENT = Math.max(4, euCount / 8);
            UHDFast.LOGGER.info("XeFenceScheduler: Configured for {} EUs - MAX_PENDING={}, MAX_CONCURRENT={}",
                    euCount, MAX_PENDING, MAX_CONCURRENT);
        }

        running = true;
        workerThread = new Thread(XeFenceScheduler::processLoop, "XeFenceScheduler");
        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.NORM_PRIORITY - 1); // Lower priority than game thread
        workerThread.start();

        UHDFast.LOGGER.info("XeFenceScheduler: Started (max pending: {}, max concurrent: {})", MAX_PENDING,
                MAX_CONCURRENT);
    }

    /**
     * Submit a chunk generation task to the GPU.
     * Returns immediately with a CompletableFuture.
     *
     * @return CompletableFuture that will be completed when GPU finishes
     */
    public static CompletableFuture<Void> submitTask(
            int chunkX, int chunkZ, long seed, float seaLevel) {

        if (!running) {
            initialize();
        }

        // Check queue limit
        if (taskQueue.size() + pendingTasks.size() >= MAX_PENDING) {
            totalRejected.incrementAndGet();
            // Return null to indicate rejection (caller should fallback to CPU)
            return CompletableFuture.completedFuture(null);
        }

        GpuTask task = new GpuTask(chunkX, chunkZ, seed, seaLevel);
        taskQueue.offer(task);
        totalSubmitted.incrementAndGet();

        // Update max concurrent
        int current = taskQueue.size() + pendingTasks.size();
        maxConcurrent.updateAndGet(max -> Math.max(max, current));

        return task.future;
    }

    /**
     * Process tasks in a loop.
     * Runs on worker thread.
     */
    private static void processLoop() {
        while (running) {
            try {
                // Step 1: Check for completed GPU tasks
                checkCompletedTasks();

                // Step 2: Submit new tasks if GPU is ready
                submitPendingTasks();

                // Small sleep to avoid busy waiting
                Thread.sleep(1);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                UHDFast.LOGGER.debug("XeFenceScheduler: Process error: {}", e.getMessage());
            }
        }
    }

    /**
     * Check fence sync objects for completed tasks.
     * 优化：使用缓存 IntBuffer 避免每帧分配
     */
    private static final IntBuffer cachedFenceStatus = MemoryUtil.memAllocInt(1).put(0).position(0);

    private static void checkCompletedTasks() {
        GpuTask task;
        while ((task = pendingTasks.peek()) != null) {
            if (task.fence == 0) {
                // Task submitted but no fence (shouldn't happen)
                pendingTasks.poll();
                continue;
            }

            cachedFenceStatus.clear();
            ARBSync.glGetSynci(task.fence, ARBSync.GL_SYNC_STATUS, cachedFenceStatus);
            int status = cachedFenceStatus.get(0);
            if (status == ARBSync.GL_SIGNALED) {
                // GPU finished
                ARBSync.glDeleteSync(task.fence);
                task.fence = 0;

                pendingTasks.poll();

                long gpuTime = System.nanoTime() - task.submitTimeNs;
                totalGpuTimeNs += gpuTime;
                lastGpuTimeNs = gpuTime;
                if (gpuTime < minGpuTimeNs) {
                    minGpuTimeNs = gpuTime;
                }
                if (gpuTime > maxGpuTimeNs) {
                    maxGpuTimeNs = gpuTime;
                }

                // Read back results
                boolean readBackSuccess = readBackResult(task);

                completedTasks.offer(task);
                totalCompleted.incrementAndGet();

                // Notify callbacks of completion
                notifyCallbacks(task, readBackSuccess);
            } else {
                // Not done yet, stop checking (FIFO order)
                break;
            }
        }
    }

    /**
     * Submit queued tasks to GPU.
     */
    private static void submitPendingTasks() {
        // 光影启用时降低 MAX_CONCURRENT，禁用时恢复（仅在状态变化时执行实际调整）
        applyShaderPackMode(ShaderPackManager.getInstance().isShaderPackLoaded());

        // Limit concurrent GPU tasks based on MAX_CONCURRENT
        if (pendingTasks.size() >= MAX_CONCURRENT) {
            return; // Don't overload GPU
        }

        GpuTask task = taskQueue.poll();
        if (task == null)
            return;

        // Submit to GPU (on main thread context - simplified, real impl needs context
        // sharing)
        submitToGpu(task);
        pendingTasks.offer(task);
    }

    /**
     * Submit task to GPU.
     * Must be called on thread with GL context.
     */
    private static void submitToGpu(GpuTask task) {
        long startTime = System.nanoTime();

        try {
            // Use optimized terrain shader if available
            int program = XeShaderOptimizer.getOptimizedTerrainProgram();
            if (program == 0) {
                // Fallback to original shader (would need XeChunkLoader access)
                task.future.complete(null);
                return;
            }

            // Upload data
            XePersistentMemoryManager.uploadUniforms(task.seed, task.chunkX, task.chunkZ, 0, task.seaLevel);
            XePersistentMemoryManager.clearFaceCounter();

            // Bind and dispatch
            GL20.glUseProgram(program);
            XePersistentMemoryManager.bindTerrainGenSSBOs();

            // Dispatch compute (optimized: 16x16x4 workgroup)
            GL43.glDispatchCompute(1, 20, 5); // 320/16 = 20 Y, 16/4 = 4 Z

            // Memory barrier (optimized: use specific barrier bits)
            MemoryBarrierOptimizer.barrierSSBO();

            // Insert fence sync
            task.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            task.submitTimeNs = startTime;

        } catch (Exception e) {
            UHDFast.LOGGER.warn("XeFenceScheduler: GPU submit failed for ({}, {}): {}",
                    task.chunkX, task.chunkZ, e.getMessage());
            task.fence = 0;
            task.future.complete(null);
            totalFailed.incrementAndGet();
            // Notify callbacks of failure
            notifyCallbacks(task, false);
        }
    }

    /**
     * Read back result from GPU.
     * 
     * @return true if readback succeeded, false otherwise
     */
    private static boolean readBackResult(GpuTask task) {
        try {
            // Simplified: just complete the future without downloading results
            // since XeChunkLoader was deleted
            task.future.complete(null);
            return true;
        } catch (Exception e) {
            UHDFast.LOGGER.warn("XeFenceScheduler: Readback failed for ({}, {}): {}",
                    task.chunkX, task.chunkZ, e.getMessage());
            task.future.complete(null);
            totalFailed.incrementAndGet();
            return false;
        }
    }

    /**
     * Wait for all pending tasks to complete.
     * Blocking - used during cleanup or world save.
     */
    public static void waitForAll() {
        // Wait for task queue to drain
        while (!taskQueue.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }

        // Wait for pending GPU tasks
        while (!pendingTasks.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Get number of pending GPU tasks.
     */
    public static int getPendingTaskCount() {
        return pendingTasks.size();
    }

    /**
     * Get total number of tasks in queue.
     */
    public static int getQueuedTaskCount() {
        return taskQueue.size();
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    public static int getTotalSubmitted() {
        return totalSubmitted.get();
    }

    public static int getTotalCompleted() {
        return totalCompleted.get();
    }

    public static int getTotalRejected() {
        return totalRejected.get();
    }

    public static int getTotalFailed() {
        return totalFailed.get();
    }

    public static int getMaxConcurrent() {
        return maxConcurrent.get();
    }

    /**
     * Get current max concurrent value (alias for getMaxConcurrent).
     */
    public static int getCurrentMaxConcurrent() {
        return maxConcurrent.get();
    }

    /**
     * Set max concurrent GPU tasks (used by GPULoadBalancer).
     */
    public static void setMaxConcurrent(int newMax) {
        MAX_CONCURRENT = newMax;
    }

    /**
     * 应用光影模式 - 光影启用时降低 MAX_CONCURRENT 以避免与光影渲染争用 GPU 资源，
     * 光影禁用时恢复原始值。
     * <p>
     * 仅在状态变化时执行实际调整，避免重复日志。
     * </p>
     *
     * @param shaderActive 光影是否启用
     */
    public static void applyShaderPackMode(boolean shaderActive) {
        if (shaderActive) {
            // 进入光影模式：保存原始 MAX_CONCURRENT 并减半（不低于 2）
            if (originalMaxConcurrent == 0) {
                originalMaxConcurrent = MAX_CONCURRENT;
                int reduced = Math.max(2, originalMaxConcurrent / 2);
                MAX_CONCURRENT = reduced;
                UHDFast.LOGGER.info(
                        "XeFenceScheduler: Shader pack active - MAX_CONCURRENT reduced {} -> {} (avoid shader contention)",
                        originalMaxConcurrent, reduced);
            }
        } else {
            // 退出光影模式：恢复原始 MAX_CONCURRENT
            if (originalMaxConcurrent > 0) {
                int restored = originalMaxConcurrent;
                MAX_CONCURRENT = restored;
                UHDFast.LOGGER.info("XeFenceScheduler: Shader pack inactive - MAX_CONCURRENT restored to {}",
                        restored);
                originalMaxConcurrent = 0;
            }
        }
    }

    public static double getAverageGpuTimeMs() {
        int c = totalCompleted.get();
        return c == 0 ? 0 : (double) totalGpuTimeNs / c / 1_000_000.0;
    }

    public static long getMinGpuTimeNs() {
        return minGpuTimeNs == Long.MAX_VALUE ? 0 : minGpuTimeNs;
    }

    public static long getMaxGpuTimeNs() {
        return maxGpuTimeNs;
    }

    /**
     * Get minimum GPU task execution time in milliseconds.
     */
    public static double getMinGpuTimeMs() {
        return getMinGpuTimeNs() / 1_000_000.0;
    }

    /**
     * Get maximum GPU task execution time in milliseconds.
     */
    public static double getMaxGpuTimeMs() {
        return getMaxGpuTimeNs() / 1_000_000.0;
    }

    public static long getLastGpuTimeNs() {
        return lastGpuTimeNs;
    }

    public static String getSchedulerStats() {
        return String.format("FenceScheduler: submitted=%d completed=%d rejected=%d failed=%d pending=%d/%d " +
                "maxConcurrent=%d avgGpuTime=%.2fms",
                totalSubmitted.get(), totalCompleted.get(), totalRejected.get(), totalFailed.get(),
                pendingTasks.size(), taskQueue.size(),
                maxConcurrent.get(), getAverageGpuTimeMs());
    }

    /**
     * Get detailed statistics as a structured object.
     */
    public static Statistics getStatistics() {
        return new Statistics(
                taskQueue.size(),
                pendingTasks.size(),
                completedTasks.size(),
                totalSubmitted.get(),
                totalCompleted.get(),
                totalRejected.get(),
                totalFailed.get(),
                maxConcurrent.get(),
                getAverageGpuTimeMs(),
                getMinGpuTimeNs() / 1_000_000.0,
                maxGpuTimeNs / 1_000_000.0,
                lastGpuTimeNs / 1_000_000.0);
    }

    public static void resetStats() {
        totalSubmitted.set(0);
        totalCompleted.set(0);
        totalRejected.set(0);
        totalFailed.set(0);
        maxConcurrent.set(0);
        totalGpuTimeNs = 0;
        minGpuTimeNs = Long.MAX_VALUE;
        maxGpuTimeNs = 0;
        lastGpuTimeNs = 0;
    }

    /**
     * Statistics data class for monitoring.
     */
    public static class Statistics {
        public final int currentQueueSize;
        public final int pendingTaskCount;
        public final int completedTaskCount;
        public final int totalSubmitted;
        public final int totalCompleted;
        public final int totalRejected;
        public final int totalFailed;
        public final int maxConcurrent;
        public final double averageTaskTimeMs;
        public final double minTaskTimeMs;
        public final double maxTaskTimeMs;
        public final double lastTaskTimeMs;

        public Statistics(int currentQueueSize, int pendingTaskCount, int completedTaskCount,
                int totalSubmitted, int totalCompleted, int totalRejected, int totalFailed,
                int maxConcurrent, double averageTaskTimeMs, double minTaskTimeMs,
                double maxTaskTimeMs, double lastTaskTimeMs) {
            this.currentQueueSize = currentQueueSize;
            this.pendingTaskCount = pendingTaskCount;
            this.completedTaskCount = completedTaskCount;
            this.totalSubmitted = totalSubmitted;
            this.totalCompleted = totalCompleted;
            this.totalRejected = totalRejected;
            this.totalFailed = totalFailed;
            this.maxConcurrent = maxConcurrent;
            this.averageTaskTimeMs = averageTaskTimeMs;
            this.minTaskTimeMs = minTaskTimeMs;
            this.maxTaskTimeMs = maxTaskTimeMs;
            this.lastTaskTimeMs = lastTaskTimeMs;
        }

        @Override
        public String toString() {
            return String.format(
                    "Statistics{queue=%d, pending=%d, completed=%d, submitted=%d, " +
                            "rejected=%d, failed=%d, maxConcurrent=%d, " +
                            "avgTime=%.2fms, minTime=%.2fms, maxTime=%.2fms, lastTime=%.2fms}",
                    currentQueueSize, pendingTaskCount, completedTaskCount, totalSubmitted,
                    totalRejected, totalFailed, maxConcurrent,
                    averageTaskTimeMs, minTaskTimeMs, maxTaskTimeMs, lastTaskTimeMs);
        }
    }

    /**
     * Cleanup scheduler.
     */
    public static synchronized void cleanup() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(1000);
            } catch (InterruptedException e) {
                // ignore
            }
        }

        // Delete any remaining fences
        for (GpuTask task : pendingTasks) {
            if (task.fence != 0) {
                ARBSync.glDeleteSync(task.fence);
                task.fence = 0;
            }
        }

        taskQueue.clear();
        pendingTasks.clear();
        completedTasks.clear();

        UHDFast.LOGGER.info("XeFenceScheduler: Cleaned up");
    }
}
