package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU 资源池 - 统一管理 SSBO、UBO、纹理等 GPU 资源，避免频繁创建/销毁。
 *
 * 核心优化：
 * 1. 对象池模式：复用已分配的 GPU 资源
 * 2. 按类型和大小分类管理
 * 3. 引用计数自动回收
 * 4. 减少驱动开销（glGenBuffers/glDeleteBuffers 调用）
 *
 * 基于 Intel oneAPI 优化最佳实践：
 * - "Resource Pooling" - 减少分配开销
 * - "Object Reuse" - 避免重复创建
 */
public class GPUResourcePool {

    // ========================================================================
    // 资源类型
    // ========================================================================

    public enum ResourceType {
        SSBO, // Shader Storage Buffer Object
        UBO, // Uniform Buffer Object
        VBO, // Vertex Buffer Object
        TEXTURE, // 纹理
        FBO // Frame Buffer Object
    }

    // ========================================================================
    // 资源包装
    // ========================================================================

    public static class GPUResource {
        public final int id;
        public final ResourceType type;
        public final long sizeBytes;
        private int refCount = 0;
        private volatile boolean inPool = false;

        GPUResource(int id, ResourceType type, long sizeBytes) {
            this.id = id;
            this.type = type;
            this.sizeBytes = sizeBytes;
        }

        void retain() {
            refCount++;
            inPool = false;
        }

        void release() {
            refCount--;
            if (refCount <= 0 && !inPool) {
                returnToPool();
            }
        }

        private void returnToPool() {
            GPUResourcePool.releaseResource(this);
        }

        boolean isInUse() {
            return refCount > 0;
        }
    }

    // ========================================================================
    // 资源池
    // ========================================================================

    /** 按类型和大小分类的资源池 */
    private static final Map<String, Queue<GPUResource>> poolsByTypeAndSize = new ConcurrentHashMap<>();

    /** 所有已创建的资源（用于清理） */
    private static final List<GPUResource> allResources = Collections.synchronizedList(new ArrayList<>());

    private static volatile boolean initialized = false;

    // ========================================================================
    // 统计
    // ========================================================================

    private static final AtomicInteger totalCreated = new AtomicInteger(0);
    private static final AtomicInteger totalReused = new AtomicInteger(0);
    private static final AtomicInteger totalReleased = new AtomicInteger(0);
    private static final AtomicInteger activeResources = new AtomicInteger(0);

    /**
     * 初始化资源池
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        initialized = true;
        UHDFast.LOGGER.info("GPUResourcePool: Initialized");
    }

    /**
     * 获取或创建 SSBO
     *
     * @param sizeBytes 缓冲区大小（字节）
     * @return GPU 资源
     */
    public static GPUResource acquireSSBO(long sizeBytes) {
        return acquireResource(ResourceType.SSBO, sizeBytes, GL43.GL_SHADER_STORAGE_BUFFER);
    }

    /**
     * 获取或创建 UBO
     */
    public static GPUResource acquireUBO(long sizeBytes) {
        return acquireResource(ResourceType.UBO, sizeBytes, GL31.GL_UNIFORM_BUFFER);
    }

    /**
     * 获取或创建 VBO
     */
    public static GPUResource acquireVBO(long sizeBytes) {
        return acquireResource(ResourceType.VBO, sizeBytes, GL15.GL_ARRAY_BUFFER);
    }

    /**
     * 获取或创建纹理
     *
     * @param width          宽度
     * @param height         高度
     * @param internalFormat 内部格式
     * @return GPU 资源
     */
    public static GPUResource acquireTexture(int width, int height, int internalFormat) {
        String key = ResourceType.TEXTURE.name() + "_" + width + "x" + height + "_" + internalFormat;

        Queue<GPUResource> pool = poolsByTypeAndSize.get(key);
        GPUResource resource = (pool != null) ? pool.poll() : null;

        if (resource != null) {
            totalReused.incrementAndGet();
            resource.retain();
            activeResources.incrementAndGet();
            return resource;
        }

        // 创建新纹理
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        long sizeBytes = (long) width * height * 4 * 4; // RGBA32F
        resource = new GPUResource(textureId, ResourceType.TEXTURE, sizeBytes);
        resource.retain();
        allResources.add(resource);
        totalCreated.incrementAndGet();
        activeResources.incrementAndGet();

        return resource;
    }

