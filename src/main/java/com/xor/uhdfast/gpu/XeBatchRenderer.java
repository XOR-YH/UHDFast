package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XeBatchRenderer {

    private static final List<BatchDrawCall> batchQueue = new ArrayList<>();
    private static int totalBatchesProcessed = 0;
    private static int totalDrawCallsBatched = 0;
    private static int maxBatchSize = 500;
    private static boolean initialized = false;

    private static int batchVertexBuffer = 0;
    private static int batchInstanceBuffer = 0;

    public static class BatchDrawCall {
        public final int vaoId;
        public final int vertexCount;
        public final int instanceCount;
        public final int drawMode;
        public final int instanceDataOffset;
        public final boolean hasInstanceData;

        public BatchDrawCall(int vaoId, int vertexCount, int instanceCount, int drawMode, int instanceDataOffset,
                boolean hasInstanceData) {
            this.vaoId = vaoId;
            this.vertexCount = vertexCount;
            this.instanceCount = instanceCount;
            this.drawMode = drawMode;
            this.instanceDataOffset = instanceDataOffset;
            this.hasInstanceData = hasInstanceData;
        }
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Batch renderer disabled - not Xe architecture");
            return;
        }

        int euCount = IntelXeDetector.getEUCount();

        if (euCount <= 32) {
            maxBatchSize = 512; // 从 256 提升
        } else if (euCount <= 80) {
            maxBatchSize = 1024; // 从 512 提升
        } else {
            maxBatchSize = 2048; // 从 1024 提升
        }

        batchVertexBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchVertexBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 16 * 1024 * 1024, GL15.GL_DYNAMIC_DRAW);

        batchInstanceBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchInstanceBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 8 * 1024 * 1024, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        UHDFast.LOGGER.info("Xe Batch Renderer initialized");
        UHDFast.LOGGER.info("  Max batch size: {}", maxBatchSize);
        UHDFast.LOGGER.info("  EU Count: {}", euCount);
        UHDFast.LOGGER.info("  Optimized for Xe execution unit parallelism");

        initialized = true;
    }

    public static void addBatchDrawCall(int vaoId, int vertexCount, int instanceCount, int drawMode,
            float[] instanceData) {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return;
        }

        int instanceOffset = 0;
        @SuppressWarnings("all")
        boolean hasData = instanceData != null && instanceData.length > 0;
        if (hasData) {
            @SuppressWarnings("all")
            int len = instanceData.length;
            FloatBuffer fb = BufferUtils.createFloatBuffer(len);
            fb.put(instanceData);
            fb.flip();
            instanceOffset = 0;
            ByteBuffer bb = BufferUtils.createByteBuffer(len * 4);
            bb.asFloatBuffer().put(fb);
            bb.rewind();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchInstanceBuffer);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, bb);
        }
        BatchDrawCall batch = new BatchDrawCall(vaoId, vertexCount, instanceCount, drawMode, instanceOffset, hasData);
        batchQueue.add(batch);

        if (batchQueue.size() >= maxBatchSize) {
            flushBatchQueue();
        }
    }

    public static void flushBatchQueue() {
        if (!IntelXeDetector.isXeArchitecture() || batchQueue.isEmpty()) {
            return;
        }

        // 检查 MDI 是否可用且已启用
        MDIBufferManager mdiManager = MDIBufferManager.getInstance();
        if (!mdiManager.isFallbackActive() && mdiManager.isAvailable() && UHDFastConfig.enableMDI.get()) {
            flushBatchQueueMDI();
            return;
        }

        // 旧路径：逐个提交（MDI 不可用或未启用时的 fallback）
        long startTime = System.nanoTime();

        for (BatchDrawCall batch : batchQueue) {
            GL30.glBindVertexArray(batch.vaoId);

            if (batch.instanceCount > 1) {
                GL31.glDrawArraysInstanced(batch.drawMode, 0, batch.vertexCount, batch.instanceCount);
            } else {
                GL11.glDrawArrays(batch.drawMode, 0, batch.vertexCount);
            }

            totalDrawCallsBatched++;
        }

        GL30.glBindVertexArray(0);

        long endTime = System.nanoTime();
        long batchTime = endTime - startTime;

        totalBatchesProcessed++;

        UHDFast.LOGGER.debug("Batch processed: {} draw calls in {} ms",
                batchQueue.size(), batchTime / 1_000_000.0);

        batchQueue.clear();
    }

    /** 缓存的分组 Map，避免每帧分配 HashMap 和 ArrayList */
    private static final Map<Integer, List<BatchDrawCall>> cachedGroups = new HashMap<>();

    /**
     * 使用 Multi-Draw Indirect (MDI) 批量提交所有绘制调用。
     * 按 VAO 分组，每组绑定 VAO 一次后填充命令到 staging buffer，
     * 每组的命令在 flushAll() 中一次性上传 GPU 并提交 glMultiDrawArraysIndirect。
     * 仅在 {@link #flushBatchQueue()} 检测到 MDI 可用时调用。
     * 
     * 优化：使用 staging buffer 减少每命令的 glBufferSubData 开销。
     * 优化：复用缓存 HashMap 避免每帧分配。
     */
    private static void flushBatchQueueMDI() {
        long startTime = System.nanoTime();

        MDIBufferManager mdiManager = MDIBufferManager.getInstance();
        mdiManager.beginFrame();

        // 按 vaoId 分组，以便每个 VAO 只绑定一次
        // 复用缓存 HashMap 避免每帧分配
        for (BatchDrawCall batch : batchQueue) {
            cachedGroups.computeIfAbsent(batch.vaoId, k -> new ArrayList<>()).add(batch);
        }

        for (Map.Entry<Integer, List<BatchDrawCall>> entry : cachedGroups.entrySet()) {
            int vaoId = entry.getKey();
            List<BatchDrawCall> draws = entry.getValue();

            // 绑定 VAO 一次
            GL30.glBindVertexArray(vaoId);

            // 填充该 VAO 对应的所有间接绘制命令到 staging buffer
            for (BatchDrawCall batch : draws) {
                mdiManager.appendDrawCommand(0, batch.vertexCount, batch.instanceCount);
                totalDrawCallsBatched++;
            }

            // 一次性提交该 VAO 组的所有绘制调用
            mdiManager.flushAll();
        }

        GL30.glBindVertexArray(0);

        mdiManager.endFrame();

        // 清空缓存的内层列表，供下次复用
        for (List<BatchDrawCall> list : cachedGroups.values()) {
            list.clear();
        }
        cachedGroups.clear();

        long endTime = System.nanoTime();
        long batchTime = endTime - startTime;

        totalBatchesProcessed++;

        UHDFast.LOGGER.debug("Batch processed (MDI): {} draw calls in {} ms",
                batchQueue.size(), batchTime / 1_000_000.0);

        batchQueue.clear();
    }

    public static void addInstancedDrawCall(int vaoId, int vertexCount, int instanceCount,
            FloatBuffer instanceData) {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return;
        }

        float[] data = new float[instanceData.remaining()];
        instanceData.get(data);

        addBatchDrawCall(vaoId, vertexCount, instanceCount, GL11.GL_TRIANGLES, data);
    }

    public static void addMeshDrawCall(int vaoId, int vertexCount) {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return;
        }

        addBatchDrawCall(vaoId, vertexCount, 1, GL11.GL_TRIANGLES, null);
    }

    public static int getQueueSize() {
        return batchQueue.size();
    }

    public static int getTotalBatchesProcessed() {
        return totalBatchesProcessed;
    }

    public static int getTotalDrawCallsBatched() {
        return totalDrawCallsBatched;
    }

    public static int getPendingBatchCount() {
        return batchQueue.size();
    }

    public static double getAverageBatchSize() {
        if (totalBatchesProcessed == 0) {
            return 0;
        }
        return (double) totalDrawCallsBatched / totalBatchesProcessed;
    }

    public static void resetStats() {
        totalBatchesProcessed = 0;
        totalDrawCallsBatched = 0;
        batchQueue.clear();
    }

    public static void resetFrame() {
        // No-op: standard GL buffers don't need write offset reset
    }

    public static void destroy() {
        if (batchVertexBuffer != 0) {
            GL15.glDeleteBuffers(batchVertexBuffer);
            batchVertexBuffer = 0;
        }
        if (batchInstanceBuffer != 0) {
            GL15.glDeleteBuffers(batchInstanceBuffer);
            batchInstanceBuffer = 0;
        }
    }
}
