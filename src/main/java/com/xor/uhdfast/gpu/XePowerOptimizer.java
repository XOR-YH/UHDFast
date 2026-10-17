package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import org.lwjgl.opengl.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Xe Power Optimizer - Comprehensive GPU power and performance management
 * for chunk loading pipeline.
 * 
 * Implements 6 core optimization strategies:
 * 
 * 1. Adaptive Frame Budget - Limits GPU work per frame based on target FPS,
 * preventing chunk generation from stealing GPU time from rendering.
 * 
 * 2. GPU Utilization Feedback Loop - Continuously monitors actual GPU usage
 * via OpenGL timing queries and dynamically adjusts dispatch group sizes,
 * batch counts, and work distribution.
 * 
 * 3. GPU/CPU Work Stealing - When GPU is saturated, automatically migrates
 * lower-priority chunk generation tasks to CPU worker threads. When GPU
 * is idle, pulls work back to GPU for maximum throughput.
 * 
 * 4. Temporal Spreading - Distributes chunk generation workload across
 * multiple frames instead of doing everything in one burst. Maintains
 * smooth frame pacing even during heavy loading periods.
 * 
 * 5. Shader Dispatch Optimization - Coalesces small dispatches into larger
 * ones, eliminates redundant state changes, and uses dynamic work group
 * sizing to match the actual chunk count.
 * 
 * 6. Power Saving Mode - When GPU usage exceeds configurable thresholds
 * for sustained periods, enters progressive power saving stages that
 * reduce dispatch frequency, lower quality levels, and defer non-critical work.
 */
public class XePowerOptimizer {

    // =========================================================================
    // Power Mode States
    // =========================================================================

    public enum PowerMode {
        /** Full performance - GPU idle, all work on GPU */
        FULL_PERFORMANCE(1.0f, 1.0f, 0, "Full"),
        /** Balanced - Normal operation with mild throttling */
        BALANCED(0.8f, 0.85f, 1, "Balanced"),
        /** Power saving - Reduced dispatch size, some work on CPU */
        POWER_SAVE(0.5f, 0.6f, 2, "PowerSave"),
        /** Ultra power saving - Minimal GPU work, most on CPU */
        ULTRA_POWER_SAVE(0.25f, 0.35f, 3, "UltraSave"),
        /** Emergency - GPU work paused, all fallback to CPU */
        EMERGENCY(0.0f, 0.0f, 4, "Emergency");

        public final float gpuWorkFactor;
        public final float dispatchSizeFactor;
        public final int level;
        public final String name;

        PowerMode(float gpuWorkFactor, float dispatchSizeFactor, int level, String name) {
            this.gpuWorkFactor = gpuWorkFactor;
            this.dispatchSizeFactor = dispatchSizeFactor;
            this.level = level;
            this.name = name;
        }

        public PowerMode nextSaver() {
            int idx = Math.min(level + 1, values().length - 1);
            return values()[idx];
        }

        public PowerMode nextFaster() {
            int idx = Math.max(level - 1, 0);
            return values()[idx];
        }
    }

    // =========================================================================
    // Adaptive Frame Budget
    // =========================================================================

    /** Target frame time in nanoseconds (derived from target FPS) */
    private static volatile long targetFrameTimeNs = 16_666_666L; // 60 FPS default

    /** Maximum GPU work time per frame in nanoseconds */
    private static volatile long maxGpuWorkPerFrameNs = 10_000_000L; // 10ms = 60% of frame (FPS priority)

    /** Actual GPU work time used in current frame */
    private static volatile long currentFrameGpuWorkNs = 0;

    /** Number of chunks dispatched in current frame */
    private static volatile int chunksDispatchedThisFrame = 0;

    /** Maximum chunks allowed per frame (adaptive) */
    private static volatile int maxChunksPerFrame = 32;

    /** Frame boundary tracking */
    private static volatile long lastFrameStartTime = System.nanoTime();
    private static volatile int currentFrameNumber = 0;

    // =========================================================================
    // GPU Utilization Feedback Loop
    // =========================================================================

    /** PID controller for GPU utilization feedback */
    private static volatile double pidErrorAccumulator = 0;
    private static volatile double pidLastError = 0;
    private static final double PID_Kp = 0.02;
    private static final double PID_Ki = 0.005;
    private static final double PID_Kd = 0.01;

    /** Measured GPU utilization (0.0 - 1.0) */
    private static volatile double measuredGpuUtilization = 0.0;

    /** Target GPU utilization (0.0 - 1.0) - 提升到 95% */
    private static volatile double targetGpuUtilization = 0.95;

    /** Adaptive dispatch size multiplier (PID output) */
    private static volatile double dispatchSizeMultiplier = 1.0;

    /** Feedback loop update interval in frames */
    private static final int FEEDBACK_UPDATE_INTERVAL = 30;
    private static volatile int framesSinceLastFeedback = 0;

    /** OpenGL timestamp query IDs for precise GPU timing */
    private static volatile int[] gpuTimeQueries = new int[2];
    private static volatile int queryBufferIndex = 0;
    private static volatile boolean queriesInitialized = false;

    /** Rolling history of GPU dispatch times */
    private static final double[] gpuDispatchHistory = new double[64];
    private static volatile int historyIndex = 0;
    private static volatile int historyCount = 0;

    // =========================================================================
    // Work Stealing (GPU <-> CPU)
    // =========================================================================