    /**
     * 获取或创建 FBO
     */
    public static GPUResource acquireFBO() {
        String key = ResourceType.FBO.name() + "_default";

        Queue<GPUResource> pool = poolsByTypeAndSize.get(key);
        GPUResource resource = (pool != null) ? pool.poll() : null;

        if (resource != null) {
            totalReused.incrementAndGet();
            resource.retain();
            activeResources.incrementAndGet();
            return resource;
        }

        // 创建新 FBO
        int fboId = GL30.glGenFramebuffers();

        resource = new GPUResource(fboId, ResourceType.FBO, 0);
        resource.retain();
        allResources.add(resource);
        totalCreated.incrementAndGet();
        activeResources.incrementAndGet();

        return resource;
    }

    /**
     * 通用资源获取
     */
    private static GPUResource acquireResource(ResourceType type, long sizeBytes, int glTarget) {
        if (!initialized)
            initialize();

        String key = type.name() + "_" + sizeBytes;

        Queue<GPUResource> pool = poolsByTypeAndSize.get(key);
        GPUResource resource = (pool != null) ? pool.poll() : null;

        if (resource != null) {
            totalReused.incrementAndGet();
            resource.retain();
            activeResources.incrementAndGet();
            return resource;
        }

        // 创建新缓冲区
        int[] buffers = new int[1];
        GL15.glGenBuffers(buffers);
        int bufferId = buffers[0];

        GL15.glBindBuffer(glTarget, bufferId);
        int usageHint = (type == ResourceType.UBO) ? GL15.GL_DYNAMIC_DRAW : GL15.GL_DYNAMIC_DRAW;
        GL15.glBufferData(glTarget, sizeBytes, usageHint);
        GL15.glBindBuffer(glTarget, 0);

        resource = new GPUResource(bufferId, type, sizeBytes);
        resource.retain();
        allResources.add(resource);
        totalCreated.incrementAndGet();
        activeResources.incrementAndGet();

        return resource;
    }

    /**
     * 归还资源到池中
     */
    public static void releaseResource(GPUResource resource) {
        if (resource == null)
            return;

        activeResources.decrementAndGet();
        totalReleased.incrementAndGet();

        String key;
        if (resource.type == ResourceType.TEXTURE) {
            // 纹理需要特殊处理 key
            key = resource.type.name() + "_custom";
        } else if (resource.type == ResourceType.FBO) {
            key = resource.type.name() + "_default";
        } else {
            key = resource.type.name() + "_" + resource.sizeBytes;
        }

        Queue<GPUResource> pool = poolsByTypeAndSize.computeIfAbsent(key,
                k -> new java.util.concurrent.ConcurrentLinkedQueue<>());
        pool.offer(resource);
        resource.inPool = true;
    }

    /**
     * 批量释放资源
     */
    public static void releaseResources(List<GPUResource> resources) {
        for (GPUResource resource : resources) {
            resource.release();
        }
    }

    /**
     * 清理所有资源
     */
    public static synchronized void destroy() {
        poolsByTypeAndSize.clear();

        for (GPUResource resource : allResources) {
            switch (resource.type) {
                case SSBO:
                case UBO:
                case VBO:
                    GL15.glDeleteBuffers(resource.id);
                    break;
                case TEXTURE:
                    GL11.glDeleteTextures(resource.id);
                    break;
                case FBO:
                    GL30.glDeleteFramebuffers(resource.id);
                    break;
            }
        }

        allResources.clear();
        initialized = false;
        UHDFast.LOGGER.info("GPUResourcePool: Destroyed");
    }

    // ========================================================================
    // 统计接口
    // ========================================================================

    public static int getTotalCreated() {
        return totalCreated.get();
    }

    public static int getTotalReused() {
        return totalReused.get();
    }

    public static int getTotalReleased() {
        return totalReleased.get();
    }

    public static int getActiveResources() {
        return activeResources.get();
    }

    @SuppressWarnings("null")
    public static int getPooledResources() {
        return poolsByTypeAndSize.values().stream()
                .mapToInt(Queue::size)
                .sum();
    }

    public static double getReuseRate() {
        int total = totalCreated.get() + totalReused.get();
        return total > 0 ? (double) totalReused.get() / total * 100.0 : 0.0;
    }

    public static String getPoolStats() {
        return String.format("GPUResourcePool: created=%d reused=%d released=%d active=%d pooled=%d " +
                "reuseRate=%.1f%%",
                totalCreated.get(), totalReused.get(), totalReleased.get(),
                activeResources.get(), getPooledResources(), getReuseRate());
    }

    public static void resetStats() {
        totalCreated.set(0);
        totalReused.set(0);
        totalReleased.set(0);
        activeResources.set(0);
    }
}
