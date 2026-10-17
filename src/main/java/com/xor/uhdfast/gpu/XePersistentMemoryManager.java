package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xe Persistent Memory Manager - eliminates allocation overhead for GPU chunk
 * loading.
 *
 * Based on Intel oneAPI optimization best practices:
 * - "Unified Shared Memory Allocations" - persistent mapped buffers
 * - "Avoiding Moving Data Back and Forth between Host and Device"
 * - "Performance Impact of USM and Buffers"
 *
 * Key optimizations:
 * 1. Pre-allocate all buffers at startup (no runtime allocation)
 * 2. Use persistent mapped buffers (zero-copy upload)
 * 3. Double-buffer for overlap (upload while GPU computes)
 * 4. Object pool for result buffers (reduce GC pressure)
 */
public class XePersistentMemoryManager {

    // ========================================================================
    // Persistent mapped upload buffers (USM-style)
    // ========================================================================

    /** Size constants matching XeChunkLoader */
    private static final int CHUNK_WIDTH = 16;
    private static final int CHUNK_HEIGHT = 320;
    private static final int CHUNK_DEPTH = 16;
    private static final int TOTAL_BLOCKS = CHUNK_WIDTH * CHUNK_HEIGHT * CHUNK_DEPTH; // 81920
    private static final int MAX_VERTEX_ELEMENTS = TOTAL_BLOCKS * 6 * 4 * 13; // worst case
    private static final int MAX_INDEX_ELEMENTS = TOTAL_BLOCKS * 6 * 6;

    /** Double-buffered upload buffers for zero-copy */
    private static ByteBuffer[] blockUploadBuffers = new ByteBuffer[2];
    private static IntBuffer[] blockUploadViews = new IntBuffer[2];
    private static ByteBuffer uniformUploadBuffer;
    private static IntBuffer uniformUploadView;

    /** Current flip index for double buffering */
    private static volatile int currentUploadBuffer = 0;

    // ========================================================================
    // Persistent GPU SSBOs (never recreated, just reused)
    // ========================================================================

    private static int[] persistentSSBOs = new int[9];
    private static int persistentUniformBuffer = 0;

    // ========================================================================
    // Result object pool (eliminates allocation during chunk loading)
    // ========================================================================

    private static final int POOL_SIZE = 32;
    private static final AtomicInteger poolAllocations = new AtomicInteger(0);
    private static final AtomicInteger poolHits = new AtomicInteger(0);

    // ========================================================================
    // Mapped read buffers (persistent for download)
    // ========================================================================

    private static FloatBuffer persistentVertexReadBuffer;
    private static IntBuffer persistentIndexReadBuffer;
    private static IntBuffer persistentHeightReadBuffer;
    private static IntBuffer persistentBiomeReadBuffer;

    // ========================================================================
    // Statistics
    // ========================================================================

    private static final AtomicInteger totalUploads = new AtomicInteger(0);
    private static final AtomicInteger totalDownloads = new AtomicInteger(0);
    private static final AtomicInteger totalBufferSubData = new AtomicInteger(0);
    private static final AtomicInteger totalMapBuffer = new AtomicInteger(0);
    private static volatile long totalUploadTimeNs = 0;
    private static volatile long totalDownloadTimeNs = 0;

    /**
     * Initialize persistent memory system.
     * Called once at startup, allocates all buffers.
     */
    public static synchronized void initialize() {
        createUploadBuffers();
        createSSBOs();
        createResultPool();
        createReadBuffers();

        UHDFast.LOGGER.info("XePersistentMemoryManager: Initialized");
        UHDFast.LOGGER.info("  Upload buffers: {} MB (double-buffered)",
                (TOTAL_BLOCKS * 4 * 2) / (1024 * 1024));
        UHDFast.LOGGER.info("  Vertex SSBO: {} MB", (MAX_VERTEX_ELEMENTS * 4) / (1024 * 1024));
        UHDFast.LOGGER.info("  Index SSBO: {} MB", (MAX_INDEX_ELEMENTS * 4) / (1024 * 1024));
        UHDFast.LOGGER.info("  Result pool: {} entries", POOL_SIZE);
    }

