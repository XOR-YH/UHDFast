package com.xor.uhdfast.optimizer;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import com.xor.uhdfast.gpu.IntelXeDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.concurrent.atomic.AtomicInteger;

public class XeEntityOptimizer {

    public enum LODLevel {
        HIGH, // 0-32 blocks: Full detail
        MEDIUM, // 32-96 blocks: Reduced detail
        LOW // 96+ blocks: Minimal detail
    }

    private static final AtomicInteger totalRendered = new AtomicInteger(0);
    private static final AtomicInteger totalLODReduced = new AtomicInteger(0);
    private static final AtomicInteger frameRendered = new AtomicInteger(0);
    private static final AtomicInteger frameLODReduced = new AtomicInteger(0);

    private static int lodHighDistance = 32;
    private static int lodMediumDistance = 96;

    public static void initialize() {
        long start = System.nanoTime();

        lodHighDistance = 32;
        lodMediumDistance = (int) (UHDFastConfig.entityRenderDistance.get() * 0.5);

        int euCount = IntelXeDetector.getEUCount();
        lodMediumDistance = Math.max(64, Math.min(192, euCount));

        UHDFast.LOGGER.info("--- Xe Entity LOD Optimizer ---");
        UHDFast.LOGGER.info("  LOD High Distance: 0-{} blocks", lodHighDistance);
        UHDFast.LOGGER.info("  LOD Medium Distance: {}-{} blocks", lodHighDistance, lodMediumDistance);
        UHDFast.LOGGER.info("  LOD Low Distance: {}+ blocks", lodMediumDistance);
        UHDFast.LOGGER.info("  EU Count: {}", euCount);
        UHDFast.LOGGER.info("  Initialized in {} ms", String.format("%.2f", (System.nanoTime() - start) / 1_000_000.0));
    }

    public static boolean shouldRenderEntity(Entity entity) {
        if (!UHDFastConfig.enableEntityRenderOptimization.get()) {
            return true;
        }

        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = mc.player;
        if (player == null) return true;

        double dx = entity.getX() - player.getX();
        double dy = entity.getY() - player.getY();
        double dz = entity.getZ() - player.getZ();
        double distanceSq = dx * dx + dy * dy + dz * dz;

        // 使用玩家配置的实体渲染距离
        double maxDist = UHDFastConfig.entityRenderDistance.get();
        double maxDistSq = maxDist * maxDist;

        // 超出渲染距离的直接剔除
        if (distanceSq > maxDistSq) {
            return false;
        }

        // 近距离实体始终渲染（<= 32 blocks）
        if (distanceSq <= 1024.0) {
            return true;
        }

        // 中距离根据 LOD 级别决定是否跳过
        // 对于远距离的实体，根据渲染距离乘数做额外剔除
        double multiplier = 1.0;
        try {
            multiplier = UHDFastConfig.renderDistanceMultiplier.get();
        } catch (Exception ignored) {}

        // 如果渲染距离乘数较大，对远距离实体做更激进的剔除
        if (multiplier > 1.5) {
            // 距离 > 64 blocks 且乘数 > 1.5 时，只渲染 50% 的远距离实体
            if (distanceSq > 4096.0) {
                // 使用实体 ID 做哈希，保证同一实体不会闪烁
                int hash = entity.getId() * 31 + (int)(distanceSq);
                if ((hash & 1) == 0) {
                    return false;
                }
            }
        }

        // 小物品实体（掉落物、经验球等）在远距离时剔除
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return distanceSq <= 4096.0; // <= 64 blocks
        }

        return true;
    }

    public static void recordEntityRendered(boolean wasLODReduced) {
        totalRendered.incrementAndGet();
        if (wasLODReduced) {
            totalLODReduced.incrementAndGet();
        }
    }

    public static LODLevel getLODLevel(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = mc.player;
        if (player == null)
            return LODLevel.HIGH;

        double dx = entity.getX() - player.getX();
        double dy = entity.getY() - player.getY();
        double dz = entity.getZ() - player.getZ();
        double distanceSq = dx * dx + dy * dy + dz * dz;

        // 用平方距离替代 sqrt
        double highDistSq = lodHighDistance * lodHighDistance;
        double mediumDistSq = lodMediumDistance * lodMediumDistance;

        if (distanceSq <= highDistSq) {
            return LODLevel.HIGH;
        } else if (distanceSq <= mediumDistSq) {
            return LODLevel.MEDIUM;
        } else {
            return LODLevel.LOW;
        }
    }

    public static void onFrameStart() {
        frameRendered.set(0);
        frameLODReduced.set(0);
    }

    public static void onFrameEnd() {
    }

    public static int getTotalRendered() {
        return totalRendered.get();
    }

    public static int getTotalLODReduced() {
        return totalLODReduced.get();
    }

    public static int getFrameRendered() {
        return frameRendered.get();
    }

    public static int getFrameLODReduced() {
        return frameLODReduced.get();
    }

    public static double getLODRate() {
        int total = totalRendered.get() + totalLODReduced.get();
        return total > 0 ? (double) totalLODReduced.get() / total * 100.0 : 0.0;
    }

    public static void logStats() {
        UHDFast.LOGGER.info("--- Xe Entity LOD Optimizer Stats ---");
        UHDFast.LOGGER.info("  Entities Rendered: {}", totalRendered.get());
        UHDFast.LOGGER.info("  Entities LOD Reduced: {}", totalLODReduced.get());
        UHDFast.LOGGER.info("  LOD Rate: {:.1f}%", getLODRate());
        UHDFast.LOGGER.info("---------------------------------");
    }

    public static void resetStats() {
        totalRendered.set(0);
        totalLODReduced.set(0);
        frameRendered.set(0);
        frameLODReduced.set(0);
    }
}
