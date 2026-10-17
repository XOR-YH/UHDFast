package com.xor.uhdfast.mixin;

import com.xor.uhdfast.gpu.TextureMemoryOptimizer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public class TextureManagerMixin {

    @Inject(method = "register", at = @At("HEAD"))
    private void onTextureRegister(ResourceLocation location, AbstractTexture texture, CallbackInfo ci) {
        if (texture != null) {
            try {
                TextureMemoryOptimizer.registerTexture(texture.getId(), 256, 256, org.lwjgl.opengl.GL11.GL_RGBA);
            } catch (Exception e) {
                // Ignore reflection errors
            }
        }
    }
}
