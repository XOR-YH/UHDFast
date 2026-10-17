package com.xor.uhdfast.optimizer;

import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EntityTickInterpolator {

    private static final Map<Integer, InterpolationState> states = new ConcurrentHashMap<>();

    /**
     * Called when an entity is about to be ticked.
     * Records the entity's current position as the interpolation start point.
     */
    public static void registerTick(Entity entity) {
        int id = entity.getId();
        InterpolationState s = states.computeIfAbsent(id, k -> new InterpolationState());
        s.tickX = entity.getX();
        s.tickY = entity.getY();
        s.tickZ = entity.getZ();
        s.lastTickTime = System.nanoTime();
    }

    /**
     * Called when an entity's tick is skipped.
     * Smoothly advances xo/yo/zo toward the last ticked position
     * so that rendering interpolation doesn't jump.
     */
    public static void onTickSkipped(Entity entity) {
        int id = entity.getId();
        InterpolationState s = states.computeIfAbsent(id, k -> new InterpolationState());

        // Advance old position toward the last ticked position gradually.
        // This prevents the render interpolation from jumping between
        // a stale old position and the current position.
        double targetX = s.tickX;
        double targetY = s.tickY;
        double targetZ = s.tickZ;

        double blendFactor = 0.15;
        entity.xo = entity.xo + (targetX - entity.xo) * blendFactor;
        entity.yo = entity.yo + (targetY - entity.yo) * blendFactor;
        entity.zo = entity.zo + (targetZ - entity.zo) * blendFactor;

        // Also keep xOld/yOld/zOld in sync (used by some entities)
        entity.xOld = entity.xOld + (targetX - entity.xOld) * blendFactor;
        entity.yOld = entity.yOld + (targetY - entity.yOld) * blendFactor;
        entity.zOld = entity.zOld + (targetZ - entity.zOld) * blendFactor;

        // If the entity hasn't moved at all, just sync to prevent drift
        double distSq = (entity.getX() - targetX) * (entity.getX() - targetX)
                + (entity.getY() - targetY) * (entity.getY() - targetY)
                + (entity.getZ() - targetZ) * (entity.getZ() - targetZ);
        if (distSq < 0.01) {
            entity.xo = targetX;
            entity.yo = targetY;
            entity.zo = targetZ;
            entity.xOld = targetX;
            entity.yOld = targetY;
            entity.zOld = targetZ;
        }
    }

    public static void onEntityRemoved(Entity entity) {
        states.remove(entity.getId());
    }

    public static void reset() {
        states.clear();
    }

    public static class InterpolationState {
        public double tickX, tickY, tickZ;
        public long lastTickTime;
    }
}
