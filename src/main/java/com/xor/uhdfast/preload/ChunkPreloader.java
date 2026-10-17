package com.xor.uhdfast.preload;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.cache.CachedChunkData;
import com.xor.uhdfast.cache.ChunkDataCache;
import com.xor.uhdfast.gpu.XeChunkGPUAccelerator;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

// 用于CPU回退路径的网格构建缓冲区，避免每区块分配大型数组
// 使用ThreadLocal确保线程安全，每个线程有自己的缓冲区
class MeshBuildBuffer {
    float[] vertices;
    int[] indices;

    MeshBuildBuffer() {
        int maxVertices = 16 * 128 * 16 * 6 * 4 * 3;
        int maxIndices = 16 * 128 * 16 * 6 * 4 * 2;
        this.vertices = new float[maxVertices];
        this.indices = new int[maxIndices];
    }
}

public class ChunkPreloader {

    private static final int DEFAULT_PRELOAD_DISTANCE = 24;
    private static final int MIN_PRELOAD_DISTANCE = 8;
    private static final int MAX_PRELOAD_DISTANCE = 48;
    private static final int THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
    // 批处理大小：减小到2，避免单个线程阻塞太久
    private static final int BATCH_SIZE = 2;

    private static volatile ChunkPreloader instance;
    private static final Object instanceLock = new Object();

    private ExecutorService executor;
    private int preloadDistance;
    private volatile boolean running;

    private double prevPlayerX;
    private double prevPlayerZ;
    private double moveDirX;
    private double moveDirZ;

    private final AtomicInteger chunksPreloaded = new AtomicInteger(0);
    private final AtomicInteger activeTasks = new AtomicInteger(0);

    // 复用HashSet，避免每次update都创建新对象
    private final Set<Long> submittedKeys = new HashSet<>();

    // CPU回退路径的线程本地缓冲区，避免每区块分配大型数组
    private static final ThreadLocal<MeshBuildBuffer> meshBuffer = ThreadLocal.withInitial(MeshBuildBuffer::new);

    private ChunkPreloader() {
        this.preloadDistance = DEFAULT_PRELOAD_DISTANCE;
        this.running = false;
        this.moveDirX = 0.0;
        this.moveDirZ = 0.0;
    }

