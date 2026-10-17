package com.xor.uhdfast.optimizer;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class XeEntityTickOptimizer {

    public enum TickLODLevel {
        FULL_RATE,
        HALF_RATE,
        QUARTER_RATE,
        SKIP_AI,
        FROZEN
    }

    private static final Map<Integer, EntityTickState> entityStates = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastTickTime = new ConcurrentHashMap<>();

    private static final AtomicInteger totalSkipped = new AtomicInteger(0);
    private static final AtomicInteger totalFullUpdated = new AtomicInteger(0);
    private static final AtomicInteger totalReducedUpdated = new AtomicInteger(0);
    private static final AtomicInteger totalAISkipped = new AtomicInteger(0);
    private static final AtomicInteger totalFrozen = new AtomicInteger(0);
    private static final AtomicInteger frameSkipped = new AtomicInteger(0);
    private static final AtomicInteger frameFullUpdated = new AtomicInteger(0);
    private static final AtomicInteger frameAISkipped = new AtomicInteger(0);

    private static final AtomicLong frameTickBudgetStart = new AtomicLong(0);
    private static final AtomicInteger frameTickBudgetCount = new AtomicInteger(0);

    private static int lodHalfDistance;
    private static int lodQuarterDistance;
    private static int lodSkipAIDistance;
    private static int lodFrozenDistance;

    private static final int IDLE_THRESHOLD_TICKS = 60;

    public static void initialize() {
        long start = System.nanoTime();

        loadConfigDistances();

        UHDFast.LOGGER.info("--- Xe Entity Tick Optimizer ---");
        UHDFast.LOGGER.info("  Tick LOD Full: 0-{} blocks", lodHalfDistance);
        UHDFast.LOGGER.info("  Tick LOD Half: {}-{} blocks", lodHalfDistance, lodQuarterDistance);
        UHDFast.LOGGER.info("  Tick LOD Quarter: {}-{} blocks", lodQuarterDistance, lodSkipAIDistance);
        UHDFast.LOGGER.info("  Tick LOD Skip AI: {}-{} blocks", lodSkipAIDistance, lodFrozenDistance);
        UHDFast.LOGGER.info("  Tick LOD Frozen: {}+ blocks", lodFrozenDistance);
        UHDFast.LOGGER.info("  Idle threshold: {} ticks", IDLE_THRESHOLD_TICKS);
        UHDFast.LOGGER.info("  Initialized in {} ms", String.format("%.2f", (System.nanoTime() - start) / 1_000_000.0));
    }

    private static void loadConfigDistances() {
        lodHalfDistance = UHDFastConfig.entityTickLodHalfDistance.get();
        lodQuarterDistance = UHDFastConfig.entityTickLodQuarterDistance.get();
        lodSkipAIDistance = UHDFastConfig.entityTickLodSkipAIDistance.get();
        lodFrozenDistance = UHDFastConfig.entityTickLodFrozenDistance.get();

        if (lodHalfDistance >= lodQuarterDistance) {
            lodQuarterDistance = lodHalfDistance + 16;
        }
        if (lodQuarterDistance >= lodSkipAIDistance) {
            lodSkipAIDistance = lodQuarterDistance + 32;
        }
        if (lodSkipAIDistance >= lodFrozenDistance) {
            lodFrozenDistance = lodSkipAIDistance + 48;
        }
    }

    public static TickLODLevel getTickLODLevel(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.entity.player.Player player = mc.player;
        if (player == null)
            return TickLODLevel.FULL_RATE;

        double dx = entity.getX() - player.getX();
        double dy = entity.getY() - player.getY();
        double dz = entity.getZ() - player.getZ();
        double distanceSq = dx * dx + dy * dy + dz * dz;

        // 用平方距离替代 sqrt
        double halfDistSq = lodHalfDistance * lodHalfDistance;
        double quarterDistSq = lodQuarterDistance * lodQuarterDistance;
        double skipAIDistSq = lodSkipAIDistance * lodSkipAIDistance;
        double frozenDistSq = lodFrozenDistance * lodFrozenDistance;

        if (distanceSq <= halfDistSq) {
            return TickLODLevel.FULL_RATE;
        } else if (distanceSq <= quarterDistSq) {
            return TickLODLevel.HALF_RATE;
        } else if (distanceSq <= skipAIDistSq) {
            return TickLODLevel.QUARTER_RATE;
        } else if (distanceSq <= frozenDistSq) {
            return TickLODLevel.SKIP_AI;
        } else {
            return TickLODLevel.FROZEN;
        }
    }

    public static boolean shouldTickEntity(Entity entity, long currentTick) {
        if (!UHDFastConfig.enableEntityTickOptimization.get()) {
            return true;
        }

        if (isProtectedEntity(entity)) {
            totalFullUpdated.incrementAndGet();
            frameFullUpdated.incrementAndGet();
            return true;
        }

        int tickBudgetMs = UHDFastConfig.entityTickBudgetMs.get();
        if (tickBudgetMs > 0 && isTickBudgetExceeded(tickBudgetMs)) {
            EntityTickInterpolator.onTickSkipped(entity);
            frameSkipped.incrementAndGet();
            totalSkipped.incrementAndGet();
            return false;
        }

        TickLODLevel level = getTickLODLevel(entity);

        switch (level) {
            case FULL_RATE:
                totalFullUpdated.incrementAndGet();
                frameFullUpdated.incrementAndGet();
                recordTickBudgetUsage();
                return true;

            case HALF_RATE:
                return shouldTickWithInterval(entity.getId(), currentTick, 2);

            case QUARTER_RATE:
                return shouldTickWithInterval(entity.getId(), currentTick, 4);

            case SKIP_AI:
                totalAISkipped.incrementAndGet();
                frameAISkipped.incrementAndGet();
                return true;

            case FROZEN:
                totalFrozen.incrementAndGet();
                frameSkipped.incrementAndGet();
                return false;

            default:
                return true;
        }
    }

    public static boolean shouldTickAI(Entity entity, long currentTick) {
        if (!UHDFastConfig.enableEntityTickOptimization.get()) {
            return true;
        }

        if (isProtectedEntity(entity)) {
            return true;
        }

        TickLODLevel level = getTickLODLevel(entity);
        if (level == TickLODLevel.SKIP_AI || level == TickLODLevel.FROZEN) {
            totalAISkipped.incrementAndGet();
            return false;
        }

        switch (level) {
            case FULL_RATE:
                return true;
            case HALF_RATE:
                return shouldTickWithInterval(entity.getId(), currentTick, 2);
            case QUARTER_RATE:
                return shouldTickWithInterval(entity.getId(), currentTick, 4);
            default:
                return true;
        }
    }

    public static boolean shouldSkipRedundantAI(Entity entity) {
        if (!UHDFastConfig.enableRedundantAISkip.get()) {
            return false;
        }

        if (!(entity instanceof Mob mob)) {
            return false;
        }

        if (isProtectedEntity(entity)) {
            return false;
        }

        boolean isIdle = mob.onGround() &&
                !mob.isAggressive() &&
                mob.getTarget() == null &&
                mob.getLastHurtByMob() == null &&
                mob.getNavigation().isDone() &&
                mob.getMoveControl().getSpeedModifier() == 0.0F;

        if (!isIdle) {
            int entityId = entity.getId();
            EntityTickState state = entityStates.get(entityId);
            if (state != null) {
                state.idleTickCount = 0;
            }
            return false;
        }

        int entityId = entity.getId();
        EntityTickState state = entityStates.computeIfAbsent(entityId, k -> new EntityTickState());
        state.idleTickCount++;

        return state.idleTickCount >= IDLE_THRESHOLD_TICKS;
    }

    private static boolean isProtectedEntity(Entity entity) {
        return entity instanceof Player ||
                entity instanceof Projectile ||
                entity instanceof ItemEntity ||
                entity instanceof ExperienceOrb ||
                entity instanceof AbstractMinecart ||
                entity instanceof Boat;
    }

    private static boolean shouldTickWithInterval(int entityId, long currentTick, int interval) {
        long lastTick = lastTickTime.getOrDefault(entityId, -1L);
        if (lastTick == -1 || (currentTick - lastTick) >= interval) {
            lastTickTime.put(entityId, currentTick);
            totalReducedUpdated.incrementAndGet();
            frameFullUpdated.incrementAndGet();
            recordTickBudgetUsage();
            return true;
        }
        totalSkipped.incrementAndGet();
        frameSkipped.incrementAndGet();
        return false;
    }

    private static boolean isTickBudgetExceeded(int budgetMs) {
        long currentTime = System.nanoTime();
        long frameStart = frameTickBudgetStart.get();
        if (frameStart == 0)
            return false;
        return (currentTime - frameStart) >= budgetMs * 1_000_000L;
    }

    private static void recordTickBudgetUsage() {
        frameTickBudgetCount.incrementAndGet();
    }

    public static void resetIdleCount(int entityId) {
        EntityTickState state = entityStates.get(entityId);
        if (state != null) {
            state.idleTickCount = 0;
        }
    }

    public static void onFrameStart() {
        frameSkipped.set(0);
        frameFullUpdated.set(0);
        frameAISkipped.set(0);
        frameTickBudgetStart.set(System.nanoTime());
        frameTickBudgetCount.set(0);
    }

    public static int getTotalSkipped() {
        return totalSkipped.get();
    }

    public static int getTotalFullUpdated() {
        return totalFullUpdated.get();
    }

    public static int getTotalReducedUpdated() {
        return totalReducedUpdated.get();
    }

    public static int getTotalAISkipped() {
        return totalAISkipped.get();
    }

    public static int getTotalFrozen() {
        return totalFrozen.get();
    }

    public static int getFrameSkipped() {
        return frameSkipped.get();
    }

    public static int getFrameFullUpdated() {
        return frameFullUpdated.get();
    }

    public static int getFrameAISkipped() {
        return frameAISkipped.get();
    }

    public static double getTickSkipRate() {
        int total = totalSkipped.get() + totalFullUpdated.get() + totalReducedUpdated.get();
        return total > 0 ? (double) totalSkipped.get() / total * 100.0 : 0.0;
    }

    public static void logStats() {
        UHDFast.LOGGER.info("--- Xe Entity Tick Optimizer Stats ---");
        UHDFast.LOGGER.info("  Full Updates: {}", totalFullUpdated.get());
        UHDFast.LOGGER.info("  Reduced Updates: {}", totalReducedUpdated.get());
        UHDFast.LOGGER.info("  Ticks Skipped: {}", totalSkipped.get());
        UHDFast.LOGGER.info("  AI Skipped: {}", totalAISkipped.get());
        UHDFast.LOGGER.info("  Entities Frozen: {}", totalFrozen.get());
        UHDFast.LOGGER.info("  Tick Skip Rate: {}%", String.format("%.1f", getTickSkipRate()));
        UHDFast.LOGGER.info("---------------------------------");
    }

    public static void resetStats() {
        totalSkipped.set(0);
        totalFullUpdated.set(0);
        totalReducedUpdated.set(0);
        totalAISkipped.set(0);
        totalFrozen.set(0);
        frameSkipped.set(0);
        frameFullUpdated.set(0);
        frameAISkipped.set(0);
        frameTickBudgetStart.set(0);
        frameTickBudgetCount.set(0);
        entityStates.clear();
        lastTickTime.clear();
    }

    public static class EntityTickState {
        public int idleTickCount = 0;
    }
}
