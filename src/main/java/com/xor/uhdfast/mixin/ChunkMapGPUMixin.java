package com.xor.uhdfast.mixin;

import com.xor.uhdfast.UHDFastConfig;
import com.xor.uhdfast.compat.ModCompatibility;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ChunkMap GPU Mixin
 * 
 * 拦截Minecraft区块加载流程，处理GPU生成结果。
 * 
 * 修复说明：
 * - 移除了所有阻塞主线程的逻辑（preload循环、玩家查询等）
 * - 只在区块加载完成时轻量级地处理GPU结果
 * - GPU生成完全在后台异步执行，不干扰原版区块加载
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapGPUMixin {

    @Shadow
    protected ServerLevel level;

    /**
     * 在区块加载完成后，轻量级处理GPU结果。
     * 
     * 关键修复：
     * - 不在主线程执行preload循环
     * - 不调用level.players()（可能为空或阻塞）
     * - 只处理已完成的GPU结果，不提交新请求
     * - GPU预生成由独立的定时器/事件处理
     * 
     * @param protoChunk 原型区块
     * @param cir        回调信息
     */
    @Inject(method = "protoChunkToFullChunk", at = @At("RETURN"), remap = false, require = 0)
    private void onProtoChunkToFullChunk(Object protoChunk, CallbackInfoReturnable<LevelChunk> cir) {
        // GPU chunk loader removed - keeping mixin for future use
        if (!UHDFastConfig.enableGPUWorldgen.get()
                || ModCompatibility.shouldDisableGPUChunkLoader()) {
            return;
        }
    }
}
