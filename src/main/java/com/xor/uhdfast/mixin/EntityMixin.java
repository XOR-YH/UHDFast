package com.xor.uhdfast.mixin;

import com.xor.uhdfast.optimizer.EntityTickInterpolator;
import com.xor.uhdfast.optimizer.XeEntityTickOptimizer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {

    @Shadow
    public abstract int getId();

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickHead(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (entity.level() == null)
            return;

        XeEntityTickOptimizer.resetIdleCount(entity.getId());
        EntityTickInterpolator.registerTick(entity);

        // Record last tick position for smooth interpolation
        entity.xo = entity.getX();
        entity.yo = entity.getY();
        entity.zo = entity.getZ();
        entity.xOld = entity.getX();
        entity.yOld = entity.getY();
        entity.zOld = entity.getZ();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void onTickReturn(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        EntityTickInterpolator.onEntityRemoved(entity);
    }
}
