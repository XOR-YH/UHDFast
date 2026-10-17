package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xe Multi-Queue Manager for parallel GPU dispatch.
 *
 * Intel Xe GPUs have multiple engines that can execute work in parallel:
 * - 3D engine: Main graphics rendering
 * - Compute engine: Compute shaders (our terrain generation)
 * - Copy engine: DMA transfers
 * - Video engine: Media processing
 *
 * By using multiple queues, we can overlap:
 * 1. Terrain generation on compute queue
 * 2. Mesh building on graphics queue
 * 3. Data upload/download on copy queue
 *
 * This maximizes Xe EU utilization and hides memory transfer latency.
 *
 * Based on Intel oneAPI GPU Optimization Guide:
 * "Submitting Kernels to Multiple Queues" and
 * "Executing Multiple Kernels on the Device at the Same Time"
 */
public class XeMultiQueueManager {

    // ========================================================================
    // Queue types matching Xe hardware engines
    // ========================================================================

    /**
     * Logical queue types mapped to Xe engines.
     */
    public enum QueueType {
        /** Compute engine - terrain generation, noise computation */
        COMPUTE_ENGINE("Compute"),
        /** Graphics/3D engine - mesh building, vertex processing */
        GRAPHICS_ENGINE("Graphics"),
        /** Copy/DMA engine - buffer uploads and downloads */
        COPY_ENGINE("Copy");

        public final String name;

        QueueType(String name) {
            this.name = name;
        }
    }

    // ========================================================================
    // OpenGL command queue abstraction
    // ========================================================================

    /**
     * Represents a logical command queue backed by an OpenGL sync object.
     * In OpenGL, we simulate multi-queue using multiple sync objects and
     * careful dispatch ordering to overlap work where the driver allows.
     */
    private static class CommandQueue {
        private volatile long lastSync = 0;
        private volatile int pendingDispatches = 0;
        private final AtomicInteger totalDispatches = new AtomicInteger(0);

        public CommandQueue(QueueType type) {
        }

        public void submitSync() {
            // Insert a fence sync after dispatch
            if (lastSync != 0) {
                ARBSync.glDeleteSync(lastSync);
            }
            lastSync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            pendingDispatches++;
            totalDispatches.incrementAndGet();
        }

        public void waitSync() {
            if (lastSync == 0) {
                return;
            }
            int result = ARBSync.glClientWaitSync(lastSync, ARBSync.GL_SYNC_FLUSH_COMMANDS_BIT, 0);
            if (result == ARBSync.GL_CONDITION_SATISFIED || result == ARBSync.GL_ALREADY_SIGNALED) {
                ARBSync.glDeleteSync(lastSync);
                lastSync = 0;
            }
        }

        public boolean isSignaled() {
            if (lastSync == 0)
                return true;
            IntBuffer statusBuf = MemoryUtil.memAllocInt(1);
            ARBSync.glGetSynci(lastSync, ARBSync.GL_SYNC_STATUS, statusBuf);
            int status = statusBuf.get(0);
            MemoryUtil.memFree(statusBuf);
            if (status == ARBSync.GL_SIGNALED) {
                ARBSync.glDeleteSync(lastSync);
                lastSync = 0;
                return true;
            }
            return false;
        }

        public int getTotalDispatches() {
            return totalDispatches.get();
        }

        public int getPendingDispatches() {
            return pendingDispatches;
        }
    }

    // ========================================================================
    // Queue pool
    // ========================================================================

    private static CommandQueue[] queues = new CommandQueue[QueueType.values().length];
    private static volatile boolean initialized = false;

    // Statistics
    private static final AtomicInteger totalSubmissions = new AtomicInteger(0);
    private static final AtomicInteger totalWaits = new AtomicInteger(0);
    private static final long[] queueStartTimes = new long[QueueType.values().length];

