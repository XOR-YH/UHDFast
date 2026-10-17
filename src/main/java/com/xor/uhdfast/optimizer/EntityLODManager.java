package com.xor.uhdfast.optimizer;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public class EntityLODManager {

    public enum LODLevel {
        HIGH, // Full quality rendering
        MEDIUM, // Simplified shadows, reduced layers
        LOW // Minimal rendering, no shadow, reduced detail
    }

    private static final Minecraft mc = Minecraft.getInstance();

    public static void initialize() {
        long start = System.nanoTime();
        UHDFast.LOGGER.info("--- Xe Entity LOD Manager ---");
        UHDFast.LOGGER.info("  High Detail Distance: {}", UHDFastConfig.entityLODHighDistance.get());
        UHDFast.LOGGER.info("  Medium Detail Distance: {}", UHDFastConfig.entityLODMediumDistance.get());
        UHDFast.LOGGER.info("  Low Detail Distance: {}", UHDFastConfig.entityLODLowDistance.get());
        UHDFast.LOGGER.info("  Initialized in {} ms", String.format("%.2f", (System.nanoTime() - start) / 1_000_000.0));
    }

    public static LODLevel getLODLevel(Entity entity) {
        if (entity == null)
            return LODLevel.HIGH;
        if (!UHDFastConfig.enableEntityLOD.get()) {
            return LODLevel.HIGH;
        }

        Player player = mc.player;
        if (player == null) {
            return LODLevel.HIGH;
        }

        double distSq = player.distanceToSqr(entity);

        double highDist = UHDFastConfig.entityLODHighDistance.get();
        double mediumDist = UHDFastConfig.entityLODMediumDistance.get();
        double lowDist = UHDFastConfig.entityLODLowDistance.get();

        // 用平方距离替代 sqrt
        if (distSq <= highDist * highDist) {
            return LODLevel.HIGH;
        } else if (distSq <= mediumDist * mediumDist) {
            return LODLevel.MEDIUM;
        } else if (distSq <= lowDist * lowDist) {
            return LODLevel.LOW;
        } else {
            // Still render beyond low distance but at lowest detail
            return LODLevel.LOW;
        }
    }

    public static void applyLOD(Entity entity, float partialTick) {
        LODLevel level = getLODLevel(entity);

        EntityRenderer<?> renderer = getEntityRenderer(entity);
        if (renderer == null) {
            return;
        }

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

        switch (level) {
            case HIGH:
                // Full quality - do nothing special
                dispatcher.setRenderShadow(true);
                break;

            case MEDIUM:
                // Simplified - disable shadow for performance
                dispatcher.setRenderShadow(false);
                break;

            case LOW:
                // Minimal - disable shadow, minimal detail
                dispatcher.setRenderShadow(false);
                break;
        }
    }

    private static EntityRenderer<?> getEntityRenderer(Entity entity) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        return entity != null ? dispatcher.getRenderer(entity) : null;
    }

    public static void resetShadow() {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.setRenderShadow(true);
    }
}