    /** CPU work stealing executor */
    private static ExecutorService cpuWorkStealingExecutor;

    /** Queue for work items stolen from GPU */
    private static final ConcurrentLinkedQueue<StolenWorkItem> stolenWorkQueue = new ConcurrentLinkedQueue<>();

    /** Counter for work stealing events */
    private static final AtomicInteger workStealCount = new AtomicInteger(0);
    private static final AtomicInteger workReturnCount = new AtomicInteger(0);
    private static final AtomicInteger cpuCompletedTasks = new AtomicInteger(0);

    /** Threshold for triggering work stealing (GPU utilization %) */
    private static volatile double workStealThreshold = 85.0;

    /** Threshold for returning work to GPU (GPU utilization %) */
    private static volatile double workReturnThreshold = 60.0;

    /** Maximum pending stolen work items */
    private static final int MAX_STOLEN_WORK = 64;

    // =========================================================================
    // Temporal Spreading
    // =========================================================================

    /** Spread factor - how many frames to spread work across */
    private static volatile int temporalSpreadFrames = 3;

    /** Current spread frame index */
    private static volatile int currentSpreadFrame = 0;

    /** Pending work queue for temporal spreading */
    private static final Queue<TemporalWorkItem> temporalWorkQueue = new ConcurrentLinkedQueue<>();

    /** Maximum pending temporal work items */
    private static final int MAX_TEMPORAL_WORK = 128;

    /** Temporal spreading statistics */
    private static final AtomicInteger spreadFrameCount = new AtomicInteger(0);
    private static final AtomicInteger spreadItemsProcessed = new AtomicInteger(0);
    private static final AtomicInteger spreadItemsDeferred = new AtomicInteger(0);

    // =========================================================================
    // Shader Dispatch Optimization
    // =========================================================================

    /** Cached optimal dispatch dimensions */
    private static volatile int cachedDispatchX = 1;
    private static volatile int cachedDispatchY = 1;
    private static volatile int cachedDispatchZ = 1;

    /** Last chunk count for dispatch optimization */
    private static volatile int lastOptimizedChunkCount = 0;

    /** Dispatch coalescing - minimum chunks before batched dispatch */
    private static volatile int minBatchThreshold = 2;

    /** Avoid redundant dispatch if chunk count changed < 10% */
    private static final float REDUNDANT_DISPATCH_THRESHOLD = 0.1f;

    /** Dispatch skip counter */
    private static final AtomicInteger skippedDispatches = new AtomicInteger(0);

    // =========================================================================
    // Power Saving Mode
    // =========================================================================

    /** Current power mode */
    private static volatile PowerMode currentPowerMode = PowerMode.FULL_PERFORMANCE;

    /** GPU usage samples for sustained monitoring */
    private static final double[] gpuUsageSamples = new double[120];
    private static volatile int sampleIndex = 0;
    private static volatile int sampleCount = 0;

    /** Sustained high usage counter (frames) */
    private static volatile int sustainedHighUsageFrames = 0;

    /** Sustained low usage counter (frames) */
    private static volatile int sustainedLowUsageFrames = 0;

    /** Frames to wait before escalating power saving */
    private static volatile int powerSaveEscalationFrames = 60;

    /** Frames to wait before reducing power saving */
    private static volatile int powerSaveDeescalationFrames = 120;

    /** Power mode transition statistics */
    private static final AtomicInteger modeTransitionCount = new AtomicInteger(0);

    /** Emergency pause flag */
    private static volatile boolean emergencyPause = false;
    private static volatile long emergencyPauseStartTime = 0;
    private static volatile long emergencyPauseDurationMs = 2000;

    // =========================================================================
    // Statistics
    // =========================================================================

    private static final AtomicLong totalGpuWorkTimeNs = new AtomicLong(0);
    private static final AtomicLong totalCpuWorkTimeNs = new AtomicLong(0);
    private static final AtomicLong totalIdleTimeNs = new AtomicLong(0);
    private static final AtomicInteger totalFramesProcessed = new AtomicInteger(0);
    private static final AtomicInteger totalChunksGenerated = new AtomicInteger(0);
    private static final AtomicInteger totalBatchesCoalesced = new AtomicInteger(0);

    // =========================================================================
    // Configuration
    // =========================================================================

    private static volatile boolean enabled = true;
    private static volatile boolean initialized = false;
    private static volatile int targetFPS = 90;
    private static volatile double gpuUtilizationTarget = 0.95; // 从 0.75 提升到 0.95
    private static volatile int frameBudgetPercent = 50; // GPU work % of frame budget - 从 20 提升到 50
    private static volatile boolean temporalSpreadingEnabled = true;
    private static volatile boolean workStealingEnabled = true;
    private static volatile boolean dispatchOptimizationEnabled = true;
    private static volatile boolean powerSavingEnabled = true;

    // =========================================================================
    // Initialization
    // =========================================================================