    public static ChunkPreloader getInstance() {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new ChunkPreloader();
                    UHDFast.LOGGER.info("ChunkPreloader initialized");
                }
            }
        }
        return instance;
    }

    public void init(int preloadDistance) {
        if (preloadDistance < MIN_PRELOAD_DISTANCE) {
            UHDFast.LOGGER.warn("Preload distance {} below minimum {}, clamping to minimum", preloadDistance,
                    MIN_PRELOAD_DISTANCE);
            this.preloadDistance = MIN_PRELOAD_DISTANCE;
        } else if (preloadDistance > MAX_PRELOAD_DISTANCE) {
            UHDFast.LOGGER.warn("Preload distance {} above maximum {}, clamping to maximum", preloadDistance,
                    MAX_PRELOAD_DISTANCE);
            this.preloadDistance = MAX_PRELOAD_DISTANCE;
        } else {
            this.preloadDistance = preloadDistance;
        }

        shutdown();

        this.executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE, new ThreadFactory() {
            private final AtomicInteger threadNum = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ChunkPreloader-" + threadNum.getAndIncrement());
                t.setDaemon(true);
                // 适度低优先级，避免完全饿死加载线程
                t.setPriority(Thread.MIN_PRIORITY + 2);
                return t;
            }
        });
        this.running = true;
        this.prevPlayerX = 0.0;
        this.prevPlayerZ = 0.0;
        this.moveDirX = 0.0;
        this.moveDirZ = 0.0;
        this.chunksPreloaded.set(0);
        this.activeTasks.set(0);

        UHDFast.LOGGER.info("ChunkPreloader started with preload distance: {} threads: {}", this.preloadDistance,
                THREAD_POOL_SIZE);
    }

    public void update(Player player) {
        if (player == null || !running || executor == null || executor.isShutdown()) {
            return;
        }

        double currentX = player.getX();
        double currentZ = player.getZ();

        this.moveDirX = currentX - prevPlayerX;
        this.moveDirZ = currentZ - prevPlayerZ;

        this.prevPlayerX = currentX;
        this.prevPlayerZ = currentZ;

        int currentChunkX = (int) Math.floor(currentX / 16.0);
        int currentChunkZ = (int) Math.floor(currentZ / 16.0);

        Level level = player.getCommandSenderWorld();
        if (level == null) {
            return;
        }

        ChunkDataCache cache = ChunkDataCache.getInstance();

        // 复用HashSet，避免每次update都创建新对象
        submittedKeys.clear();

        // 使用平方距离避免sqrt计算
        int maxDistSq = preloadDistance * preloadDistance;

        // 批处理：收集需要预加载的区块，每BATCH_SIZE个提交一次任务
        List<ChunkBatchTask> batchList = new ArrayList<>();

        for (int dx = -preloadDistance; dx <= preloadDistance; dx++) {
            for (int dz = -preloadDistance; dz <= preloadDistance; dz++) {
                int targetChunkX = currentChunkX + dx;
                int targetChunkZ = currentChunkZ + dz;

                long chunkKey = ChunkDataCache.createChunkKey(targetChunkX, targetChunkZ);

                if (cache.get(chunkKey) != null) {
                    continue;
                }

                if (submittedKeys.contains(chunkKey)) {
                    continue;
                }

                // 使用平方距离比较，避免sqrt
                int distSq = dx * dx + dz * dz;
                if (distSq > maxDistSq) {
                    continue;
                }

                // 使用平方距离判断是否在预加载方向
                if (!isInPreloadDirection(dx, dz, distSq)) {
                    continue;
                }

                if (activeTasks.get() >= THREAD_POOL_SIZE * 2) {
                    continue;
                }

                submittedKeys.add(chunkKey);
                batchList.add(new ChunkBatchTask(level, targetChunkX, targetChunkZ, chunkKey, cache));
            }
        }

        // 按批提交：每BATCH_SIZE个区块合并到一个任务，减少任务调度开销
        for (int i = 0; i < batchList.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, batchList.size());
            List<ChunkBatchTask> batch = batchList.subList(i, end);
            activeTasks.addAndGet(batch.size());
            executor.submit(() -> {
                try {
                    for (ChunkBatchTask task : batch) {
                        preloadChunk(task.level, task.chunkX, task.chunkZ, task.chunkKey, task.cache);
                    }
                } catch (Exception e) {
                    UHDFast.LOGGER.debug("Batch preload failed: {}", e.getMessage());
                } finally {
                    activeTasks.addAndGet(-batch.size());
                }
            });
        }
    }

    // 使用平方距离避免sqrt计算
    private boolean isInPreloadDirection(int dx, int dz, int distSq) {
        double moveDirLenSq = moveDirX * moveDirX + moveDirZ * moveDirZ;
        if (moveDirLenSq < 0.000001) {
            return true;
        }

        // 使用平方距离比较，避免sqrt
        // 原始: dot / (sqrt(moveDirLenSq) * sqrt(distSq)) > -0.3
        // 转换: dot > -0.3 * sqrt(moveDirLenSq * distSq)
        // 平方: dot > threshold (当dot为负时，平方比较)
        double dot = dx * moveDirX + dz * moveDirZ;
        // 如果dot >= 0，恒成立（右侧为负值）
        if (dot >= 0) return true;
        // 使用平方比较避免sqrt: dot^2 > 0.09 * moveDirLenSq * distSq
        return dot * dot > 0.09 * moveDirLenSq * distSq;
    }

    // 区块批处理任务：打包多个区块的坐标和缓存引用，减少任务提交次数
    private static class ChunkBatchTask {
        final Level level;
        final int chunkX;
        final int chunkZ;
        final long chunkKey;
        final ChunkDataCache cache;

        ChunkBatchTask(Level level, int chunkX, int chunkZ, long chunkKey, ChunkDataCache cache) {
            this.level = level;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.chunkKey = chunkKey;
            this.cache = cache;
        }
    }

    private void preloadChunk(Level level, int chunkX, int chunkZ, long chunkKey, ChunkDataCache cache) {
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }

        // 检查缓存是否已有数据（可能已被 GPU flushBatch 写入）
        if (cache.get(chunkKey) != null) {
            return;
        }

        byte[] blockData = extractBlockData(chunk);
        if (blockData == null) {
            return;
        }

        // 使用线程安全的批处理提交 API，GPU 实际执行在渲染线程的 flushBatch() 中
        if (XeChunkGPUAccelerator.isActive()) {
            XeChunkGPUAccelerator.submitChunkBuild(blockData, chunkX, chunkZ);
            // GPU 结果由 flushBatch() 直接写入缓存，无需在此处处理
            return;
        }

        // CPU 回退路径
        float[] vertices = buildCPUMesh(blockData);
        int[] indices = buildCPUIndices(blockData);

        if (vertices != null && indices != null) {
            CachedChunkData cachedData = new CachedChunkData(vertices, indices);
            cache.put(chunkKey, cachedData);
            chunksPreloaded.incrementAndGet();
        }
    }

    private byte[] extractBlockData(LevelChunk chunk) {
        int dataSize = 16 * 128 * 16;
        byte[] data = new byte[dataSize];

        try {
            var sections = chunk.getSections();
            for (int sectionIdx = 0; sectionIdx < sections.length; sectionIdx++) {
                var section = sections[sectionIdx];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }

                int sectionBaseY = sectionIdx * 16 - chunk.getMinBuildHeight();
                for (int y = 0; y < 16 && (sectionBaseY + y) < 128; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int worldY = sectionBaseY + y;
                            var state = section.getBlockState(x, y, z);
                            int dataIdx = worldY * 16 * 16 + x * 16 + z;
                            if (dataIdx >= 0 && dataIdx < dataSize && !state.isAir()) {
                                data[dataIdx] = (byte) (state.getBlock().hashCode() & 0xFF);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            UHDFast.LOGGER.debug("Failed to extract block data: {}", e.getMessage());
            return null;
        }

        return data;
    }

    private boolean isSolid(byte[] data, int x, int y, int z, int width, int height, int depth) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
            return false;
        }
        return data[y * width * depth + x * depth + z] != 0;
    }

    private float[] buildCPUMesh(byte[] blockData) {
        int width = 16;
        int height = 128;
        int depth = 16;

        // 使用线程本地缓冲区，避免每区块分配大型数组
        MeshBuildBuffer buf = meshBuffer.get();
        float[] vertices = buf.vertices;
        int vertexCount = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    int idx = y * width * depth + x * depth + z;
                    if (blockData[idx] == 0) {
                        continue;
                    }

                    float fx = (float) x;
                    float fx2 = fx + 1.0f;
                    float fy = (float) y;
                    float fy2 = fy + 1.0f;
                    float fz = (float) z;
                    float fz2 = fz + 1.0f;

                    if (!isSolid(blockData, x, y, z - 1, width, height, depth)) {
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz;
                    }

                    if (!isSolid(blockData, x, y, z + 1, width, height, depth)) {
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz2;
                    }

                    if (!isSolid(blockData, x, y + 1, z, width, height, depth)) {
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz;
                    }

                    if (!isSolid(blockData, x, y - 1, z, width, height, depth)) {
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz2;
                    }

                    if (!isSolid(blockData, x - 1, y, z, width, height, depth)) {
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz;
                    }

                    if (!isSolid(blockData, x + 1, y, z, width, height, depth)) {
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy2;
                        vertices[vertexCount++] = fz2;
                        vertices[vertexCount++] = fx2;
                        vertices[vertexCount++] = fy;
                        vertices[vertexCount++] = fz2;
                    }
                }
            }
        }

        float[] result = new float[vertexCount];
        System.arraycopy(vertices, 0, result, 0, vertexCount);
        return result;
    }

    private int[] buildCPUIndices(byte[] blockData) {
        int width = 16;
        int height = 128;
        int depth = 16;

        // 使用线程本地缓冲区，避免每区块分配大型数组
        MeshBuildBuffer buf = meshBuffer.get();
        int[] indices = buf.indices;
        int indexCount = 0;
        int faceIndex = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (!isSolid(blockData, x, y, z, width, height, depth)) {
                        continue;
                    }

                    int faces = 0;
                    if (!isSolid(blockData, x, y, z - 1, width, height, depth))
                        faces++;
                    if (!isSolid(blockData, x, y, z + 1, width, height, depth))
                        faces++;
                    if (!isSolid(blockData, x, y + 1, z, width, height, depth))
                        faces++;
                    if (!isSolid(blockData, x, y - 1, z, width, height, depth))
                        faces++;
                    if (!isSolid(blockData, x - 1, y, z, width, height, depth))
                        faces++;
                    if (!isSolid(blockData, x + 1, y, z, width, height, depth))
                        faces++;

                    for (int f = 0; f < faces; f++) {
                        indices[indexCount++] = faceIndex;
                        indices[indexCount++] = faceIndex + 1;
                        indices[indexCount++] = faceIndex + 2;
                        indices[indexCount++] = faceIndex;
                        indices[indexCount++] = faceIndex + 2;
                        indices[indexCount++] = faceIndex + 3;
                        faceIndex += 4;
                    }
                }
            }
        }

        int[] result = new int[indexCount];
        System.arraycopy(indices, 0, result, 0, indexCount);
        return result;
    }

    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            running = false;
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            UHDFast.LOGGER.info("ChunkPreloader shut down. Total chunks preloaded: {}", chunksPreloaded.get());
        }
    }

    public int getChunksPreloaded() {
        return chunksPreloaded.get();
    }

    public int getActiveTasks() {
        return activeTasks.get();
    }

    public int getPreloadDistance() {
        return preloadDistance;
    }
}
