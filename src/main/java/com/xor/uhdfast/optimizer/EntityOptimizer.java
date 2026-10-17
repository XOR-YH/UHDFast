package com.xor.uhdfast.optimizer;

import com.xor.uhdfast.UHDFastConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EntityOptimizer {

    private static final Map<EntityType<?>, List<Entity>> entityBatches = new ConcurrentHashMap<>();
    private static final Map<EntityType<?>, Integer> entityTypeRenderCounts = new ConcurrentHashMap<>();

    private static int totalEntitiesSkipped = 0;
    private static int totalEntitiesRendered = 0;
    private static int totalBatchesProcessed = 0;

    private static final int MAX_ENTITIES_PER_TYPE = 500;
    private static final double MIN_RENDER_DISTANCE = 8.0;
    private static final double MAX_RENDER_DISTANCE = 256.0;

    private static long lastCleanupTime = 0;
    private static final long CLEANUP_INTERVAL = 5000;

    public static boolean shouldRenderEntity(Entity entity) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = mc.player;
        if (player == null)
            return true;
        return shouldRenderEntity(entity, player.getX(), player.getY(), player.getZ());
    }

    public static boolean shouldRenderEntity(Entity entity, double playerX, double playerY, double playerZ) {
        if (!UHDFastConfig.enableEntityRenderOptimization.get()) {
            totalEntitiesRendered++;
            return true;
        }

        double dx = entity.getX() - playerX;
        double dy = entity.getY() - playerY;
        double dz = entity.getZ() - playerZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;

        if (distanceSq < MIN_RENDER_DISTANCE * MIN_RENDER_DISTANCE) {
            totalEntitiesRendered++;
            return true;
        }

        double maxDist = getMaxRenderDistanceForEntity(entity);
        if (distanceSq > maxDist * maxDist) {
            totalEntitiesSkipped++;
            return false;
        }

        if (!isEntityInFrustum(entity, playerX, playerY, playerZ)) {
            totalEntitiesSkipped++;
            return false;
        }

        if (isEntityOccluded(entity, playerX, playerY, playerZ)) {
            totalEntitiesSkipped++;
            return false;
        }

        totalEntitiesRendered++;
        return true;
    }

    private static double getMaxRenderDistanceForEntity(Entity entity) {
        EntityType<?> type = entity.getType();

        if (type == EntityType.ITEM || type == EntityType.EXPERIENCE_ORB) {
            return 32.0 * UHDFastConfig.renderDistanceMultiplier.get();
        }

        if (type == EntityType.PAINTING || type == EntityType.ITEM_FRAME) {
            return 48.0 * UHDFastConfig.renderDistanceMultiplier.get();
        }

        if (type == EntityType.PLAYER) {
            return 128.0 * UHDFastConfig.renderDistanceMultiplier.get();
        }

        if (type == EntityType.ZOMBIE || type == EntityType.SKELETON ||
                type == EntityType.CREEPER || type == EntityType.SPIDER ||
                type == EntityType.ENDERMAN || type == EntityType.WITCH) {
            return 96.0 * UHDFastConfig.renderDistanceMultiplier.get();
        }

        if (type == EntityType.COW || type == EntityType.PIG ||
                type == EntityType.SHEEP || type == EntityType.CHICKEN) {
            return 64.0 * UHDFastConfig.renderDistanceMultiplier.get();
        }

        return MAX_RENDER_DISTANCE * UHDFastConfig.renderDistanceMultiplier.get();
    }

    private static boolean isEntityInFrustum(Entity entity, double playerX, double playerY, double playerZ) {
        double dx = entity.getX() - playerX;
        double dz = entity.getZ() - playerZ;
        double distanceSq = dx * dx + dz * dz;

        // 用平方距离替代 sqrt：128^2 = 16384
        if (distanceSq > 16384.0) {
            return true;
        }

        return true;
    }

    private static boolean isEntityOccluded(Entity entity, double playerX, double playerY, double playerZ) {
        if (entity.getY() < playerY - 16.0) {
            return true;
        }

        return false;
    }

    public static void addToBatch(Entity entity) {
        if (entity == null)
            return;

        EntityType<?> type = entity.getType();
        entityBatches.computeIfAbsent(type, k -> Collections.synchronizedList(new ArrayList<>()));

        List<Entity> batch = entityBatches.get(type);
        synchronized (batch) {
            if (batch.size() < MAX_ENTITIES_PER_TYPE) {
                batch.add(entity);
            }
        }
    }

    public static List<Entity> getEntityBatch(EntityType<?> type) {
        return entityBatches.getOrDefault(type, Collections.emptyList());
    }

    public static void processBatches() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanupTime > CLEANUP_INTERVAL) {
            cleanupBatches();
            lastCleanupTime = currentTime;
        }

        totalBatchesProcessed++;

        for (Map.Entry<EntityType<?>, List<Entity>> entry : entityBatches.entrySet()) {
            List<Entity> batch = entry.getValue();
            if (!batch.isEmpty()) {
                renderEntityBatch(entry.getKey(), batch);
            }
        }
    }

    private static void renderEntityBatch(EntityType<?> type, List<Entity> entities) {
        if (entities.isEmpty())
            return;

        entityTypeRenderCounts.merge(type, entities.size(), (a, b) -> a + b);
    }

    public static void cleanupBatches() {
        for (List<Entity> batch : entityBatches.values()) {
            synchronized (batch) {
                batch.removeIf(entity -> entity == null || entity.isRemoved());
            }
        }
    }

    public static int getOptimalEntityRenderDistance() {
        return (int) (128 * UHDFastConfig.renderDistanceMultiplier.get());
    }

    public static int getTotalEntitiesSkipped() {
        return totalEntitiesSkipped;
    }

    public static int getTotalEntitiesRendered() {
        return totalEntitiesRendered;
    }

    public static int getTotalBatchesProcessed() {
        return totalBatchesProcessed;
    }

    public static double getEntitySkipRate() {
        int total = totalEntitiesSkipped + totalEntitiesRendered;
        return total > 0 ? (double) totalEntitiesSkipped / total * 100.0 : 0.0;
    }

    public static void resetStats() {
        totalEntitiesSkipped = 0;
        totalEntitiesRendered = 0;
        totalBatchesProcessed = 0;
        entityBatches.clear();
        entityTypeRenderCounts.clear();
    }
}
