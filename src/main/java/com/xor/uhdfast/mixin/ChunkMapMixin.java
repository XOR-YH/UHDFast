package com.xor.uhdfast.mixin;

import com.xor.uhdfast.optimizer.XeLoadOptimizer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @Inject(method = "getVisibleChunkIfPresent", at = @At("HEAD"))
    private void onChunkLoadRequest(long chunkPos, CallbackInfoReturnable<?> cir) {
        int chunkX = ChunkPos.getX(chunkPos);
        int chunkZ = ChunkPos.getZ(chunkPos);

        // 原有的加载优化任务
        XeLoadOptimizer.submitChunkLoadTask(chunkX, chunkZ, 0.0, 0.0);
    }
}