    /**
     * Initialize the Xe Power Optimizer.
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        // Read configuration from UHDFastConfig
        enabled = UHDFastConfig.enableXePowerOptimizer.get();
        targetFPS = UHDFastConfig.gpuThrottleTargetFPS.get();
        gpuUtilizationTarget = UHDFastConfig.xePowerOptimizerGpuUtilizationTarget.get() / 100.0;
        frameBudgetPercent = UHDFastConfig.xePowerOptimizerFrameBudgetPercent.get();
        temporalSpreadFrames = UHDFastConfig.xePowerOptimizerTemporalSpreadFrames.get();
        workStealThreshold = UHDFastConfig.xePowerOptimizerWorkStealThreshold.get();
        workReturnThreshold = UHDFastConfig.xePowerOptimizerWorkReturnThreshold.get();
        powerSaveEscalationFrames = UHDFastConfig.xePowerOptimizerPowerSaveEscalationFrames.get();
        powerSaveDeescalationFrames = UHDFastConfig.xePowerOptimizerPowerSaveDeescalationFrames.get();
        emergencyPauseDurationMs = UHDFastConfig.xePowerOptimizerEmergencyPauseDurationMs.get();
        maxChunksPerFrame = UHDFastConfig.xePowerOptimizerMaxChunksPerFrame.get();
        minBatchThreshold = UHDFastConfig.xePowerOptimizerMinBatchThreshold.get();
        targetGpuUtilization = gpuUtilizationTarget;

        // Calculate target frame time
        targetFrameTimeNs = (long) (1_000_000_000L / targetFPS);
        maxGpuWorkPerFrameNs = (long) (targetFrameTimeNs * frameBudgetPercent / 100.0);

        // Initialize CPU work stealing executor
        int cpuWorkers = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        cpuWorkStealingExecutor = new ThreadPoolExecutor(
                cpuWorkers, cpuWorkers,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "XePowerOptimizer-CPUWorker");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 2);
                    return t;
                });

        // Initialize OpenGL timestamp queries if available
        initGpuTimingQueries();

        initialized = true;

        UHDFast.LOGGER.info("XePowerOptimizer: Initialized");
        UHDFast.LOGGER.info("  Enabled: {}", enabled);
        UHDFast.LOGGER.info("  Target FPS: {} (frame budget: {}ms)", targetFPS, maxGpuWorkPerFrameNs / 1_000_000.0);
        UHDFast.LOGGER.info("  GPU utilization target: {}%", (int) (gpuUtilizationTarget * 100));
        UHDFast.LOGGER.info("  Temporal spreading: {} frames", temporalSpreadFrames);
        UHDFast.LOGGER.info("  Work stealing: enabled (threshold: {}%)", (int) workStealThreshold);
        UHDFast.LOGGER.info("  Dispatch optimization: enabled");
        UHDFast.LOGGER.info("  Power saving: enabled");
        UHDFast.LOGGER.info("  CPU worker threads: {}", cpuWorkers);
    }

    /**
     * Check if the optimizer is enabled. Initializes if not already.
     */
    public static boolean isEnabled() {
        if (!initialized) {
            initialize();
        }
        return enabled;
    }

    /**
     * Initialize OpenGL GPU timing queries for precise utilization measurement.
     */
    private static void initGpuTimingQueries() {
        try {
            gpuTimeQueries[0] = GL33.glGenQueries();
            gpuTimeQueries[1] = GL33.glGenQueries();
            queriesInitialized = true;
        } catch (Exception e) {
            UHDFast.LOGGER.debug("XePowerOptimizer: GPU timing queries not available: {}", e.getMessage());
            queriesInitialized = false;
        }
    }

    // =========================================================================
    // Public API: Frame Budget Management
    // =========================================================================

    /**
     * Start a new frame budget period. Call at the beginning of each frame.
     * Resets per-frame counters and checks temporal spreading queue.
     */
    public static void beginFrame() {
        if (!enabled)
            return;

        long now = System.nanoTime();
        currentFrameNumber++;
        currentFrameGpuWorkNs = 0;
        chunksDispatchedThisFrame = 0;
        lastFrameStartTime = now;

        // Update frame number for temporal spreading
        if (temporalSpreadingEnabled) {
            currentSpreadFrame = currentFrameNumber % temporalSpreadFrames;
            processTemporalQueue();
        }

        // Update feedback loop periodically
        framesSinceLastFeedback++;
        if (framesSinceLastFeedback >= FEEDBACK_UPDATE_INTERVAL) {
            framesSinceLastFeedback = 0;
            updateFeedbackLoop();
        }

        // Check for sustained high/low usage and adjust power mode
        if (powerSavingEnabled) {
            updatePowerMode();
        }

        // Check emergency pause
        if (emergencyPause) {
            long elapsed = System.currentTimeMillis() - emergencyPauseStartTime;
            if (elapsed >= emergencyPauseDurationMs) {
                emergencyPause = false;
                UHDFast.LOGGER.debug("XePowerOptimizer: Emergency pause lifted after {}ms", elapsed);
            }
        }

        totalFramesProcessed.incrementAndGet();
    }

    /**
     * Check if there is remaining GPU budget for more work this frame.
     * 
     * @return true if more GPU work can be dispatched this frame
     */
    public static boolean hasFrameBudget() {
        if (!enabled)
            return true;
        if (emergencyPause)
            return false;

        // Check GPU work time budget
        if (currentFrameGpuWorkNs >= maxGpuWorkPerFrameNs) {
            return false;
        }

        // Check chunk count budget
        if (chunksDispatchedThisFrame >= maxChunksPerFrame) {
            return false;
        }

        // Check power mode restrictions
        if (currentPowerMode == PowerMode.EMERGENCY) {
            return false;
        }

        return true;
    }

