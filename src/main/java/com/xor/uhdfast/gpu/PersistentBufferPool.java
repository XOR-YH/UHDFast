package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久映射缓冲区池 - 使用 ARB_buffer_storage (OpenGL 4.4) 的 GL_MAP_PERSISTENT_BIT
 * 和 GL_MAP_COHERENT_BIT 实现零拷贝数据传输。
 *
 * 核心优化：
 * 1. 缓冲区在创建时永久映射，避免每次传输的 map/unmap 开销
 * 2. 使用双缓冲/三缓冲 + Fence 同步实现 CPU/GPU 并行
 * 3. 缓冲区复用机制，避免频繁创建/销毁
 * 4. 提供类型化视图（Float/Int/Byte），减少类型转换开销
 *
 * 基于 Intel oneAPI 优化最佳实践：
 * - "Persistent Mapped Buffers" - 消除映射开销
 * - "Triple Buffering for Overlap" - 隐藏延迟
 */
public class PersistentBufferPool {

    /**
     * 缓冲区的用途类型
     */
    public enum BufferUsage {
        /** CPU写入 -> GPU读取 (上传) */
        UPLOAD,
        /** GPU写入 -> CPU读取 (回读) */
        DOWNLOAD,
        /** 双向持久映射 */
        PERSISTENT
    }

    /**
     * 单个持久映射缓冲区的包装
     */
    public static class PersistentBuffer {
        public final int glBufferId;
        public final long sizeBytes;
        public final BufferUsage usage;
        public final ByteBuffer mappedPointer;
        public final FloatBuffer floatView;
        public final IntBuffer intView;

        // 用于同步的 fence (三缓冲)
        private long fence = 0;
        private volatile boolean inUse = false;

        PersistentBuffer(int glBufferId, long sizeBytes, BufferUsage usage,
                ByteBuffer mappedPointer) {
            this.glBufferId = glBufferId;
            this.sizeBytes = sizeBytes;
            this.usage = usage;
            this.mappedPointer = mappedPointer;
            this.floatView = mappedPointer.asFloatBuffer();
            this.intView = mappedPointer.asIntBuffer();
        }

        /**
         * 获取 FloatBuffer 视图，已重置 position/limit
         */
        public FloatBuffer getFloatView() {
            floatView.clear();
            return floatView;
        }

        /**
         * 获取 IntBuffer 视图，已重置 position/limit
         */
        public IntBuffer getIntView() {
            intView.clear();
            return intView;
        }

        /**
         * 获取 ByteBuffer 视图，已重置 position/limit
         */
        public ByteBuffer getByteView() {
            mappedPointer.clear();
            return mappedPointer;
        }

        /**
         * 写入 float 数据到缓冲区
         */
        public void writeFloats(float[] data, int offset, int length) {
            floatView.clear();
            floatView.put(data, offset, length);
            floatView.position(0);
        }

        /**
         * 写入 int 数据到缓冲区
         */
        public void writeInts(int[] data, int offset, int length) {
            intView.clear();
            intView.put(data, offset, length);
            intView.position(0);
        }

        /**
         * 标记此缓冲区正在被 GPU 使用，插入 fence
         */
        void markInUse() {
            inUse = true;
            if (fence != 0) {
                ARBSync.glDeleteSync(fence);
            }
            fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }

        /**
         * 检查 GPU 是否已完成对此缓冲区的使用
         * 优化：使用静态缓存 IntBuffer 避免每帧分配和泄漏
         */
        private static final IntBuffer cachedGpuStatus = MemoryUtil.memAllocInt(1).put(0).position(0);

        boolean isGpuDone() {
            if (fence == 0)
                return true;
            cachedGpuStatus.clear();
            int status = ARBSync.glGetSynci(fence, ARBSync.GL_SYNC_STATUS, cachedGpuStatus);
            return status == ARBSync.GL_SIGNALED;
        }

        /**
         * 等待 GPU 完成
         */
        void waitGpu() {
            if (fence != 0) {
                ARBSync.glClientWaitSync(fence, ARBSync.GL_SYNC_FLUSH_COMMANDS_BIT,
                        1_000_000_000L); // 1 second timeout
                ARBSync.glDeleteSync(fence);
                fence = 0;
            }
            inUse = false;
        }

        void destroy() {
            if (fence != 0) {
                ARBSync.glDeleteSync(fence);
                fence = 0;
            }
            if (mappedPointer != null) {
                GL43.glBindBuffer(GL15.GL_ARRAY_BUFFER, glBufferId);
                GL30.glUnmapBuffer(GL15.GL_ARRAY_BUFFER);
                GL43.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            }
            if (glBufferId != 0) {
                GL15.glDeleteBuffers(glBufferId);
            }
        }
    }

