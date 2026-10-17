package com.xor.uhdfast.optimizer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体批处理收集器
 * 收集并分组相同类型的实体，用于GPU实例化渲染
 */
public class EntityBatchCollector {

    private static final int MIN_BATCH_SIZE = 4;
    private static final double BATCH_DISTANCE_THRESHOLD = 64.0;

    private static final Map<EntityType<?>, List<Entity>> currentFrameBatches = new ConcurrentHashMap<>();
    private static final Map<EntityType<?>, List<List<Entity>>> readyBatches = new ConcurrentHashMap<>();

    private static int totalEntitiesCollected = 0;
    private static int totalBatchesCreated = 0;

    /**
     * 开始新的帧收集
     */
    public static void beginFrame() {
        currentFrameBatches.clear();
        readyBatches.clear();
        totalEntitiesCollected = 0;
        totalBatchesCreated = 0;
    }

    /**
     * 收集实体到批处理队列
     */
    public static void collectEntity(Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return;
        }

        EntityType<?> type = entity.getType();
        currentFrameBatches.computeIfAbsent(type, k -> new ArrayList<>()).add(entity);
        totalEntitiesCollected++;
    }

    /**
     * 完成当前帧收集并准备批处理
     */
    public static void endFrame() {
        for (Map.Entry<EntityType<?>, List<Entity>> entry : currentFrameBatches.entrySet()) {
            List<Entity> entities = entry.getValue();

            if (entities.size() < MIN_BATCH_SIZE) {
                continue;
            }

            List<List<Entity>> batches = groupEntitiesByProximity(entities);
            readyBatches.put(entry.getKey(), batches);
            totalBatchesCreated += batches.size();
        }
    }

    /**
     * 根据空间邻近度分组实体
     */
    private static List<List<Entity>> groupEntitiesByProximity(List<Entity> entities) {
        List<List<Entity>> batches = new ArrayList<>();
        Set<Entity> processed = new HashSet<>();

        for (Entity entity : entities) {
            if (processed.contains(entity)) {
                continue;
            }

            List<Entity> batch = new ArrayList<>();
            batch.add(entity);
            processed.add(entity);

            double entityX = entity.getX();
            double entityY = entity.getY();
            double entityZ = entity.getZ();

            for (Entity other : entities) {
                if (processed.contains(other)) {
                    continue;
                }

                double dx = other.getX() - entityX;
                double dy = other.getY() - entityY;
                double dz = other.getZ() - entityZ;
                double distanceSq = dx * dx + dy * dy + dz * dz;

                if (distanceSq <= BATCH_DISTANCE_THRESHOLD * BATCH_DISTANCE_THRESHOLD) {
                    batch.add(other);
                    processed.add(other);
                }
            }

            if (batch.size() >= MIN_BATCH_SIZE) {
                batches.add(batch);
            }
        }

        return batches;
    }

    /**
     * 获取所有准备好的批处理
     */
    public static Map<EntityType<?>, List<List<Entity>>> getReadyBatches() {
        return readyBatches;
    }

    /**
     * 检查实体是否可以实例化渲染
     */
    public static boolean canInstanceRender(Entity entity) {
        if (entity == null) {
            return false;
        }

        EntityType<?> type = entity.getType();
        List<Entity> entities = currentFrameBatches.get(type);

        return entities != null && entities.size() >= MIN_BATCH_SIZE;
    }

    /**
     * 获取统计信息
     */
    public static int getTotalEntitiesCollected() {
        return totalEntitiesCollected;
    }

    public static int getTotalBatchesCreated() {
        return totalBatchesCreated;
    }

    public static void resetStats() {
        totalEntitiesCollected = 0;
        totalBatchesCreated = 0;
    }
}
