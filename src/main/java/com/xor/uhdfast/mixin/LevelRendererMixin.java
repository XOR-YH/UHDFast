package com.xor.uhdfast.mixin;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.gpu.EntityBatchManager;
import com.xor.uhdfast.gpu.XeChunkGPUAccelerator;
import com.xor.uhdfast.optimizer.ChunkOptimizer;
import com.xor.uhdfast.optimizer.RenderOptimizer;
import com.xor.uhdfast.optimizer.XeEntityOptimizer;
import com.xor.uhdfast.optimizer.XeEntityTickOptimizer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LevelRendererMixin - 优化渲染管线
 * 
 * 注意：GPU视锥剔除功能已被移除（因异步双缓冲导致某些视角下方块不渲染）。
 * 如需恢复，需重新实现 submitCullTask 的 section key 对齐逻辑。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    private static double lastPlayerX = 0;
    private static double lastPlayerZ = 0;

    // GPU区块构建性能监控
    private static int gpuBuildsCompleted = 0;
    private static int cpuFallbacks = 0;
    private static long totalGpuBuildTimeNs = 0;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevelStart(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projectionMatrix, Matrix4f inverseRotationMatrix, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        @SuppressWarnings("all")
        var player = mc.player;
        if (player != null) {
            lastPlayerX = player.getX();
            lastPlayerZ = player.getZ();
        } else {
            lastPlayerX = 0;
            lastPlayerZ = 0;
        }
        RenderOptimizer.onFrameStart();
        XeEntityOptimizer.onFrameStart();
        XeEntityTickOptimizer.onFrameStart();

        // 处理GPU区块构建结果
        processGPUChunkResults();

        // 提交GPU区块构建批处理
        // 将 ChunkPreloader 中 submitChunkBuild() 提交的所有请求一次性 dispatch
        try {
            XeChunkGPUAccelerator.flushBatch();
        } catch (Exception e) {
            UHDFast.LOGGER.debug("Failed to flush GPU chunk batch: {}", e.getMessage());
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderLevelEnd(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projectionMatrix, Matrix4f inverseRotationMatrix, CallbackInfo ci) {
        RenderOptimizer.onFrameEnd();

        // Flush entity batches for GPU instanced rendering
        try {
            EntityBatchManager.getInstance().flushBatch();
        } catch (Exception e) {
            UHDFast.LOGGER.debug("Failed to flush entity batch: {}", e.getMessage());
        }

        // 每100帧输出一次性能统计
        if (gpuBuildsCompleted + cpuFallbacks > 0 && (gpuBuildsCompleted + cpuFallbacks) % 100 == 0) {
            logGPUBuildStats();
        }
    }

    /**
     * 拦截setSectionDirty - 跳过远距离区块的重建
     */
    @Inject(method = "setSectionDirty", at = @At("HEAD"), cancellable = true)
    private void onSetChunkDirty(int x, int y, int z, CallbackInfo ci) {
        if (!ChunkOptimizer.shouldRebuildChunk(x, z, lastPlayerX, lastPlayerZ)) {
            ci.cancel();
        }
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"), cancellable = true)
    private void onSetChunkDirtyWithNeighbors(int x, int y, int z, CallbackInfo ci) {
        if (!ChunkOptimizer.shouldRebuildChunk(x, z, lastPlayerX, lastPlayerZ)) {
            ci.cancel();
        }
    }

    /**
     * 处理GPU区块构建结果 - 已由 XeChunkGPUAccelerator.flushBatch() 直接写入缓存。
     * 此方法仅更新统计信息。
     */
    private void processGPUChunkResults() {
        if (!XeChunkGPUAccelerator.isActive()) {
            return;
        }

        try {
            // flushBatch() 已直接将结果写入 ChunkDataCache
            // 这里只更新统计信息
            int chunksDone = XeChunkGPUAccelerator.getChunksProcessed();
            if (chunksDone > gpuBuildsCompleted) {
                gpuBuildsCompleted = chunksDone;
                totalGpuBuildTimeNs = XeChunkGPUAccelerator.getTotalComputeTime();
            }
        } catch (Exception e) {
            UHDFast.LOGGER.debug("Failed to process GPU chunk results: {}", e.getMessage());
            cpuFallbacks++;
        }
    }

    /**
     * 输出GPU区块构建性能统计
     */
    private void logGPUBuildStats() {
        long totalTimeMs = totalGpuBuildTimeNs / 1_000_000;
        double avgTimeMs = gpuBuildsCompleted > 0 ? (double) totalTimeMs / gpuBuildsCompleted : 0;
        double fallbackRate = (gpuBuildsCompleted + cpuFallbacks) > 0
                ? (double) cpuFallbacks / (gpuBuildsCompleted + cpuFallbacks) * 100.0
                : 0;

        UHDFast.LOGGER.info("GPU Chunk Build Stats: completed={}, fallbacks={}, avgTime={}ms, fallbackRate={}%}",
                gpuBuildsCompleted, cpuFallbacks, String.format("%.2f", avgTimeMs),
                String.format("%.1f", fallbackRate));
    }
}