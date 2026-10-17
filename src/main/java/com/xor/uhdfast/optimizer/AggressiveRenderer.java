package com.xor.uhdfast.optimizer;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.minecraft.client.Minecraft;

public class AggressiveRenderer {

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
        }

        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            applyRenderOptimizations(event);
        }
    }

    private static void applyRenderOptimizations(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        if (shouldOptimizeEntities()) {
        }

        if (shouldLimitParticles()) {
            limitParticles();
        }
    }

    private static void limitParticles() {
        // 移除反射操作，避免每帧反射调用导致的性能问题
        // 粒子限制已通过配置系统实现，无需在此处强制限制
    }

    private static boolean shouldOptimizeEntities() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.graphicsMode().get() == net.minecraft.client.GraphicsStatus.FAST) {
            return true;
        }
        return System.currentTimeMillis() % 1000 < 500;
    }

    private static boolean shouldLimitParticles() {
        return Minecraft.getInstance().options.particles().get() != net.minecraft.client.ParticleStatus.ALL;
    }
}
