package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * 多画间接（MDI）缓冲区管理器 — 管理 OpenGL 间接绘制缓冲区 (GL_DRAW_INDIRECT_BUFFER)
 * 和 mega-buffer（单一大型 VBO + IBO，供所有 chunk 共享顶点数据）。
 *
 * <p>核心功能：
 * <ul>
 *   <li>使用 {@link GL43#glMultiDrawArraysIndirect} 兼容的 {@code DrawArraysIndirectCommand}
 *       结构体（每个命令 = 4 × GLuint = 16 字节）</li>
 *   <li>间接缓冲区从 1MB 起始，满时自动 2 倍扩容</li>
 *   <li>每帧重置写入指针为 0，然后追加间接命令</li>
 *   <li>提供 mega-buffer VBO (16MB) 和 IBO (8MB) 的创建与销毁</li>
 *   <li>当 GL 版本低于 4.3 时自动进入 fallback 模式，flushAll() 不执行任何操作</li>
 * </ul>
 *
 * <p><strong>线程安全：</strong>本类所有方法必须在渲染线程中调用，非线程安全。
 */
public class MDIBufferManager {

    // ========================================================================
    // 常量
    // ========================================================================

    /** 间接缓冲区初始大小：1MB */
    private static final int INITIAL_INDIRECT_BUFFER_SIZE = 1_048_576;

    /** Mega-buffer VBO 大小：16MB */
    private static final int MEGA_VBO_SIZE = 16 * 1_048_576;

    /** Mega-buffer IBO 大小：8MB */
    private static final int MEGA_IBO_SIZE = 8 * 1_048_576;

    /** 每个 DrawArraysIndirectCommand 结构体字节数 (4 × GLuint) */
    private static final int COMMAND_STRIDE = 16;

    /** 初始 staging 缓冲区大小（64KB） */
    private static final int INITIAL_STAGING_SIZE = 64 * 1024;

    // ========================================================================
    // 状态
    // ========================================================================

    /** 间接绘制缓冲区 GL 对象 ID */
    private int indirectBuffer;

    /** Mega-buffer VBO GL 对象 ID */
    private int megaVBO;

    /** Mega-buffer IBO GL 对象 ID */
    private int megaIBO;

    /** 当前间接缓冲区大小（字节） */
    private int bufferSize;

    /** 当前帧已提交的命令数量 */
    private int commandCount;

    /** 当前帧写入指针（字节偏移） */
    @SuppressWarnings("unused")
    private int writePointer;

    /** 是否已初始化 */
    private boolean initialized;

    /** 是否处于 fallback 模式（GL 版本低于 4.3） */
    private boolean fallback;

    /** 单例实例 */
    private static MDIBufferManager instance;

    // ========================================================================
    // 批量写入优化：使用 staging 缓冲区减少 glBufferSubData 调用次数
    // ========================================================================

    /** staging 缓冲区，累积所有命令后一次性写入 GPU */
    private ByteBuffer stagingBuffer;

    /** staging 缓冲区当前大小 */
    private int stagingCapacity;

    // ========================================================================
    // 单例
    // ========================================================================

    /**
     * 获取 MDIBufferManager 单例实例。
     *
     * @return 单例实例
     */
    public static synchronized MDIBufferManager getInstance() {
        if (instance == null) {
            instance = new MDIBufferManager();
        }
        return instance;
    }

    // ========================================================================
    // 统计
    // ========================================================================

    /** 累计提交的绘制调用总数 */
    private long totalDraws;

    /** 单帧最大命令数 */
    private int maxDrawsPerFrame;

    // ========================================================================
    // 初始化与检测
    // ========================================================================

    /**
     * 检查当前 OpenGL 上下文是否支持间接绘制（版本 >= 4.3）。
     *
     * @return 如果 GL 版本 >= 4.3 返回 true
     */
    public boolean isAvailable() {
        return GL.getCapabilities().OpenGL43;
    }

    /**
     * 初始化间接缓冲区、mega-buffer VBO 和 IBO。
     * 如果 GL 版本低于 4.3，则标记为 fallback 模式，仅创建 mega-buffer 缓冲区。
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        // 检查 GL 版本
        fallback = !isAvailable();
        if (fallback) {
            UHDFast.LOGGER.warn("MDIBufferManager: OpenGL 4.3 not available, " +
                    "indirect drawing disabled (fallback mode)");
        }

        // 创建间接缓冲区（仅在非 fallback 模式下有效）
        if (!fallback) {
            indirectBuffer = GL15.glGenBuffers();
            bufferSize = INITIAL_INDIRECT_BUFFER_SIZE;
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
            GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, bufferSize, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);

            // 初始化 staging 缓冲区
            stagingCapacity = INITIAL_STAGING_SIZE;
            stagingBuffer = MemoryUtil.memAlloc(stagingCapacity);

            UHDFast.LOGGER.info("MDIBufferManager: Indirect buffer created (size={} bytes, maxCommands={})",
                    bufferSize, bufferSize / COMMAND_STRIDE);
        }

        // 创建 mega-buffer VBO
        megaVBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, megaVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, MEGA_VBO_SIZE, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // 创建 mega-buffer IBO
        megaIBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, megaIBO);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, MEGA_IBO_SIZE, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);

        resetStats();

        initialized = true;

        UHDFast.LOGGER.info("MDIBufferManager: Initialized");
        UHDFast.LOGGER.info("  Mega VBO: {} bytes", MEGA_VBO_SIZE);
        UHDFast.LOGGER.info("  Mega IBO: {} bytes", MEGA_IBO_SIZE);
        UHDFast.LOGGER.info("  Fallback mode: {}", fallback);
    }

    // ========================================================================
    // 帧生命周期
    // ========================================================================

    /**
     * 开始新帧，重置写入指针和命令计数。
     * 必须在渲染线程每帧开始时调用。
     */
    public void beginFrame() {
        if (!initialized) {
            return;
        }
        writePointer = 0;
        commandCount = 0;
        // 重置 staging 缓冲区写入位置
        if (stagingBuffer != null) {
            stagingBuffer.clear();
        }
    }

    /**
     * 追加一个 {@code DrawArraysIndirectCommand} 到间接缓冲区。
     * 使用 staging 缓冲区批量累积，避免每命令调用 glBufferSubData。
     * 如果缓冲区空间不足，自动触发扩容。
     *
     * @param firstVertex   起始顶点索引
     * @param vertexCount   顶点数量
     * @param instanceCount 实例数量
     */
    public void appendDrawCommand(int firstVertex, int vertexCount, int instanceCount) {
        if (!initialized || fallback) {
            return;
        }

        // 先检查 staging 缓冲区空间，必要时扩容
        ensureStagingCapacity(COMMAND_STRIDE);

        // 写入 staging 缓冲区（不调用 glBufferSubData）
        // 每个 DrawArraysIndirectCommand = { count, primCount, first, baseInstance }
        stagingBuffer.putInt(vertexCount);     // count
        stagingBuffer.putInt(instanceCount);   // primCount
        stagingBuffer.putInt(firstVertex);     // first
        stagingBuffer.putInt(0);               // baseInstance

        writePointer += COMMAND_STRIDE;
        commandCount++;
    }

    /**
     * 确保 staging 缓冲区有至少 {@code requiredBytes} 字节的剩余空间。
     * 不足时自动扩容为当前大小的 2 倍。
     */
    private void ensureStagingCapacity(int requiredBytes) {
        if (stagingBuffer == null) {
            stagingCapacity = INITIAL_STAGING_SIZE;
            stagingBuffer = MemoryUtil.memAlloc(stagingCapacity);
        }

        int remaining = stagingBuffer.capacity() - stagingBuffer.position();
        if (remaining < requiredBytes) {
            // 扩容为当前大小的 2 倍
            int newCapacity = stagingCapacity * 2;
            ByteBuffer newBuffer = MemoryUtil.memAlloc(newCapacity);
            stagingBuffer.flip();
            newBuffer.put(stagingBuffer);
            MemoryUtil.memFree(stagingBuffer);
            stagingBuffer = newBuffer;
            stagingCapacity = newCapacity;
        }
    }

    /**
     * 结束当前帧。当前实现仅记录统计信息。
     */
    public void endFrame() {
        if (!initialized) {
            return;
        }

        totalDraws += commandCount;
        if (commandCount > maxDrawsPerFrame) {
            maxDrawsPerFrame = commandCount;
        }

        UHDFast.LOGGER.debug("MDIBufferManager: Frame end - commands={}, bufferSize={}",
                commandCount, bufferSize);
    }

    // ========================================================================
    // 提交绘制
    // ========================================================================

    /**
     * 使用 {@link GL43#glMultiDrawArraysIndirect} 一次性提交所有命令。
     * <p>在 fallback 模式下（GL 版本 < 4.3），此方法不执行任何操作。
     * <p>优化：使用 staging 缓冲区批量写入，将多次 glBufferSubData 合并为一次。
     */
    public void flushAll() {
        if (!initialized || fallback || commandCount == 0) {
            return;
        }

        // 限制 staging 缓冲区到实际写入大小
        stagingBuffer.flip();

        // 确保 GPU 间接缓冲区足够大
        int requiredBytes = stagingBuffer.remaining();
        if (requiredBytes > bufferSize) {
            growBufferInternal(requiredBytes);
        }

        // 一次性写入所有命令到 GPU 间接缓冲区（单次 glBufferSubData）
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 0, stagingBuffer);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);

        // 提交所有间接绘制命令
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, 0, commandCount, 0);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);

        // 重置 staging 缓冲区，为下一组命令（不同 VAO）做准备
        stagingBuffer.clear();
        writePointer = 0;
        commandCount = 0;
    }

    // ========================================================================
    // 缓冲区扩容
    // ========================================================================

    /**
     * 将间接缓冲区扩容到至少 {@code requiredBytes} 大小。
     * 通过创建新缓冲区、删除旧缓冲区实现，不再拷贝旧数据（因为 staging buffer 总会重新写入全量数据）。
     */
    private void growBufferInternal(int requiredBytes) {
        int newSize = bufferSize;
        while (newSize < requiredBytes) {
            newSize *= 2;
        }
        int newBuffer = GL15.glGenBuffers();

        // 创建新缓冲区
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, newBuffer);
        GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, newSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);

        // 删除旧缓冲区
        GL15.glDeleteBuffers(indirectBuffer);

        indirectBuffer = newBuffer;
        bufferSize = newSize;

        UHDFast.LOGGER.info("MDIBufferManager: Indirect buffer grown to {} bytes (maxCommands={})",
                bufferSize, bufferSize / COMMAND_STRIDE);
    }

    // ========================================================================
    // GPU视锥剔除结果应用
    // ========================================================================

    /**
     * 应用 GPU 视锥剔除结果到间接绘制缓冲区。
     * <p>
     * 将可见性数组映射到间接命令缓冲区中的每个命令条目，
     * 对不可见的命令将 primCount 设为 0，从而跳过其绘制。
     * </p>
     *
     * @param visibility 可见性数组，visibility[i] = true 表示命令 i 可见
     * @param count      要处理的可见性条目数量（不超过当前命令数量）
     */
    public void applyCullingResults(boolean[] visibility, int count) {
        if (!initialized || fallback || indirectBuffer == 0) {
            return;
        }

        int processCount = Math.min(count, commandCount);
        if (processCount <= 0 || visibility == null) {
            return;
        }

        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        try {
            // 映射间接缓冲区以写入
            ByteBuffer mappedBuffer = GL30.glMapBufferRange(GL40.GL_DRAW_INDIRECT_BUFFER,
                    0, processCount * COMMAND_STRIDE,
                    GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT);

            if (mappedBuffer == null) {
                UHDFast.LOGGER.warn("MDIBufferManager: Failed to map indirect buffer for culling results");
                return;
            }

            int culledCount = 0;
            for (int i = 0; i < processCount; i++) {
                int offset = i * COMMAND_STRIDE;
                // 每个 DrawArraysIndirectCommand = { count (4B), primCount (4B), first (4B), baseInstance (4B) }
                // primCount 位于 offset + 4 处
                if (i < visibility.length && !visibility[i]) {
                    // 该命令被剔除，将 primCount 设为 0
                    mappedBuffer.putInt(offset + 4, 0);
                    culledCount++;
                }
                // 可见命令保持 primCount 不变
            }

            GL15.glUnmapBuffer(GL40.GL_DRAW_INDIRECT_BUFFER);

            if (culledCount > 0) {
                UHDFast.LOGGER.debug("MDIBufferManager: Culled {} of {} draw commands via GPU frustum culling",
                        culledCount, processCount);
            }
        } catch (Exception e) {
            UHDFast.LOGGER.warn("MDIBufferManager: Failed to apply culling results: {}", e.getMessage());
        } finally {
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        }
    }

    // ========================================================================
    // 查询
    // ========================================================================

    /**
     * 返回当前帧的命令数量。
     *
     * @return 命令计数
     */
    public int getCommandCount() {
        return commandCount;
    }

    /**
     * 检查是否处于 fallback 模式（GL 版本低于 4.3，间接绘制不可用）。
     *
     * @return true 表示处于 fallback 模式
     */
    public boolean isFallbackActive() {
        return fallback;
    }

    /**
     * 获取 Mega-buffer VBO 的 GL 对象 ID。
     *
     * @return VBO ID
     */
    public int getMegaVBO() {
        return megaVBO;
    }

    /**
     * 获取 Mega-buffer IBO 的 GL 对象 ID。
     *
     * @return IBO ID
     */
    public int getMegaIBO() {
        return megaIBO;
    }

    /**
     * 获取间接缓冲区 GL 对象 ID。
     *
     * @return 间接缓冲区 ID
     */
    public int getIndirectBuffer() {
        return indirectBuffer;
    }

    // ========================================================================
    // 统计接口
    // ========================================================================

    /**
     * 获取累计提交的绘制调用总数。
     *
     * @return 总绘制次数
     */
    public long getTotalDraws() {
        return totalDraws;
    }

    /**
     * 获取当前间接缓冲区大小（字节）。
     *
     * @return 缓冲区大小（字节）
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * 获取单帧最大命令数。
     *
     * @return 最大命令数
     */
    public int getMaxDrawsPerFrame() {
        return maxDrawsPerFrame;
    }

    /**
     * 获取格式化统计信息字符串。
     *
     * @return 统计信息
     */
    public String getStats() {
        return String.format("MDIBufferManager: totalDraws=%d bufferSize=%d maxDrawsPerFrame=%d fallback=%b",
                totalDraws, bufferSize, maxDrawsPerFrame, fallback);
    }

    /**
     * 重置所有统计计数器。
     */
    public void resetStats() {
        totalDraws = 0;
        maxDrawsPerFrame = 0;
    }

    // ========================================================================
    // 清理
    // ========================================================================

    /**
     * 销毁所有 GL 缓冲区资源。必须在渲染线程中调用。
     */
    public void destroy() {
        if (!initialized) {
            return;
        }

        if (indirectBuffer != 0) {
            GL15.glDeleteBuffers(indirectBuffer);
            indirectBuffer = 0;
        }

        if (megaVBO != 0) {
            GL15.glDeleteBuffers(megaVBO);
            megaVBO = 0;
        }

        if (megaIBO != 0) {
            GL15.glDeleteBuffers(megaIBO);
            megaIBO = 0;
        }

        // 释放 staging 缓冲区
        if (stagingBuffer != null) {
            MemoryUtil.memFree(stagingBuffer);
            stagingBuffer = null;
        }
        stagingCapacity = 0;

        bufferSize = 0;
        writePointer = 0;
        commandCount = 0;
        initialized = false;

        UHDFast.LOGGER.info("MDIBufferManager: Destroyed");
    }
}