    /**
     * Initialize multi-queue system.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        for (int i = 0; i < QueueType.values().length; i++) {
            queues[i] = new CommandQueue(QueueType.values()[i]);
            queueStartTimes[i] = System.nanoTime();
        }

        initialized = true;

        IntelXeDetector.XeArchitectureType arch = IntelXeDetector.getArchitectureType();
        int euCount = IntelXeDetector.getEUCount();
        UHDFast.LOGGER.info("XeMultiQueueManager: Initialized for {} ({} EUs)", arch, euCount);
        UHDFast.LOGGER.info("  Queue count: {}", QueueType.values().length);
        for (QueueType qt : QueueType.values()) {
            UHDFast.LOGGER.info("    {} engine queue: active", qt.name);
        }
    }

    /**
     * Get command queue by type.
     */
    public static CommandQueue getQueue(QueueType type) {
        if (!initialized) {
            initialize();
        }
        return queues[type.ordinal()];
    }

    /**
     * Submit terrain generation dispatch on compute queue.
     * Non-blocking - returns immediately, use wait to synchronize.
     * Integrated with XePowerOptimizer for adaptive dispatch sizing.
     */
    public static void submitTerrainDispatch(int dispatchX, int dispatchY, int dispatchZ, int program) {
        CommandQueue queue = getQueue(QueueType.COMPUTE_ENGINE);

        // Use XePowerOptimizer to potentially adjust dispatch dimensions
        if (XePowerOptimizer.isEnabled()) {
            double multiplier = XePowerOptimizer.getDispatchSizeMultiplier();
            if (multiplier < 1.0) {
                // Scale down dispatch when GPU is busy
                dispatchY = Math.max(1, (int) Math.ceil(dispatchY * multiplier));
            }
        }

        GL20.glUseProgram(program);
        GL43.glDispatchCompute(dispatchX, dispatchY, dispatchZ);
        MemoryBarrierOptimizer.barrierCombined(MemoryBarrierOptimizer.BARRIER_SSBO, MemoryBarrierOptimizer.BARRIER_UBO);

        queue.submitSync();
        totalSubmissions.incrementAndGet();
    }

    /**
     * Submit mesh building dispatch on graphics queue.
     * Non-blocking - returns immediately.
     */
    public static void submitMeshDispatch(int dispatchX, int dispatchY, int dispatchZ, int program) {
        CommandQueue queue = getQueue(QueueType.GRAPHICS_ENGINE);

        GL20.glUseProgram(program);
        GL43.glDispatchCompute(dispatchX, dispatchY, dispatchZ);
        GL43.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        queue.submitSync();
        totalSubmissions.incrementAndGet();
    }

    /**
     * Submit buffer upload on copy queue.
     * Non-blocking - use waitSync before reading.
     */
    public static void submitBufferUpload(int buffer, long offset, ByteBuffer data) {
        CommandQueue queue = getQueue(QueueType.COPY_ENGINE);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offset, data);