    /**
     * Get remaining GPU work budget for current frame in nanoseconds.
     */
    public static long getRemainingFrameBudgetNs() {
        if (!enabled)
            return Long.MAX_VALUE;
        return Math.max(0, maxGpuWorkPerFrameNs - currentFrameGpuWorkNs);
    }

    /**
     * Record GPU work completed. Updates frame budget tracking.
     * 
     * @param workTimeNs Time spent on GPU work in nanoseconds
     * @param chunkCount Number of chunks processed
     */
    public static void recordGpuWork(long workTimeNs, int chunkCount) {
        currentFrameGpuWorkNs += workTimeNs;
        chunksDispatchedThisFrame += chunkCount;
        totalGpuWorkTimeNs.addAndGet(workTimeNs);
        totalChunksGenerated.addAndGet(chunkCount);

        // Record GPU dispatch time in rolling history
        synchronized (gpuDispatchHistory) {
            gpuDispatchHistory[historyIndex % gpuDispatchHistory.length] = workTimeNs / 1_000_000.0;
            historyIndex++;
            historyCount = Math.min(historyCount + 1, gpuDispatchHistory.length);
        }
    }

    /**
     * Get the adaptive max chunks per frame based on current conditions.
     */
    public static int getAdaptiveMaxChunksPerFrame() {
        if (!enabled)
            return maxChunksPerFrame;

        double factor = currentPowerMode.gpuWorkFactor;
        factor *= dispatchSizeMultiplier;

        // Factor in remaining frame budget
        long elapsed = System.nanoTime() - lastFrameStartTime;
        if (elapsed > targetFrameTimeNs) {
            factor *= 0.5; // Frame overrun - halve the budget
        }

        return Math.max(1, (int) (maxChunksPerFrame * factor));
    }

    // =========================================================================
    // Public API: GPU Utilization Feedback Loop
    // =========================================================================

