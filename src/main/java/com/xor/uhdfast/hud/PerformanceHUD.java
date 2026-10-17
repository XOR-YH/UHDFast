package com.xor.uhdfast.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import com.xor.uhdfast.gpu.GPULoadBalancer;
import com.xor.uhdfast.gpu.GPUMemoryMonitor;
import com.xor.uhdfast.gpu.IntelXeDetector;
import com.xor.uhdfast.gpu.TextureMemoryOptimizer;
import com.xor.uhdfast.gpu.XeBatchRenderer;
import com.xor.uhdfast.gpu.XeFenceScheduler;
import com.xor.uhdfast.gpu.XeInstancedRenderer;
import com.xor.uhdfast.gpu.XeLightingCompute;
import com.xor.uhdfast.gpu.XeShaderCache;
import com.xor.uhdfast.gpu.MDIBufferManager;
import com.xor.uhdfast.optimizer.ChunkOptimizer;
import com.xor.uhdfast.optimizer.RenderOptimizer;
import com.xor.uhdfast.optimizer.XeEntityOptimizer;
import com.xor.uhdfast.optimizer.XeEntityTickOptimizer;
import com.xor.uhdfast.optimizer.XeLoadOptimizer;
import com.xor.uhdfast.optimizer.XeSpatialEntityOptimizer;
import com.xor.uhdfast.shaderpack.ShaderPack;
import com.xor.uhdfast.shaderpack.ShaderPackManager;
import com.xor.uhdfast.shaderpack.ShaderRenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public class PerformanceHUD {

    private static boolean hudEnabled = false;
    private static int hudX = 10;
    private static int hudY = 10;
    private static long lastUpdate = 0;
    private static String[] cachedLines = new String[0];

    public static void toggleHUD() {
        hudEnabled = !hudEnabled;
        UHDFast.LOGGER.info("Performance HUD {}", hudEnabled ? "enabled" : "disabled");
    }

    public static boolean isHUDVisible() {
        return hudEnabled;
    }

    public static final ResourceLocation HUD_ID = ResourceLocation.fromNamespaceAndPath(UHDFast.MOD_ID,
            "performance_hud");

    public static void render(GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        if (!hudEnabled) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdate < 500) {
            renderCachedLines(guiGraphics);
            return;
        }

        lastUpdate = currentTime;
        updateLines();
        renderCachedLines(guiGraphics);
    }

    private static void updateLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();

        lines.add("\u00a76\u00a7l=== UHDFast Xe Performance HUD ===");
        lines.add("");

        lines.add("\u00a7bGPU: \u00a7f" + (IntelXeDetector.isXeArchitecture() ? "Intel Xe" : "Other"));
        if (IntelXeDetector.isXeArchitecture()) {
            lines.add("\u00a7bArch: \u00a7f" + IntelXeDetector.getArchitectureDescription());
            lines.add("\u00a7bEUs: \u00a7f" + IntelXeDetector.getEUCount());
        }
        lines.add("");

        float fps = RenderOptimizer.getCurrentFPS();
        if (fps > 0) {
            lines.add("\u00a7aFPS: \u00a7f" + String.format("%.1f", fps));
        } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.fpsString != null) {
                lines.add("\u00a7aFPS: \u00a7f" + mc.fpsString.replace(" fps", ""));
            }
        }
        lines.add("");

        lines.add("\u00a7cEntities Rendered: \u00a7f" + XeEntityOptimizer.getTotalRendered());
        lines.add("\u00a7cEntities LOD Reduced: \u00a7f" + XeEntityOptimizer.getTotalLODReduced());
        lines.add("\u00a7cLOD Rate: \u00a7f" + String.format("%.1f%%", XeEntityOptimizer.getLODRate()));
        lines.add("\u00a7cEntity Render Distance: \u00a7f" + UHDFastConfig.entityRenderDistance.get() + " blocks");
        lines.add("");

        lines.add("\u00a77Chunks Skipped: \u00a7f" + ChunkOptimizer.getSkippedChunkCount());
        lines.add("\u00a77GPU Chunks: \u00a7f" + ChunkOptimizer.getGpuAcceleratedChunkCount());
        lines.add("\u00a77CPU Fallback: \u00a7f" + ChunkOptimizer.getFallbackChunkCount());
        lines.add("");

        lines.add("\u00a7e\u00a7l--- Load Optimizer ---");
        lines.add("\u00a7eWorker Threads: \u00a7f" + XeLoadOptimizer.getWorkerThreadCount());
        lines.add("\u00a7eTasks Completed: \u00a7f" + XeLoadOptimizer.getTotalTasksCompleted());
        lines.add("\u00a7eAvg Load Time: \u00a7f" + String.format("%.2f ms", XeLoadOptimizer.getAverageLoadTimeMs()));
        lines.add("\u00a7eCompletion Rate: \u00a7f" + String.format("%.1f%%", XeLoadOptimizer.getCompletionRate()));
        lines.add("");

        lines.add("\u00a7d\u00a7l--- Shader Cache ---");
        lines.add("\u00a7dCache Hits: \u00a7f" + XeShaderCache.getCacheHits());
        lines.add("\u00a7dCache Misses: \u00a7f" + XeShaderCache.getCacheMisses());
        lines.add("\u00a7dHit Rate: \u00a7f" + String.format("%.1f%%", XeShaderCache.getCacheHitRate()));
        lines.add("\u00a7dCached Shaders: \u00a7f" + XeShaderCache.getCacheSize());
        lines.add("\u00a7dTime Saved: \u00a7f" + XeShaderCache.getCompileTimeSavedFormatted());
        lines.add("");

        lines.add("\u00a7d\u00a7l--- Texture Memory ---");
        lines.add("\u00a7dTextures Optimized: \u00a7f" + TextureMemoryOptimizer.getTexturesOptimized());
        lines.add("\u00a7dMemory Saved: \u00a7f" + TextureMemoryOptimizer.getMemorySavedFormatted());
        lines.add("");

        lines.add("\u00a7d\u00a7l--- Batch Renderer ---");
        lines.add("\u00a7dBatches Processed: \u00a7f" + XeBatchRenderer.getTotalBatchesProcessed());
        lines.add("\u00a7dDraw Calls Batched: \u00a7f" + XeBatchRenderer.getTotalDrawCallsBatched());
        lines.add("\u00a7dAvg Batch Size: \u00a7f" + String.format("%.1f", XeBatchRenderer.getAverageBatchSize()));
        lines.add("\u00a7dPending: \u00a7f" + XeBatchRenderer.getPendingBatchCount());
        lines.add("");

        if (MDIBufferManager.getInstance().isAvailable() && UHDFastConfig.enableMDI.get()) {
            lines.add("\u00a75\u00a7l--- MDI (Multi-Draw Indirect) ---");
            var mdi = MDIBufferManager.getInstance();
            lines.add("\u00a75Commands: \u00a7f" + mdi.getCommandCount() + " (max: " + mdi.getMaxDrawsPerFrame() + ")");
            lines.add("\u00a75Total Draws: \u00a7f" + mdi.getTotalDraws());
            lines.add("\u00a75Buffer Size: \u00a7f" + (mdi.getBufferSize() / 1024) + " KB");
            lines.add("");
        }

        lines.add("\u00a7d\u00a7l--- Entity Tick Optimization ---");
        lines.add("\u00a7dTicks Skipped: \u00a7f" + XeEntityTickOptimizer.getTotalSkipped());
        lines.add("\u00a7dAI Skipped: \u00a7f" + XeEntityTickOptimizer.getTotalAISkipped());
        lines.add("\u00a7dEntities Frozen: \u00a7f" + XeEntityTickOptimizer.getTotalFrozen());
        lines.add("\u00a7dTick Skip Rate: \u00a7f"
                + String.format("%.1f%%", XeEntityTickOptimizer.getTickSkipRate()));
        lines.add("\u00a7dFull Updates: \u00a7f" + XeEntityTickOptimizer.getTotalFullUpdated());
        lines.add("\u00a7dReduced Updates: \u00a7f" + XeEntityTickOptimizer.getTotalReducedUpdated());
        lines.add("\u00a7dFrame Skipped: \u00a7f" + XeEntityTickOptimizer.getFrameSkipped());
        lines.add("\u00a7dFrame AI Skipped: \u00a7f" + XeEntityTickOptimizer.getFrameAISkipped());
        lines.add("");

        lines.add("\u00a79\u00a7l--- Spatial Entity Optimizer ---");
        lines.add("\u00a79Entities Culled: \u00a7f" + XeSpatialEntityOptimizer.getTotalCulled());
        lines.add("\u00a79Cull Rate: \u00a7f" + String.format("%.1f%%", XeSpatialEntityOptimizer.getCullRate()));
        lines.add("");

        lines.add("\u00a73\u00a7l--- GPU Compute Workload ---");
        lines.add("\u00a73Instanced Renderer: \u00a7f" + XeInstancedRenderer.getDrawCalls() + " draw calls");
        lines.add("\u00a73  Instances: \u00a7f" + XeInstancedRenderer.getInstancesRendered());
        lines.add("\u00a73  GPU Transforms: \u00a7f" + XeInstancedRenderer.getGPUTransforms());
        lines.add("\u00a73  GPU Time: \u00a7f" + XeInstancedRenderer.getTotalGPUTimeMs() + " ms");
        lines.add("\u00a73Lighting Compute: \u00a7f" + XeLightingCompute.getComputeDispatches() + " dispatches");
        lines.add("\u00a73  Compute Time: \u00a7f" + XeLightingCompute.getTotalComputeTimeMs() + " ms");
        lines.add("");

        // GPU任务调度统计
        lines.add("\u00a73\u00a7l--- GPU Task Scheduler ---");
        lines.add("\u00a73Submitted: \u00a7f" + XeFenceScheduler.getTotalSubmitted());
        lines.add("\u00a73Completed: \u00a7f" + XeFenceScheduler.getTotalCompleted());
        lines.add("\u00a73Rejected: \u00a7f" + XeFenceScheduler.getTotalRejected());
        lines.add("\u00a73Queue: \u00a7f" + XeFenceScheduler.getQueuedTaskCount() + " / "
                + XeFenceScheduler.getPendingTaskCount() + " pending");
        lines.add("\u00a73Max Concurrent: \u00a7f" + XeFenceScheduler.getCurrentMaxConcurrent());
        lines.add("\u00a73GPU Time (avg/min/max): \u00a7f" +
                String.format("%.2f / %.2f / %.2f ms",
                        XeFenceScheduler.getAverageGpuTimeMs(),
                        XeFenceScheduler.getMinGpuTimeMs(),
                        XeFenceScheduler.getMaxGpuTimeMs()));
        lines.add("");

        // GPU负载动态调整
        if (GPULoadBalancer.isRunning()) {
            lines.add("\u00a76\u00a7l--- GPU Load Balancer ---");
            lines.add("\u00a76Concurrent: \u00a7f" + GPULoadBalancer.getCurrentConcurrent());
            lines.add("\u00a76Target FPS: \u00a7f" + GPULoadBalancer.getTargetFPS());
            lines.add("\u00a76Adjustments: \u00a7f" + GPULoadBalancer.getTotalAdjustments() +
                    " (+" + GPULoadBalancer.getIncreaseCount() + "/-" + GPULoadBalancer.getDecreaseCount() + ")");
            lines.add("");
        }

        // GPU内存监控
        if (GPUMemoryMonitor.getTotalMemoryUsed() > 0) {
            lines.add("\u00a7c\u00a7l--- GPU Memory ---");
            lines.add("\u00a7cUsage: \u00a7f" + GPUMemoryMonitor.getMemoryUsageFormatted());
            lines.add("\u00a7cPeak: \u00a7f" + GPUMemoryMonitor.getPeakMemoryUsageMB() + " MB");
            lines.add("\u00a7cAllocations: \u00a7f" + GPUMemoryMonitor.getTotalAllocations() +
                    " / " + GPUMemoryMonitor.getTotalDeallocations() + " freed");
            lines.add("\u00a7cWarnings: \u00a7f" + GPUMemoryMonitor.getWarningCount() +
                    " (Critical: " + GPUMemoryMonitor.getCriticalCount() + ")");
            lines.add("");
        }

        // 光影状态显示
        lines.add("\u00a7b\u00a7l--- Shader Pack ---");
        ShaderPackManager shaderMgr = ShaderPackManager.getInstance();
        if (shaderMgr.isShaderPackLoaded()) {
            ShaderPack pack = shaderMgr.getCurrentPack();
            String packName = (pack != null) ? pack.getName() : "unknown";
            int passCount = (pack != null) ? pack.getProgramNames().size() : 0;
            lines.add("\u00a7bShader: \u00a7f" + packName + " | Pass: " + passCount);
            // 光影渲染管线就绪状态
            lines.add("\u00a7bPipeline: \u00a7f" +
                    (ShaderRenderPipeline.getInstance().isInitialized() ? "Ready" : "Pending"));
        } else {
            lines.add("\u00a7bShader: \u00a7fDisabled");
        }
        lines.add("");

        lines.add("\u00a7f[Press F6 to toggle HUD]");

        cachedLines = lines.toArray(new String[0]);
    }

    private static void renderCachedLines(GuiGraphics guiGraphics) {
        if (cachedLines.length == 0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        int lineHeight = font.lineHeight + 2;
        int totalHeight = cachedLines.length * lineHeight;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        guiGraphics.fill(hudX - 4, hudY - 4, hudX + 250, hudY + totalHeight, 0x80000000);

        for (int i = 0; i < cachedLines.length; i++) {
            String line = cachedLines[i];
            if (line != null) {
                guiGraphics.drawString(font, line, hudX, hudY + i * lineHeight, 0xFFFFFFFF, false);
            }
        }

        RenderSystem.disableBlend();
    }
}