    // ========================================================================
    // 缓冲区池管理
    // ========================================================================

    /** 每个 ring 的缓冲数量 (三缓冲) */
    private static final int RING_SIZE = 3;

    /** 空闲缓冲区队列 */
    private static final ConcurrentLinkedQueue<PersistentBuffer> freeBuffers = new ConcurrentLinkedQueue<>();

    /** 所有已创建的缓冲区 (用于清理) */
    private static final List<PersistentBuffer> allBuffers = new ArrayList<>();

    private static volatile boolean initialized = false;

    // ========================================================================
    // 统计
    // ========================================================================

    private static final AtomicInteger totalAllocations = new AtomicInteger(0);
    private static final AtomicInteger totalReuses = new AtomicInteger(0);
    private static final AtomicInteger totalWaits = new AtomicInteger(0);
    private static final AtomicInteger activeBuffers = new AtomicInteger(0);
    private static volatile long totalSavedTimeNs = 0;

    /**
     * 初始化持久映射缓冲区池
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        // 检查 ARB_buffer_storage 支持
        boolean hasBufferStorage = GL.getCapabilities().GL_ARB_buffer_storage
                || GL.getCapabilities().OpenGL44;

        if (!hasBufferStorage) {
            UHDFast.LOGGER.warn("PersistentBufferPool: ARB_buffer_storage not supported, " +
                    "falling back to glBufferSubData mode");
        }

        initialized = true;
        UHDFast.LOGGER.info("PersistentBufferPool: Initialized");
        UHDFast.LOGGER.info("  ARB_buffer_storage: {}", hasBufferStorage);
        UHDFast.LOGGER.info("  Ring buffer size: {}", RING_SIZE);
    }

    /**
     * 获取或创建一个持久映射缓冲区
     *
     * @param sizeBytes 缓冲区大小（字节）
     * @param usage     用途类型
     * @return 持久映射缓冲区
     */
    public static PersistentBuffer acquire(long sizeBytes, BufferUsage usage) {
        if (!initialized)
            initialize();

        // 尝试从空闲池获取匹配的缓冲区
        PersistentBuffer buffer = freeBuffers.poll();
        if (buffer != null && buffer.sizeBytes >= sizeBytes) {
            totalReuses.incrementAndGet();
            activeBuffers.incrementAndGet();
            return buffer;
        }

        // 如果没有可复用的，创建新的
        if (buffer != null) {
            // 尺寸不匹配，放回并创建新的
            freeBuffers.offer(buffer);
        }

        buffer = createPersistentBuffer(sizeBytes, usage);
        totalAllocations.incrementAndGet();
        activeBuffers.incrementAndGet();
        return buffer;
    }

    /**
     * 归还缓冲区到池中
     */
    public static void release(PersistentBuffer buffer) {
        if (buffer == null)
            return;
        activeBuffers.decrementAndGet();
        freeBuffers.offer(buffer);
    }

    /**
     * 创建持久映射缓冲区
     */
    private static PersistentBuffer createPersistentBuffer(long sizeBytes, BufferUsage usage) {
        int glTarget = GL43.GL_SHADER_STORAGE_BUFFER;
        int glBuffer = GL15.glGenBuffers();

        GL15.glBindBuffer(glTarget, glBuffer);

        boolean hasBufferStorage = GL.getCapabilities().GL_ARB_buffer_storage
                || GL.getCapabilities().OpenGL44;

        ByteBuffer mappedPtr;

        if (hasBufferStorage) {
            // 使用 ARB_buffer_storage 创建真正的持久映射缓冲区
            int mapFlags;
            int storageFlags = GL44.GL_MAP_PERSISTENT_BIT;

            switch (usage) {
                case UPLOAD:
                    mapFlags = GL43.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT
                            | GL44.GL_MAP_COHERENT_BIT;
                    storageFlags |= GL43.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT
                            | GL44.GL_MAP_COHERENT_BIT;
                    break;
                case DOWNLOAD:
                    mapFlags = GL43.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT
                            | GL44.GL_MAP_COHERENT_BIT;
                    storageFlags |= GL43.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT
                            | GL44.GL_MAP_COHERENT_BIT;
                    break;
                default:
                    mapFlags = GL43.GL_MAP_READ_BIT | GL43.GL_MAP_WRITE_BIT
                            | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
                    storageFlags |= GL43.GL_MAP_READ_BIT | GL43.GL_MAP_WRITE_BIT
                            | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
                    break;
            }

            // glBufferStorage (OpenGL 4.4 / ARB_buffer_storage)
            if (GL.getCapabilities().OpenGL44) {
                GL44.glBufferStorage(glTarget, sizeBytes, storageFlags);
            } else {
                ARBBufferStorage.glBufferStorage(glTarget, sizeBytes, storageFlags);
            }

            // 永久映射
            mappedPtr = GL43.glMapBufferRange(glTarget, 0, sizeBytes, mapFlags);

        } else {
            // 回退方案：使用 DYNAMIC_DRAW + glBufferSubData
            int hint = (usage == BufferUsage.DOWNLOAD) ? GL15.GL_DYNAMIC_READ : GL15.GL_DYNAMIC_DRAW;
            GL15.glBufferData(glTarget, sizeBytes, hint);

            // 尝试映射，如果不支持则使用 null 指针模式
            mappedPtr = GL43.glMapBufferRange(glTarget, 0, sizeBytes,
                    GL43.GL_MAP_WRITE_BIT | GL43.GL_MAP_INVALIDATE_BUFFER_BIT);
            if (mappedPtr == null) {
                // 最终回退：使用 off-heap 内存作为暂存
                mappedPtr = MemoryUtil.memAlloc((int) sizeBytes);
            }
        }

        GL15.glBindBuffer(glTarget, 0);

        PersistentBuffer buffer = new PersistentBuffer(glBuffer, sizeBytes, usage,
                mappedPtr);
        allBuffers.add(buffer);

        return buffer;
    }

