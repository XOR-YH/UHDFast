package com.xor.uhdfast.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.xor.uhdfast.gpu.XeBatchRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilder.class)
public class BufferBuilderMixin {

    @Inject(method = "end", at = @At("HEAD"))
    private void onBufferEnd(CallbackInfo ci) {
        XeBatchRenderer.flushBatchQueue();
    }
}
