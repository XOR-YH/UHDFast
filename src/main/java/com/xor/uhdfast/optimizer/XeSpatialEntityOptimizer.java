package com.xor.uhdfast.optimizer;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import java.util.concurrent.atomic.AtomicInteger;

public class XeSpatialEntityOptimizer {

    private static final AtomicInteger totalRendered = new AtomicInteger(0);
    private static final AtomicInteger totalCulled = new AtomicInteger(0);
    private static final AtomicInteger frameRendered = new AtomicInteger(0);
    private static final AtomicInteger frameCulled = new AtomicInteger(0);

    public static void initialize() {
        long start = System.nanoTime();
        UHDFast.LOGGER.info("--- Xe Spatial Entity Optimizer ---");
        UHDFast.LOGGER.info("  Initialized in {} ms", String.format("%.2f", (System.nanoTime() - start) / 1_000_000.0));
    }

    // 预计算常量（避免每帧重复计算）
    private static final double FOV_HALF_COS_SQ = Math.cos(Math.toRadians(60.0));
    private static final double FOV_HALF_COS_SQ_VAL = FOV_HALF_COS_SQ * FOV_HALF_COS_SQ;
    private static final double PITCH_LIMIT_SIN = Math.sin(Math.toRadians(50.0));
    private static final double PITCH_LIMIT_SIN_SQ = PITCH_LIMIT_SIN * PITCH_LIMIT_SIN;

    /**
     * 基于视锥体和距离的多级空间剔除。
     * 优化：使用点积替代 atan2/sqrt/asin/toDegrees，避免昂贵的数学运算。
     * 1. 距离剔除：超出实体渲染距离的实体直接剔除
     * 2. 距离剔除：掉落物和经验球在远距离时剔除
     * 3. 视锥剔除：对远距离实体做视锥体检查（点积法）
     */
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

        // 实体渲染距离阈值
        double maxDist = UHDFastConfig.entityRenderDistance.get();
        double maxDistSq = maxDist * maxDist;

        // 超出渲染距离的直接剔除
        if (distanceSq > maxDistSq) {
            totalCulled.incrementAndGet();
            frameCulled.incrementAndGet();
            return false;
        }

        // 近距离实体（<= 32 blocks）始终渲染
        if (distanceSq <= 1024.0) {
            return true;
        }

        // 小物品（掉落物、经验球）在中等距离就剔除
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            if (distanceSq > 4096.0) { // > 64 blocks
                totalCulled.incrementAndGet();
                frameCulled.incrementAndGet();
                return false;
            }
        }

        // 远距离视锥体粗略剔除（> 64 blocks）
        // 优化：使用点积替代 atan2 + sqrt + asin，减少 CPU 开销
        if (distanceSq > 4096.0) {
            float yaw = player.getYRot();

            // ---- 水平方向检查（点积法） ----
            // 计算视线方向的水平分量（单位向量）
            float yawRad = (float) (yaw * DEG_TO_RAD + Math.PI);
            float lookX = -(float) Math.sin(yawRad);
            float lookZ = (float) Math.cos(yawRad);

            // 水平点积：dot = look · dir_xy
            // dot > 0 表示实体在视线前方（角度 < 90°）
            // 归一化后：dot / |dir_xy| > cos(60°) 表示在 FOV 内
            // 避免 sqrt：使用 dot² > |dir_xy|² * cos²(60°)
            double dotH = lookX * dx + lookZ * dz;
            if (dotH <= 0) {
                // 完全在背后（angle >= 90°）
                totalCulled.incrementAndGet();
                frameCulled.incrementAndGet();
                return false;
            }

            // 检查水平 FOV：dotH² > distanceSq_xy * cos²(60°)
            // 其中 distanceSq_xy = dx * dx + dz * dz = distanceSq - dy * dy
            double distanceSqXy = distanceSq - dy * dy;
            if (dotH * dotH < distanceSqXy * FOV_HALF_COS_SQ_VAL) {
                // 在水平 FOV 之外
                totalCulled.incrementAndGet();
                frameCulled.incrementAndGet();
                return false;
            }

            // ---- 垂直方向检查（点积法） ----
            // 垂直角度检查：|dy| / |dir| < sin(50°)
            // 避免 sqrt：dy² < distanceSq * sin²(50°)
            if (dy * dy > distanceSq * PITCH_LIMIT_SIN_SQ) {
                totalCulled.incrementAndGet();
                frameCulled.incrementAndGet();
                return false;
            }
        }

        return true;
    }

    /** 度转弧度常量，避免每帧重复调用 Math.toRadians */
    private static final double DEG_TO_RAD = Math.PI / 180.0;

    public static void onFrameStart() {
        frameRendered.set(0);
        frameCulled.set(0);
    }

    public static void onFrameEnd() {
    }

    public static int getTotalRendered() {
        return totalRendered.get();
    }

    public static int getTotalCulled() {
        return totalCulled.get();
    }

    public static int getFrameRendered() {
        return frameRendered.get();
    }

    public static int getFrameCulled() {
        return frameCulled.get();
    }

    public static double getCullRate() {
        int total = totalRendered.get() + totalCulled.get();
        return total > 0 ? (double) totalCulled.get() / total * 100.0 : 0.0;
    }

    public static void logStats() {
        UHDFast.LOGGER.info("--- Xe Entity Optimizer Stats ---");
        UHDFast.LOGGER.info("  Entities Rendered: {}", totalRendered.get());
        UHDFast.LOGGER.info("  Entities Culled: {}", totalCulled.get());
        UHDFast.LOGGER.info("  Cull Rate: {}%", String.format("%.1f", getCullRate()));
        UHDFast.LOGGER.info("---------------------------------");
    }

    public static void resetStats() {
        totalRendered.set(0);
        totalCulled.set(0);
        frameRendered.set(0);
        frameCulled.set(0);
    }
}