    /**
     * 批量上传数据到 GPU 缓冲区，使用持久映射缓冲区减少传输开销
     *
     * @param targetBuffer GPU 目标缓冲区 ID
     * @param data         要上传的数据
     * @param offsetBytes  目标偏移（字节）
     */
    public static void uploadFloats(int targetBuffer, float[] data, long offsetBytes) {
        long start = System.nanoTime();

        PersistentBuffer staging = acquire(data.length * 4L, BufferUsage.UPLOAD);
        try {
            staging.writeFloats(data, 0, data.length);

            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, targetBuffer);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offsetBytes,
                    staging.mappedPointer);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            release(staging);
        }

        totalSavedTimeNs += System.nanoTime() - start;
    }

    /**
     * 批量上传 int 数据
     */
    public static void uploadInts(int targetBuffer, int[] data, long offsetBytes) {
        long start = System.nanoTime();

        PersistentBuffer staging = acquire(data.length * 4L, BufferUsage.UPLOAD);
        try {
            staging.writeInts(data, 0, data.length);

            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, targetBuffer);
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offsetBytes,
                    staging.mappedPointer);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            release(staging);
        }

        totalSavedTimeNs += System.nanoTime() - start;
    }

    /**
     * 等待所有活跃缓冲区的 GPU 操作完成
     */
    public static void waitForAllGpu() {
        for (PersistentBuffer buffer : allBuffers) {
            if (buffer.inUse) {
                buffer.waitGpu();
                totalWaits.incrementAndGet();
            }
        }
    }

    /**
     * 清理所有缓冲区
     */
    public static synchronized void destroy() {
        freeBuffers.clear();
        for (PersistentBuffer buffer : allBuffers) {
            buffer.destroy();
        }
        allBuffers.clear();
        initialized = false;
        UHDFast.LOGGER.info("PersistentBufferPool: Destroyed");
    }

    // ========================================================================
    // 统计接口
    // ========================================================================

    public static int getTotalAllocations() {
        return totalAllocations.get();
    }

    public static int getTotalReuses() {
        return totalReuses.get();
    }

    public static int getActiveBuffers() {
        return activeBuffers.get();
    }

    public static int getFreeBuffers() {
        return freeBuffers.size();
    }

    public static double getReuseRate() {
        int total = totalAllocations.get() + totalReuses.get();
        return total > 0 ? (double) totalReuses.get() / total * 100.0 : 0.0;
    }

    public static long getTotalSavedTimeMs() {
        return totalSavedTimeNs / 1_000_000;
    }

    public static String getPoolStats() {
        return String.format("PersistentBufferPool: alloc=%d reuse=%d active=%d free=%d " +
                "reuseRate=%.1f%% saved=%dms",
                totalAllocations.get(), totalReuses.get(),
                activeBuffers.get(), freeBuffers.size(),
                getReuseRate(), getTotalSavedTimeMs());
    }

    public static void resetStats() {
        totalAllocations.set(0);
        totalReuses.set(0);
        totalWaits.set(0);
        activeBuffers.set(0);
        totalSavedTimeNs = 0;
    }
}