    /**
     * Begin a GPU timing query for precise dispatch measurement.
     * Returns true if query was started successfully.
     */
    public static boolean beginGpuTiming() {
        if (!enabled || !queriesInitialized)
            return false;

        try {
            GL33.glQueryCounter(gpuTimeQueries[queryBufferIndex], GL33.GL_TIMESTAMP);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * End a GPU timing query and return elapsed time in nanoseconds.
     * Returns -1 if query failed.
     */
    public static long endGpuTiming() {
        if (!enabled || !queriesInitialized)
            return -1;

        try {
            int endIdx = (queryBufferIndex + 1) % 2;
            GL33.glQueryCounter(gpuTimeQueries[endIdx], GL33.GL_TIMESTAMP);

            java.nio.LongBuffer startBuf = org.lwjgl.system.MemoryUtil.memAllocLong(1);
            java.nio.LongBuffer endBuf = org.lwjgl.system.MemoryUtil.memAllocLong(1);
            GL33.glGetQueryObjecti64v(gpuTimeQueries[queryBufferIndex], GL33.GL_QUERY_RESULT, startBuf);
            GL33.glGetQueryObjecti64v(gpuTimeQueries[endIdx], GL33.GL_QUERY_RESULT, endBuf);

            long start = startBuf.get(0);
            long end = endBuf.get(0);
            org.lwjgl.system.MemoryUtil.memFree(startBuf);
            org.lwjgl.system.MemoryUtil.memFree(endBuf);

            queryBufferIndex = (queryBufferIndex + 1) % 2;

            long elapsed = end - start;
            measuredGpuUtilization = Math.min(1.0, elapsed / (double) targetFrameTimeNs);
            return Math.max(0, elapsed);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Update the PID-based feedback loop for GPU utilization.
     * Called periodically from beginFrame().
     */
    private static void updateFeedbackLoop() {
        double currentGpuUsage = measuredGpuUtilization;
        if (currentGpuUsage <= 0) {
            // Use measured utilization from queries if available
            currentGpuUsage = measuredGpuUtilization;
        }

        double error = targetGpuUtilization - currentGpuUsage;
        double deltaError = error - pidLastError;
        pidErrorAccumulator += error;

        // Clamp integral term to prevent windup
        pidErrorAccumulator = Math.max(-10, Math.min(10, pidErrorAccumulator));

        // PID output
        double output = PID_Kp * error + PID_Ki * pidErrorAccumulator + PID_Kd * deltaError;

        // Adjust dispatch size multiplier
        dispatchSizeMultiplier = Math.max(0.25, Math.min(2.0, 1.0 + output));

        pidLastError = error;

        // Adapt max chunks per frame based on GPU usage
        if (currentGpuUsage > 0.9) {
            maxChunksPerFrame = Math.max(1, maxChunksPerFrame - 1);
        } else if (currentGpuUsage < 0.5 && maxChunksPerFrame < 16) {
            maxChunksPerFrame = Math.min(16, maxChunksPerFrame + 1);
        }

        // Log if significant change
        if (Math.abs(output) > 0.1) {
            UHDFast.LOGGER.debug("XePowerOptimizer: Feedback adjustment - dispatch multiplier: {}, max chunks: {}",
                    dispatchSizeMultiplier, maxChunksPerFrame);
        }
    }

    /**
     * Get the current dispatch size multiplier (for external dispatch calculation).
     */
    public static double getDispatchSizeMultiplier() {
        return dispatchSizeMultiplier;
    }

    /**
     * Get the average GPU dispatch time in milliseconds.
     */
    public static double getAverageGpuDispatchTimeMs() {
        synchronized (gpuDispatchHistory) {
            if (historyCount == 0)
                return 0;
            double sum = 0;
            int count = Math.min(historyCount, gpuDispatchHistory.length);
            int start = Math.max(0, historyIndex - count);
            for (int i = 0; i < count; i++) {
                sum += gpuDispatchHistory[(start + i) % gpuDispatchHistory.length];
            }
            return sum / count;
        }
    }

    // =========================================================================
    // Public API: GPU/CPU Work Stealing
    // =========================================================================

    /**
     * Check if work should be stolen from GPU and moved to CPU.
     * Call before dispatching GPU work.
     * 
     * @return true if work should be stolen to CPU
     */
    public static boolean shouldStealWork() {
        if (!enabled || !workStealingEnabled)
            return false;
        if (currentPowerMode == PowerMode.EMERGENCY)
            return true;

        double gpuUsage = measuredGpuUtilization * 100.0;

        // Steal work if GPU is overloaded
        if (gpuUsage >= workStealThreshold) {
            // Check if we haven't exceeded max stolen work
            if (stolenWorkQueue.size() < MAX_STOLEN_WORK) {
                return true;
            }
        }

        return false;
    }

    /**
     * Steal a chunk generation task from GPU to CPU.
     * Returns a CompletableFuture that will complete when CPU finishes.
     * 
     * @param task   Runnable that performs the chunk generation
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return CompletableFuture<Void>
     */
    public static CompletableFuture<Void> stealToCPU(Runnable task, int chunkX, int chunkZ) {
        if (cpuWorkStealingExecutor == null) {
            initialize();
        }

        StolenWorkItem item = new StolenWorkItem(task, chunkX, chunkZ, System.nanoTime());
        stolenWorkQueue.offer(item);
        workStealCount.incrementAndGet();

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            long start = System.nanoTime();
            try {
                task.run();
                cpuCompletedTasks.incrementAndGet();
            } catch (Exception e) {
                UHDFast.LOGGER.warn("XePowerOptimizer: CPU work stealing task failed: {}", e.getMessage());
            }
            totalCpuWorkTimeNs.addAndGet(System.nanoTime() - start);
        }, cpuWorkStealingExecutor);

        UHDFast.LOGGER.debug("XePowerOptimizer: Work stolen to CPU for chunk ({}, {})", chunkX, chunkZ);
        return future;
    }

    /**
     * Check if stolen work can be returned to GPU.
     */
    public static boolean shouldReturnWorkToGPU() {
        if (!enabled || !workStealingEnabled)
            return false;

        double gpuUsage = measuredGpuUtilization * 100.0;
        return gpuUsage <= workReturnThreshold && !stolenWorkQueue.isEmpty();
    }

    /**
     * Get number of pending stolen work items.
     */
    public static int getPendingStolenWork() {
        return stolenWorkQueue.size();
    }

    /**
     * Work item stolen from GPU to CPU.
     */
    private static class StolenWorkItem {
        StolenWorkItem(Runnable task, int chunkX, int chunkZ, long stealTime) {
        }
    }

    // =========================================================================
    // Public API: Temporal Spreading
    // =========================================================================

    /**
     * Enqueue work for temporal spreading across multiple frames.
     * 
     * @param task     Runnable to execute
     * @param priority Task priority (lower = higher priority)
     * @return true if enqueued, false if queue is full
     */
    public static boolean spreadOverFrames(Runnable task, int priority) {
        if (!enabled || !temporalSpreadingEnabled) {
            task.run();
            return true;
        }

        if (temporalWorkQueue.size() >= MAX_TEMPORAL_WORK) {
            spreadItemsDeferred.incrementAndGet();
            return false;
        }

        temporalWorkQueue.offer(new TemporalWorkItem(task, priority, currentFrameNumber));
        spreadFrameCount.incrementAndGet();
        return true;
    }

    /**
     * Process deferred temporal work items for the current spread frame.
     * Called automatically from beginFrame().
     */
    private static void processTemporalQueue() {
        if (temporalWorkQueue.isEmpty())
            return;

        int processed = 0;
        int maxPerFrame = getAdaptiveMaxChunksPerFrame();

        Iterator<TemporalWorkItem> it = temporalWorkQueue.iterator();
        while (it.hasNext() && processed < maxPerFrame && hasFrameBudget()) {
            TemporalWorkItem item = it.next();

            // Process based on priority and spread frame
            if (shouldProcessTemporalItem(item)) {
                try {
                    item.task.run();
                    spreadItemsProcessed.incrementAndGet();
                    processed++;
                } catch (Exception e) {
                    UHDFast.LOGGER.debug("XePowerOptimizer: Temporal work item failed: {}", e.getMessage());
                }
                it.remove();
            }
        }
    }

    /**
     * Determine if a temporal work item should be processed in current spread
     * frame.
     */
    private static boolean shouldProcessTemporalItem(TemporalWorkItem item) {
        // High priority items always process
        if (item.priority < 10)
            return true;

        // Medium priority: process on even spread frames
        if (item.priority < 50 && currentSpreadFrame % 2 == 0)
            return true;

        // Low priority: process only on specific spread frames
        if (currentSpreadFrame == temporalSpreadFrames - 1)
            return true;

        // Check if item is getting old (starvation prevention)
        int age = currentFrameNumber - item.enqueueFrame;
        return age > temporalSpreadFrames * 4; // Force process after 4 spread cycles
    }

    /**
     * Get temporal spreading statistics.
     */
    public static int getTemporalQueueSize() {
        return temporalWorkQueue.size();
    }

    /**
     * Temporal work item for spreading across frames.
     */
    private static class TemporalWorkItem {
        final Runnable task;
        final int priority;
        final int enqueueFrame;

        TemporalWorkItem(Runnable task, int priority, int enqueueFrame) {
            this.task = task;
            this.priority = priority;
            this.enqueueFrame = enqueueFrame;
        }
    }

    // =========================================================================
    // Public API: Shader Dispatch Optimization
    // =========================================================================

    /**
     * Compute optimal dispatch dimensions for given chunk count.
     * Uses dynamic work group sizing to minimize wasted threads.
     * 
     * @param chunkCount Number of chunks to process
     * @return int[3] dispatch dimensions {x, y, z}
     */
    public static int[] computeOptimalDispatch(int chunkCount) {
        if (!enabled || !dispatchOptimizationEnabled) {
            return new int[] { 1, (chunkCount + 15) / 16, 1 };
        }

        // Check if we can skip dispatch (chunk count hasn't changed significantly)
        if (lastOptimizedChunkCount > 0) {
            float change = Math.abs(chunkCount - lastOptimizedChunkCount)
                    / (float) Math.max(1, lastOptimizedChunkCount);
            if (change < REDUNDANT_DISPATCH_THRESHOLD && chunkCount == lastOptimizedChunkCount) {
                skippedDispatches.incrementAndGet();
                return new int[] { cachedDispatchX, cachedDispatchY, cachedDispatchZ };
            }
        }

        lastOptimizedChunkCount = chunkCount;

        // Apply power mode dispatch size factor
        double effectiveChunkCount = chunkCount * currentPowerMode.dispatchSizeFactor;
        int effectiveChunks = Math.max(1, (int) Math.ceil(effectiveChunkCount));

        // Optimal dispatch: minimize total work groups while covering all chunks
        // Xe architecture: prefer Y dimension for terrain work (matches chunk layout)
        int workGroupsPerChunk = 20; // 20 work groups per chunk (from existing dispatch: 1, 20, 2)

        int totalWorkGroups = (int) Math.ceil(effectiveChunks * workGroupsPerChunk * dispatchSizeMultiplier);

        // Split across dimensions for optimal GPU occupancy
        // Xe GPU: 16-32 threads per work group, prefer 2D dispatch
        if (totalWorkGroups <= 32) {
            cachedDispatchX = 1;
            cachedDispatchY = totalWorkGroups;
            cachedDispatchZ = 1;
        } else if (totalWorkGroups <= 256) {
            cachedDispatchX = 1;
            cachedDispatchY = Math.min(totalWorkGroups, 64);
            cachedDispatchZ = (totalWorkGroups + 63) / 64;
        } else {
            cachedDispatchX = 1;
            cachedDispatchY = 64;
            cachedDispatchZ = (totalWorkGroups + 63) / 64;
        }

        // Clamp to reasonable limits
        cachedDispatchX = Math.max(1, Math.min(cachedDispatchX, 1));
        cachedDispatchY = Math.max(1, Math.min(cachedDispatchY, 128));
        cachedDispatchZ = Math.max(1, Math.min(cachedDispatchZ, 64));

        return new int[] { cachedDispatchX, cachedDispatchY, cachedDispatchZ };
    }

    /**
     * Check if a batched dispatch should be used instead of individual dispatches.
     * 
     * @param chunkCount Number of chunks pending
     * @return true if batched dispatch is recommended
     */
    public static boolean shouldUseBatchedDispatch(int chunkCount) {
        if (!enabled || !dispatchOptimizationEnabled)
            return chunkCount >= 4;
        return chunkCount >= minBatchThreshold;
    }

    /**
     * Coalesce multiple small dispatches into a single batched dispatch.
     * Returns the coalesced chunk count or 0 if coalescing not possible.
     */
    public static int getCoalescedBatchSize(int pendingChunks) {
        if (!enabled || !dispatchOptimizationEnabled)
            return pendingChunks;

        // Apply power mode limits
        int maxBatch = (int) (8 * currentPowerMode.dispatchSizeFactor);
        maxBatch = Math.max(1, Math.min(maxBatch, 16));

        return Math.min(pendingChunks, maxBatch);
    }

    // =========================================================================
    // Public API: Power Saving Mode
    // =========================================================================

    /**
     * Update power mode based on sustained GPU usage patterns.
     * Called periodically from beginFrame().
     */
    private static void updatePowerMode() {
        double gpuUsage = measuredGpuUtilization * 100.0;
        if (gpuUsage <= 0)
            return;

        // Record sample
        gpuUsageSamples[sampleIndex % gpuUsageSamples.length] = gpuUsage;
        sampleIndex++;
        sampleCount = Math.min(sampleCount + 1, gpuUsageSamples.length);

        // Calculate sustained average (last 60 samples)
        int avgCount = Math.min(sampleCount, 60);
        double sustainedAvg = 0;
        for (int i = 0; i < avgCount; i++) {
            int idx = ((sampleIndex - 1 - i) + gpuUsageSamples.length) % gpuUsageSamples.length;
            sustainedAvg += gpuUsageSamples[idx];
        }
        sustainedAvg /= avgCount;

        // Escalate power saving if sustained high usage
        if (sustainedAvg > 85) {
            sustainedHighUsageFrames++;
            sustainedLowUsageFrames = 0;

            if (sustainedHighUsageFrames >= powerSaveEscalationFrames
                    && currentPowerMode.level < PowerMode.EMERGENCY.level) {
                currentPowerMode = currentPowerMode.nextSaver();
                modeTransitionCount.incrementAndGet();

                // Emergency mode triggers pause
                if (currentPowerMode == PowerMode.EMERGENCY) {
                    emergencyPause = true;
                    emergencyPauseStartTime = System.currentTimeMillis();
                }

                UHDFast.LOGGER.info("XePowerOptimizer: Power mode escalated to {} (sustained GPU: {:.0f}%)",
                        currentPowerMode.name, sustainedAvg);
            }
        } else if (sustainedAvg < 50) {
            sustainedLowUsageFrames++;
            sustainedHighUsageFrames = 0;

            if (sustainedLowUsageFrames >= powerSaveDeescalationFrames
                    && currentPowerMode.level > PowerMode.FULL_PERFORMANCE.level) {
                currentPowerMode = currentPowerMode.nextFaster();
                modeTransitionCount.incrementAndGet();

                if (currentPowerMode != PowerMode.EMERGENCY) {
                    emergencyPause = false;
                }

                UHDFast.LOGGER.info("XePowerOptimizer: Power mode reduced to {} (sustained GPU: {:.0f}%)",
                        currentPowerMode.name, sustainedAvg);
            }
        } else {
            sustainedHighUsageFrames = 0;
            sustainedLowUsageFrames = 0;
        }

        // Adjust temporal spread frames based on power mode
        temporalSpreadFrames = switch (currentPowerMode) {
            case FULL_PERFORMANCE -> 1;
            case BALANCED -> 2;
            case POWER_SAVE -> 3;
            case ULTRA_POWER_SAVE -> 5;
            case EMERGENCY -> 10;
        };
    }

    /**
     * Get current power mode.
     */
    public static PowerMode getCurrentPowerMode() {
        return currentPowerMode;
    }

    /**
     * Check if in emergency pause state.
     */
    public static boolean isInEmergencyPause() {
        return emergencyPause;
    }

    /**
     * Force a specific power mode (for external control).
     */
    public static void setPowerMode(PowerMode mode) {
        PowerMode oldMode = currentPowerMode;
        currentPowerMode = mode;
        if (oldMode != mode) {
            modeTransitionCount.incrementAndGet();
        }

        if (mode != PowerMode.EMERGENCY) {
            emergencyPause = false;
        }
    }

    /**
     * Get GPU work factor for current power mode.
     */
    public static float getGpuWorkFactor() {
        return currentPowerMode.gpuWorkFactor;
    }

    // =========================================================================
    // Integration with UnifiedGPUChunkManager
    // =========================================================================

    /**
     * Pre-dispatch check - call before any GPU chunk dispatch.
     * Returns true if dispatch should proceed.
     * 
     * This is the main integration point for UnifiedGPUChunkManager.
     */
    public static boolean canDispatchGpuWork() {
        if (!enabled)
            return true;

        // Check frame budget
        if (!hasFrameBudget()) {
            return false;
        }

        // Check power mode
        if (currentPowerMode == PowerMode.EMERGENCY) {
            return false;
        }

        // Check if work should be stolen
        if (shouldStealWork()) {
            return false;
        }

        return true;
    }

    /**
     * Prepare dispatch parameters optimized for current conditions.
     * Returns a DispatchPlan containing optimal settings.
     */
    public static DispatchPlan prepareDispatch(int chunkCount, boolean isHighPriority) {
        DispatchPlan plan = new DispatchPlan();

        if (!enabled) {
            plan.useGpu = true;
            plan.dispatchX = 1;
            plan.dispatchY = (chunkCount + 15) / 16;
            plan.dispatchZ = 1;
            plan.batchSize = chunkCount;
            return plan;
        }

        // Determine if work should be stolen to CPU
        if (!isHighPriority && shouldStealWork()) {
            plan.useGpu = false;
            plan.useCpu = true;
            return plan;
        }

        // Compute optimal dispatch dimensions
        int[] dims = computeOptimalDispatch(chunkCount);
        plan.dispatchX = dims[0];
        plan.dispatchY = dims[1];
        plan.dispatchZ = dims[2];

        // Determine batch size
        plan.batchSize = getCoalescedBatchSize(chunkCount);
        plan.useBatchedDispatch = shouldUseBatchedDispatch(chunkCount);
        plan.useGpu = true;

        // If temporal spreading is enabled and queue has items, reduce batch
        if (temporalSpreadingEnabled && !temporalWorkQueue.isEmpty()) {
            plan.batchSize = Math.max(1, plan.batchSize / 2);
            plan.shouldDefer = true;
        }

        return plan;
    }

    /**
     * Dispatch plan returned by prepareDispatch().
     */
    public static class DispatchPlan {
        public boolean useGpu = true;
        public boolean useCpu = false;
        public boolean shouldDefer = false;
        public int dispatchX = 1;
        public int dispatchY = 1;
        public int dispatchZ = 1;
        public int batchSize = 1;
        public boolean useBatchedDispatch = false;

        @Override
        public String toString() {
            return String.format("DispatchPlan{gpu=%s cpu=%s defer=%s dims=[%d,%d,%d] batch=%d batched=%s}",
                    useGpu, useCpu, shouldDefer, dispatchX, dispatchY, dispatchZ, batchSize, useBatchedDispatch);
        }
    }

    // =========================================================================
    // Configuration
    // =========================================================================

    public static void setTargetFPS(int fps) {
        targetFPS = fps;
        targetFrameTimeNs = (long) (1_000_000_000L / fps);
        maxGpuWorkPerFrameNs = (long) (targetFrameTimeNs * frameBudgetPercent / 100.0);
    }

    public static void setGpuUtilizationTarget(double target) {
        gpuUtilizationTarget = Math.max(0.3, Math.min(0.95, target));
        targetGpuUtilization = gpuUtilizationTarget;
    }

    public static void setFrameBudgetPercent(int percent) {
        frameBudgetPercent = Math.max(5, Math.min(50, percent));
        maxGpuWorkPerFrameNs = (long) (targetFrameTimeNs * frameBudgetPercent / 100.0);
    }

    public static void setTemporalSpreadFrames(int frames) {
        temporalSpreadFrames = Math.max(1, Math.min(16, frames));
    }

    public static void setWorkStealThreshold(double threshold) {
        workStealThreshold = Math.max(50, Math.min(95, threshold));
    }

    public static void setPowerSaveEscalationFrames(int frames) {
        powerSaveEscalationFrames = Math.max(10, Math.min(300, frames));
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    public static int getTotalFramesProcessed() {
        return totalFramesProcessed.get();
    }

    public static int getTotalChunksGenerated() {
        return totalChunksGenerated.get();
    }

    public static int getWorkStealCount() {
        return workStealCount.get();
    }

    public static int getWorkReturnCount() {
        return workReturnCount.get();
    }

    public static int getCpuCompletedTasks() {
        return cpuCompletedTasks.get();
    }

    public static int getModeTransitionCount() {
        return modeTransitionCount.get();
    }

    public static int getSkippedDispatches() {
        return skippedDispatches.get();
    }

    public static int getSpreadItemsProcessed() {
        return spreadItemsProcessed.get();
    }

    public static int getSpreadItemsDeferred() {
        return spreadItemsDeferred.get();
    }

    public static int getTotalBatchesCoalesced() {
        return totalBatchesCoalesced.get();
    }

    public static double getAverageGpuWorkPerFrameMs() {
        int frames = totalFramesProcessed.get();
        if (frames == 0)
            return 0;
        return totalGpuWorkTimeNs.get() / (double) frames / 1_000_000.0;
    }

    public static double getAverageCpuWorkPerFrameMs() {
        int frames = totalFramesProcessed.get();
        if (frames == 0)
            return 0;
        return totalCpuWorkTimeNs.get() / (double) frames / 1_000_000.0;
    }

    public static void resetStats() {
        totalGpuWorkTimeNs.set(0);
        totalCpuWorkTimeNs.set(0);
        totalIdleTimeNs.set(0);
        totalFramesProcessed.set(0);
        totalChunksGenerated.set(0);
        totalBatchesCoalesced.set(0);
        workStealCount.set(0);
        workReturnCount.set(0);
        cpuCompletedTasks.set(0);
        modeTransitionCount.set(0);
        skippedDispatches.set(0);
        spreadItemsProcessed.set(0);
        spreadItemsDeferred.set(0);
        spreadFrameCount.set(0);
    }

    public static String getOptimizerStats() {
        return String.format(
                "XePowerOptimizer: mode=%s fps=%.1f budget=%.1fms/%.1fms chunks=%d gpuWork=%.1fms cpuWork=%.1fms "
                        + "steals=%d/%d cpuDone=%d skips=%d spread=%d/%d batches=%d",
                currentPowerMode.name,
                targetFPS,
                getAverageGpuWorkPerFrameMs(),
                maxGpuWorkPerFrameNs / 1_000_000.0,
                totalChunksGenerated.get(),
                getAverageGpuWorkPerFrameMs(),
                getAverageCpuWorkPerFrameMs(),
                workStealCount.get(),
                workReturnCount.get(),
                cpuCompletedTasks.get(),
                skippedDispatches.get(),
                spreadItemsProcessed.get(),
                spreadItemsDeferred.get(),
                totalBatchesCoalesced.get());
    }

    // =========================================================================
    // Shutdown
    // =========================================================================

    public static synchronized void shutdown() {
        if (cpuWorkStealingExecutor != null && !cpuWorkStealingExecutor.isShutdown()) {
            cpuWorkStealingExecutor.shutdown();
            try {
                if (!cpuWorkStealingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    cpuWorkStealingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cpuWorkStealingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Clean up GPU queries
        if (queriesInitialized) {
            try {
                GL15.glDeleteQueries(gpuTimeQueries[0]);
                GL15.glDeleteQueries(gpuTimeQueries[1]);
            } catch (Exception e) {
                // ignore
            }
            queriesInitialized = false;
        }

        stolenWorkQueue.clear();
        temporalWorkQueue.clear();

        enabled = false;
        UHDFast.LOGGER.info("XePowerOptimizer: Shutdown");
    }
}