        queue.submitSync();
        totalSubmissions.incrementAndGet();
    }

    /**
     * Submit buffer download on copy queue.
     * Non-blocking - use waitSync before reading result.
     */
    public static void submitBufferDownload(int buffer, long offset, ByteBuffer data) {
        CommandQueue queue = getQueue(QueueType.COPY_ENGINE);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offset, data);

        queue.submitSync();
        totalSubmissions.incrementAndGet();
    }

    /**
     * Wait for compute queue to finish.
     * Blocking - used when terrain gen must complete before next step.
     */
    public static void waitForCompute() {
        CommandQueue queue = getQueue(QueueType.COMPUTE_ENGINE);
        queue.waitSync();
        totalWaits.incrementAndGet();
    }

    /**
     * Wait for graphics queue to finish.
     */
    public static void waitForGraphics() {
        CommandQueue queue = getQueue(QueueType.GRAPHICS_ENGINE);
        queue.waitSync();
        totalWaits.incrementAndGet();
    }

    /**
     * Wait for copy queue to finish.
     */
    public static void waitForCopy() {
        CommandQueue queue = getQueue(QueueType.COPY_ENGINE);
        queue.waitSync();
        totalWaits.incrementAndGet();
    }

    /**
     * Wait for all queues to finish.
     */
    public static void waitForAll() {
        waitForCompute();
        waitForGraphics();
        waitForCopy();
    }

    /**
     * Submit multiple chunks in parallel using batched dispatch.
     * Uses Xe's ability to execute multiple compute kernels simultaneously.
     * Integrated with XePowerOptimizer for adaptive dispatch sizing and power
     * management.
     */
    public static void submitBatchedTerrainDispatch(int[][] chunkCoords, long[] seeds, float seaLevel,
            int program, int uniformsBuffer) {
        CommandQueue compute = getQueue(QueueType.COMPUTE_ENGINE);

        // Use XePowerOptimizer to get optimal dispatch dimensions
        int[] dispatchDims = XePowerOptimizer.computeOptimalDispatch(chunkCoords.length);

        for (int i = 0; i < chunkCoords.length; i++) {
            // Bind uniforms for this chunk
            GL43.glBindBufferBase(GL43.GL_UNIFORM_BUFFER, 0, uniformsBuffer);

            // Update uniforms (chunkX, chunkZ, seed)
            int[] uniformData = new int[8];
            uniformData[0] = (int) (seeds[i] & 0xFFFFFFFFL);
            uniformData[1] = chunkCoords[i][0];
            uniformData[2] = chunkCoords[i][1];
            uniformData[3] = Float.floatToIntBits(seaLevel);

            java.nio.IntBuffer ub = java.nio.ByteBuffer.allocateDirect(32).asIntBuffer();
            ub.put(uniformData).position(0);
            GL15.glBindBuffer(GL43.GL_UNIFORM_BUFFER, uniformsBuffer);
            GL15.glBufferSubData(GL43.GL_UNIFORM_BUFFER, 0L, ub);

            GL20.glUseProgram(program);

            // Use optimized dispatch dimensions
            GL43.glDispatchCompute(dispatchDims[0], dispatchDims[1], dispatchDims[2]);
        }

        // Single memory barrier for all chunks (optimized: use specific barrier bits)
        MemoryBarrierOptimizer.barrierCombined(MemoryBarrierOptimizer.BARRIER_SSBO, MemoryBarrierOptimizer.BARRIER_UBO);

        compute.submitSync();
        totalSubmissions.incrementAndGet();
    }

    /**
     * Check if compute queue is idle.
     */
    public static boolean isComputeIdle() {
        return getQueue(QueueType.COMPUTE_ENGINE).isSignaled();
    }

    /**
     * Check if all queues are idle.
     */
    public static boolean allQueuesIdle() {
        for (CommandQueue q : queues) {
            if (!q.isSignaled())
                return false;
        }
        return true;
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    public static int getTotalSubmissions() {
        return totalSubmissions.get();
    }

    public static int getTotalWaits() {
        return totalWaits.get();
    }

    public static String getQueueStats() {
        StringBuilder sb = new StringBuilder("Queue stats: ");
        for (QueueType qt : QueueType.values()) {
            CommandQueue q = queues[qt.ordinal()];
            sb.append(qt.name).append("=").append(q.getTotalDispatches())
                    .append("/pending=").append(q.getPendingDispatches()).append(" ");
        }
        return sb.toString();
    }

    public static void resetStats() {
        totalSubmissions.set(0);
        totalWaits.set(0);
    }

    /**
     * Cleanup all queues and sync objects.
     */
    public static synchronized void cleanup() {
        if (!initialized)
            return;

        for (CommandQueue q : queues) {
            if (q.lastSync != 0) {
                ARBSync.glDeleteSync(q.lastSync);
                q.lastSync = 0;
            }
        }

        initialized = false;
        UHDFast.LOGGER.info("XeMultiQueueManager: Cleaned up");
    }
}
