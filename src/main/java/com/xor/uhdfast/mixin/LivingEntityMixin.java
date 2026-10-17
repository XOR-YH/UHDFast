package com.xor.uhdfast.mixin;

import com.xor.uhdfast.optimizer.XeEntityTickOptimizer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void onAIStepHead(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.level() == null)
            return;

        // Only skip truly redundant AI (idle entities with no movement/goals)
        // Never skip AI based on distance to prevent stuttering movement
        if (XeEntityTickOptimizer.shouldSkipRedundantAI(entity)) {
            ci.cancel();
        }
    }
}
