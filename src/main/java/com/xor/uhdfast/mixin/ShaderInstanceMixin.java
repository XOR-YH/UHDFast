package com.xor.uhdfast.mixin;

import com.mojang.blaze3d.shaders.Program;
import com.xor.uhdfast.gpu.XeShaderCache;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderInstance.class)
public class ShaderInstanceMixin {

    @Inject(method = "getOrCreate", at = @At("RETURN"))
    private static void onShaderGetOrCreate(ResourceProvider resourceProvider, Program.Type type, String name,
            CallbackInfoReturnable<ShaderInstance> cir) {
        if (cir.getReturnValue() != null) {
            XeShaderCache.onShaderLoaded(name);
        }
    }
}
