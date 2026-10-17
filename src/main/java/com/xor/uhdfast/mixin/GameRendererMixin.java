package com.xor.uhdfast.mixin;

import com.xor.uhdfast.optimizer.RenderOptimizer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(net.minecraft.client.DeltaTracker deltaTracker, boolean renderBlockOutline,
            CallbackInfo ci) {
        // GPU光照计算已禁用 - 结果未被Minecraft渲染管线使用，且会干扰OpenGL状态
        // XeLightingIntegration.onFrameRender();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onFrameRendered(net.minecraft.client.DeltaTracker deltaTracker, boolean renderBlockOutline,
            CallbackInfo ci) {
        RenderOptimizer.applyFrameRateLimit();
    }
}
