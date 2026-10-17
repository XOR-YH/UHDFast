package com.xor.uhdfast.cache;

import com.xor.uhdfast.UHDFast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ChunkDataCache {
    private static final int DEFAULT_CAPACITY = 1024;
    private static final int MINIMUM_KEEP_ENTRIES = 128;
    private static final float MEMORY_THRESHOLD = 0.90f;
    private static final float EVICT_RATIO = 0.50f;
    private static long lastEvictionLogTime = 0;
    private static final long EVICTION_LOG_INTERVAL_MS = 5000;

    private final LinkedHashMap<Long, CachedChunkData> cache;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final java.util.concurrent.locks.Lock readLock = rwLock.readLock();
    private final java.util.concurrent.locks.Lock writeLock = rwLock.writeLock();
    private int capacity;
    private long hitCount;
    private long missCount;

    private static volatile ChunkDataCache instance;

    private ChunkDataCache(int capacity) {
        this.capacity = capacity;
        this.cache = new LinkedHashMap<Long, CachedChunkData>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CachedChunkData> eldest) {
                return size() > ChunkDataCache.this.capacity;
            }
        };
        this.hitCount = 0;
        this.missCount = 0;
    }

    public static synchronized ChunkDataCache getInstance() {
        if (instance == null) {
            instance = new ChunkDataCache(DEFAULT_CAPACITY);
            UHDFast.LOGGER.info("ChunkDataCache initialized with default capacity: {}", DEFAULT_CAPACITY);
        }
        return instance;
    }

    public static synchronized void init(int capacity) {
        if (capacity < MINIMUM_KEEP_ENTRIES) {
            UHDFast.LOGGER.warn("Requested cache capacity {} is below minimum {}, using minimum", capacity,
                    MINIMUM_KEEP_ENTRIES);
            capacity = MINIMUM_KEEP_ENTRIES;
        }
        instance = new ChunkDataCache(capacity);
        UHDFast.LOGGER.info("ChunkDataCache initialized with capacity: {}", capacity);
    }

    public static synchronized void reset() {
        instance = null;
    }

    public CachedChunkData get(long chunkKey) {
        readLock.lock();
        try {
            CachedChunkData data = cache.get(chunkKey);
            if (data != null) {
                hitCount++;
                return data;
            } else {
                missCount++;
                return null;
            }
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error getting chunk data for key {}: {}", chunkKey, e.getMessage());
            missCount++;
            return null;
        } finally {
            readLock.unlock();
        }
    }

    public void put(long chunkKey, CachedChunkData data) {
        if (chunkKey == 0 && data == null) {
            return;
        }
        if (data == null) {
            return;
        }
        writeLock.lock();
        try {
            cache.put(chunkKey, data);
            if (cache.size() > capacity) {
                checkMemoryPressure();
            }
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error putting chunk data for key {}: {}", chunkKey, e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public CachedChunkData remove(long chunkKey) {
        writeLock.lock();
        try {
            return cache.remove(chunkKey);
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error removing chunk data for key {}: {}", chunkKey, e.getMessage());
            return null;
        } finally {
            writeLock.unlock();
        }
    }

    public void clear() {
        writeLock.lock();
        try {
            cache.clear();
            hitCount = 0;
            missCount = 0;
            UHDFast.LOGGER.info("ChunkDataCache cleared");
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error clearing cache: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public int size() {
        readLock.lock();
        try {
            return cache.size();
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error getting cache size: {}", e.getMessage());
            return 0;
        } finally {
            readLock.unlock();
        }
    }

    public int getCapacity() {
        return capacity;
    }

    public long getHitCount() {
        readLock.lock();
        try {
            return hitCount;
        } finally {
            readLock.unlock();
        }
    }

    public long getMissCount() {
        readLock.lock();
        try {
            return missCount;
        } finally {
            readLock.unlock();
        }
    }

    public double getHitRate() {
        readLock.lock();
        try {
            long total = hitCount + missCount;
            if (total == 0) {
                return 0.0;
            }
            return (double) hitCount / total;
        } finally {
            readLock.unlock();
        }
    }

    public static long createChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public void onChunkRebuildRequested(int chunkX, int chunkZ) {
        long chunkKey = createChunkKey(chunkX, chunkZ);
        writeLock.lock();
        try {
            CachedChunkData existing = cache.get(chunkKey);
            if (existing != null && existing.getTimestamp() > 0 && !existing.isDirty()) {
                hitCount++;
                if (hitCount % 500 == 0) {
                    UHDFast.LOGGER.debug("ChunkDataCache: hit={}, miss={}, rate={}%",
                            hitCount, missCount, String.format("%.1f", getHitRate() * 100));
                }
            } else {
                missCount++;
                if (existing != null) {
                    existing.setDirty(true);
                }
            }
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error in onChunkRebuildRequested: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public void onChunkLoaded(int chunkX, int chunkZ) {
        long chunkKey = createChunkKey(chunkX, chunkZ);
        writeLock.lock();
        try {
            CachedChunkData existing = cache.get(chunkKey);
            if (existing != null) {
                existing.setDirty(false);
            }
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error in onChunkLoaded: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public void onChunkUnloaded(int chunkX, int chunkZ) {
        long chunkKey = createChunkKey(chunkX, chunkZ);
        writeLock.lock();
        try {
            cache.remove(chunkKey);
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error in onChunkUnloaded: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    private void checkMemoryPressure() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long maxMemory = runtime.maxMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            if (maxMemory > 0) {
                float memoryUsage = (float) usedMemory / maxMemory;
                if (memoryUsage > MEMORY_THRESHOLD) {
                    evictToMemoryTarget();
                }
            }
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error checking memory pressure: {}", e.getMessage());
        }
    }

    private void evictToMemoryTarget() {
        try {
            int currentSize = cache.size();
            if (currentSize <= MINIMUM_KEEP_ENTRIES) {
                return;
            }

            int targetSize = Math.max(MINIMUM_KEEP_ENTRIES, (int) (currentSize * EVICT_RATIO));
            int entriesToRemove = currentSize - targetSize;

            long now = System.currentTimeMillis();
            if (now - lastEvictionLogTime > EVICTION_LOG_INTERVAL_MS) {
                UHDFast.LOGGER.info("Memory pressure detected, evicting {} entries (current: {}, target: {})",
                        entriesToRemove, currentSize, targetSize);
                lastEvictionLogTime = now;
            }

            java.util.Iterator<Map.Entry<Long, CachedChunkData>> iterator = cache.entrySet().iterator();
            int removed = 0;
            while (iterator.hasNext() && removed < entriesToRemove) {
                iterator.next();
                iterator.remove();
                removed++;
            }
        } catch (Exception e) {
            UHDFast.LOGGER.error("Error evicting entries under memory pressure: {}", e.getMessage());
        }
    }
}