package com.xor.uhdfast.mixin;

import com.xor.uhdfast.optimizer.EntityLODManager;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {

    @Inject(method = "getShadowRadius(Lnet/minecraft/world/entity/LivingEntity;)F", at = @At("RETURN"), cancellable = true)
    private void onGetShadowRadius(LivingEntity entity, CallbackInfoReturnable<Float> cir) {
        EntityLODManager.LODLevel level = EntityLODManager.getLODLevel(entity);
        float originalRadius = cir.getReturnValueF();

        float modifiedRadius = switch (level) {
            case HIGH -> originalRadius;
            case MEDIUM -> originalRadius * 0.5f;
            case LOW -> 0.0f;
        };

        cir.setReturnValue(modifiedRadius);
    }
}