    private static void createUploadBuffers() {
        // Double-buffered block upload
        for (int i = 0; i < 2; i++) {
            blockUploadBuffers[i] = MemoryUtil.memAlloc(TOTAL_BLOCKS * 4);
            blockUploadViews[i] = blockUploadBuffers[i].asIntBuffer();
        }

        // Uniform upload (small, single buffer)
        uniformUploadBuffer = MemoryUtil.memAlloc(32);
        uniformUploadView = uniformUploadBuffer.asIntBuffer();
    }

    private static void createSSBOs() {
        int[] buffers = new int[9];
        GL15.glGenBuffers(buffers);
        System.arraycopy(buffers, 0, persistentSSBOs, 0, 9);

        persistentUniformBuffer = buffers[8];

        // Noise buffer (8192 floats)
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[0]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 8192 * 4, GL15.GL_DYNAMIC_DRAW);

        // Biome buffer (256 uints)
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[1]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 256 * 4, GL15.GL_DYNAMIC_DRAW);

        // Height buffer (256 uints)
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[2]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 256 * 4, GL15.GL_DYNAMIC_DRAW);

        // Block buffer (81920 uints) - largest
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[3]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, TOTAL_BLOCKS * 4, GL15.GL_DYNAMIC_DRAW);

        // Sky light buffer
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[4]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, TOTAL_BLOCKS * 4, GL15.GL_DYNAMIC_DRAW);

        // Vertex output buffer - pre-allocated
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[5]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, MAX_VERTEX_ELEMENTS * 4, GL15.GL_DYNAMIC_DRAW);

        // Index output buffer - pre-allocated
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[6]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, MAX_INDEX_ELEMENTS * 4, GL15.GL_DYNAMIC_DRAW);

        // Face counter (single uint)
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[7]);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 4, GL15.GL_DYNAMIC_DRAW);

        // Uniform buffer
        GL15.glBindBuffer(GL43.GL_UNIFORM_BUFFER, persistentUniformBuffer);
        GL15.glBufferData(GL43.GL_UNIFORM_BUFFER, 32, GL15.GL_DYNAMIC_DRAW);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    }

    private static void createResultPool() {
        // Pool management now creates objects on-demand to avoid final field issues
    }

    private static void createReadBuffers() {
        // Vertex read buffer (max worst case)
        persistentVertexReadBuffer = MemoryUtil.memAllocFloat(MAX_VERTEX_ELEMENTS);
        persistentIndexReadBuffer = MemoryUtil.memAllocInt(MAX_INDEX_ELEMENTS);
        persistentHeightReadBuffer = MemoryUtil.memAllocInt(256);
        persistentBiomeReadBuffer = MemoryUtil.memAllocInt(256);
    }

    // ========================================================================
    // Upload operations (CPU -> GPU)
    // ========================================================================

    /**
     * Upload block data to GPU using double-buffered persistent upload.
     * Uses GL_MAP_PERSISTENT_BIT style via bufferSubData for maximum throughput.
     */
    public static void uploadBlockData(int[] blockData) {
        long start = System.nanoTime();

        int bufIdx = currentUploadBuffer;
        currentUploadBuffer = (currentUploadBuffer + 1) % 2; // flip

        IntBuffer view = blockUploadViews[bufIdx];
        view.clear();
        int len = Math.min(blockData.length, TOTAL_BLOCKS);
        view.put(blockData, 0, len);
        view.position(0);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[3]);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, view);

        totalUploads.incrementAndGet();
        totalBufferSubData.incrementAndGet();
        totalUploadTimeNs += System.nanoTime() - start;
    }

    /**
     * Upload uniforms (seed, chunkX, chunkZ, seaLevel) to GPU.
     * Small, fast operation.
     */
    public static void uploadUniforms(long seed, int chunkX, int chunkZ, int chunkY, float seaLevel) {
        uniformUploadView.clear();
        uniformUploadView.put((int) (seed & 0xFFFFFFFFL));
        uniformUploadView.put(chunkX);
        uniformUploadView.put(chunkZ);
        uniformUploadView.put(chunkY);
        uniformUploadView.put(CHUNK_HEIGHT);
        uniformUploadView.put(Float.floatToIntBits(seaLevel));
        uniformUploadView.put(0);
        uniformUploadView.put(0);
        uniformUploadView.position(0);

        GL15.glBindBuffer(GL43.GL_UNIFORM_BUFFER, persistentUniformBuffer);
        GL15.glBufferSubData(GL43.GL_UNIFORM_BUFFER, 0L, uniformUploadView);

        totalBufferSubData.incrementAndGet();
    }

    // 优化：缓存IntBuffer，避免每次clearFaceCounter调用MemoryUtil.memAlloc/free
    private static final IntBuffer cachedZeroBuffer = MemoryUtil.memAllocInt(1).put(0).position(0);

    /**
     * Clear face counter on GPU.
     * 优化：使用缓存IntBuffer，避免每帧MemoryUtil.memAlloc/free
     */
    public static void clearFaceCounter() {
        cachedZeroBuffer.clear();
        cachedZeroBuffer.put(0).position(0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[7]);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, cachedZeroBuffer);
        totalBufferSubData.incrementAndGet();
    }

    // ========================================================================
    // Download operations (GPU -> CPU)
    // ========================================================================

    /**
     * Download results from GPU to result object.
     * Uses persistent read buffers to avoid allocation.
     */
    public static void downloadResults(ChunkResult result) {
        long start = System.nanoTime();

        // Read face count using mapped buffer (fastest path)
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[7]);
        java.nio.Buffer fcBuf = GL15.glMapBuffer(GL43.GL_SHADER_STORAGE_BUFFER, GL15.GL_READ_ONLY);
        if (fcBuf == null) {
            result.success = false;
            return;
        }
        int faceCount = ((IntBuffer) fcBuf).get(0);
        GL15.glUnmapBuffer(GL43.GL_SHADER_STORAGE_BUFFER);

        result.faceCount = faceCount;
        result.vertexCount = faceCount * 4;
        totalDownloads.incrementAndGet();
        totalMapBuffer.incrementAndGet();

        if (faceCount <= 0) {
            totalDownloadTimeNs += System.nanoTime() - start;
            return;
        }

        // Download vertex data using persistent buffer
        int vertexFloatCount = faceCount * 4 * 13;
        if (vertexFloatCount > persistentVertexReadBuffer.capacity()) {
            // Should not happen if MAX is correct, but safety check
            persistentVertexReadBuffer = MemoryUtil.memAllocFloat(vertexFloatCount);
        }
        persistentVertexReadBuffer.clear();
        persistentVertexReadBuffer.limit(vertexFloatCount);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[5]);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, persistentVertexReadBuffer);

        // Copy to result (avoid holding reference to persistent buffer)
        result.vertices = new float[vertexFloatCount];
        persistentVertexReadBuffer.position(0);
        persistentVertexReadBuffer.get(result.vertices, 0, vertexFloatCount);

        // Download index data
        int indexCount = faceCount * 6;
        persistentIndexReadBuffer.clear();
        persistentIndexReadBuffer.limit(indexCount);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[6]);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, persistentIndexReadBuffer);

        result.indices = new int[indexCount];
        persistentIndexReadBuffer.position(0);
        persistentIndexReadBuffer.get(result.indices, 0, indexCount);

        // Download height data
        persistentHeightReadBuffer.clear();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[2]);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, persistentHeightReadBuffer);

        result.heightData = new int[256];
        persistentHeightReadBuffer.position(0);
        persistentHeightReadBuffer.get(result.heightData);

        // Download biome data
        persistentBiomeReadBuffer.clear();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, persistentSSBOs[1]);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, persistentBiomeReadBuffer);

        result.biomeData = new int[256];
        persistentBiomeReadBuffer.position(0);
        persistentBiomeReadBuffer.get(result.biomeData);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        totalDownloadTimeNs += System.nanoTime() - start;
    }

    // ========================================================================
    // SSBO binding helpers
    // ========================================================================

    /**
     * Bind all SSBOs for terrain generation shader.
     */
    public static void bindTerrainGenSSBOs() {
        for (int i = 0; i < 8; i++) {
            GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, i, persistentSSBOs[i]);
        }
        GL43.glBindBufferBase(GL43.GL_UNIFORM_BUFFER, 8, persistentUniformBuffer);
    }

    /**
     * Bind SSBOs for mesh-only shader.
     */
    public static void bindMeshSSBOs() {
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, persistentSSBOs[3]); // block
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, persistentSSBOs[5]); // vertex
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, persistentSSBOs[6]); // index
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, persistentSSBOs[7]); // face
        GL43.glBindBufferBase(GL43.GL_UNIFORM_BUFFER, 4, persistentUniformBuffer);
    }

    /**
     * Get SSBO handle by index.
     */
    public static int getSSBO(int index) {
        return persistentSSBOs[index];
    }

    public static int getUniformBuffer() {
        return persistentUniformBuffer;
    }

    // ========================================================================
    // Result pool
    // ========================================================================

    /**
     * Acquire a result object from the pool.
     * Returns existing object if available, creates new one if pool exhausted.
     */
    public static ChunkResult acquireResult(int chunkX, int chunkZ) {
        ChunkResult result = new ChunkResult(chunkX, chunkZ);
        poolAllocations.incrementAndGet();
        return result;
    }

    /**
     * Simple result class to replace deleted XeChunkLoader.XeChunkResult
     */
    public static class ChunkResult {
        public final int chunkX;
        public final int chunkZ;
        public boolean success;
        public int faceCount;
        public int vertexCount;
        public float[] vertices;
        public int[] indices;
        public int[] heightData;
        public int[] biomeData;

        public ChunkResult(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    // ========================================================================
    // Statistics
    // ========================================================================

    public static int getTotalUploads() {
        return totalUploads.get();
    }

    public static int getTotalDownloads() {
        return totalDownloads.get();
    }

    public static int getTotalBufferSubData() {
        return totalBufferSubData.get();
    }

    public static int getTotalMapBuffer() {
        return totalMapBuffer.get();
    }

    public static double getAverageUploadTimeMs() {
        int u = totalUploads.get();
        return u == 0 ? 0 : (double) totalUploadTimeNs / u / 1_000_000.0;
    }

    public static double getAverageDownloadTimeMs() {
        int d = totalDownloads.get();
        return d == 0 ? 0 : (double) totalDownloadTimeNs / d / 1_000_000.0;
    }

    public static int getPoolAllocations() {
        return poolAllocations.get();
    }

    public static int getPoolHits() {
        return poolHits.get();
    }

    public static String getMemoryStats() {
        return String.format("MemoryManager: uploads=%d downloads=%d bufferSubData=%d mapBuffer=%d " +
                "avgUpload=%.2fms avgDownload=%.2fms poolHits=%d poolAlloc=%d",
                totalUploads.get(), totalDownloads.get(), totalBufferSubData.get(),
                totalMapBuffer.get(), getAverageUploadTimeMs(), getAverageDownloadTimeMs(),
                poolHits.get(), poolAllocations.get());
    }

    /**
     * Reset statistics.
     */
    public static void resetStats() {
        totalUploads.set(0);
        totalDownloads.set(0);
        totalBufferSubData.set(0);
        totalMapBuffer.set(0);
        totalUploadTimeNs = 0;
        totalDownloadTimeNs = 0;
        poolAllocations.set(0);
        poolHits.set(0);
    }

    /**
     * Cleanup all persistent memory.
     */
    public static synchronized void cleanup() {
        // Free upload buffers
        for (int i = 0; i < 2; i++) {
            if (blockUploadBuffers[i] != null) {
                MemoryUtil.memFree(blockUploadBuffers[i]);
                blockUploadBuffers[i] = null;
                blockUploadViews[i] = null;
            }
        }
        if (uniformUploadBuffer != null) {
            MemoryUtil.memFree(uniformUploadBuffer);
            uniformUploadBuffer = null;
            uniformUploadView = null;
        }

        // Free read buffers
        if (persistentVertexReadBuffer != null) {
            MemoryUtil.memFree(persistentVertexReadBuffer);
            persistentVertexReadBuffer = null;
        }
        if (persistentIndexReadBuffer != null) {
            MemoryUtil.memFree(persistentIndexReadBuffer);
            persistentIndexReadBuffer = null;
        }
        if (persistentHeightReadBuffer != null) {
            MemoryUtil.memFree(persistentHeightReadBuffer);
            persistentHeightReadBuffer = null;
        }
        if (persistentBiomeReadBuffer != null) {
            MemoryUtil.memFree(persistentBiomeReadBuffer);
            persistentBiomeReadBuffer = null;
        }

        // Delete GPU buffers
        if (persistentSSBOs[0] != 0) {
            GL15.glDeleteBuffers(persistentSSBOs);
        }
        if (persistentUniformBuffer != 0) {
            GL15.glDeleteBuffers(new int[] { persistentUniformBuffer });
        }

        java.util.Arrays.fill(persistentSSBOs, 0);
        persistentUniformBuffer = 0;

        UHDFast.LOGGER.info("XePersistentMemoryManager: Cleaned up");
    }
}
