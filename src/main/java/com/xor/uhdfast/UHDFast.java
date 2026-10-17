package com.xor.uhdfast;

import com.xor.uhdfast.compat.ModCompatibility;
import com.xor.uhdfast.gpu.*;
import com.xor.uhdfast.hud.PerformanceHUD;
import com.xor.uhdfast.optimizer.*;
import com.xor.uhdfast.cache.ChunkDataCache;
import com.xor.uhdfast.preload.ChunkPreloader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

@Mod(UHDFast.MOD_ID)
public class UHDFast {
    public static final String MOD_ID = "uhdfast";
    public static final Logger LOGGER = LoggerFactory.getLogger("UHDFast");

    private static KeyMapping hudToggleKey;

    public UHDFast(IEventBus modEventBus, ModContainer modContainer) {
        long totalStart = System.nanoTime();

        LOGGER.info("========================================");
        LOGGER.info("  UHDFast - Intel Xe Architecture Optimizer");
        LOGGER.info("  Version: 0.1.0 Beta");
        LOGGER.info("  Author: XOR-YH");
        LOGGER.info("  Deep Xe Architecture Optimization");
        LOGGER.info("========================================");

        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, UHDFastConfig.SPEC,
                "uhdfast-client.toml");

        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (mc, screen) -> new com.xor.uhdfast.gui.UHDFastConfigScreen(screen));

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::registerKeys);
        modEventBus.addListener(this::registerGuiLayers);

        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, AggressiveRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, this::onRenderLevelStage);

        LOGGER.info("Constructor completed in {} ms", formatMs(System.nanoTime() - totalStart));
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Common setup starting...");

        event.enqueueWork(() -> {
            try {
                // Initialize mod compatibility detection FIRST
                ModCompatibility.init();

                IntelXeDetector.detect();

                if (IntelXeDetector.isXeArchitecture()) {
                    LOGGER.info("--- Xe Architecture Detected ---");
                    LOGGER.info("Architecture: {}", IntelXeDetector.getArchitectureDescription());
                    LOGGER.info("EU Count: {}", IntelXeDetector.getEUCount());
                    LOGGER.info("Optimization potential: {}%",
                            String.format("%.0f", IntelXeDetector.getOptimizationPotential() * 100));

                    applyPerformanceOptimizations();

                    int cacheSize = UHDFastConfig.chunkCacheSize.get();
                    ChunkDataCache.init(cacheSize);

                    int preloadDistance = UHDFastConfig.chunkPreloadDistance.get();
                    ChunkPreloader.getInstance().init(preloadDistance);

                    // Initialize core GPU optimization modules only
                    // GPU compute systems (FrustumCuller, InstancedRenderer, LightingCompute)
                    // are initialized lazily on first use to speed up startup
                    com.xor.uhdfast.gpu.XePowerOptimizer.initialize();
                    com.xor.uhdfast.gpu.XeRenderOptimizer.applyXeOptimizations();
                    // XeFrustumCuller被移除（因异步双缓冲导致方块不渲染）
                    // com.xor.uhdfast.gpu.XeFrustumCuller.initialize();
                    com.xor.uhdfast.gpu.XeLightingIntegration.initialize();
                    com.xor.uhdfast.gpu.XeChunkGPUAccelerator.initialize();
                    com.xor.uhdfast.gpu.XeFenceScheduler.initialize();
                    com.xor.uhdfast.optimizer.XeEntityOptimizer.initialize();
                    com.xor.uhdfast.optimizer.XeEntityTickOptimizer.initialize();
                    com.xor.uhdfast.optimizer.XeLoadOptimizer.initialize();

                    // GPU performance monitoring and load balancing
                    com.xor.uhdfast.gpu.GPUMemoryMonitor.initialize();
                    com.xor.uhdfast.gpu.GPULoadBalancer.initialize();

                    // GPU acceleration for mod integration
                    com.xor.uhdfast.gpu.integration.OptionalModIntegrationManager.initialize();
                } else {
                    LOGGER.warn("Xe Architecture NOT Detected - using fallback optimizations");
                    applyPerformanceOptimizations();
                    com.xor.uhdfast.optimizer.XeEntityOptimizer.initialize();
                    com.xor.uhdfast.optimizer.XeEntityTickOptimizer.initialize();
                    com.xor.uhdfast.optimizer.XeLoadOptimizer.initialize();
                    com.xor.uhdfast.gpu.XePowerOptimizer.initialize();

                    // GPU acceleration for mod integration (fallback path for non-Xe)
                    com.xor.uhdfast.gpu.integration.OptionalModIntegrationManager.initialize();
                }

                // 初始化光影加载器
                try {
                    if (!com.xor.uhdfast.compat.ModCompatibility.shouldDisableBuiltinShaders()) {
                        com.xor.uhdfast.shaderpack.ShaderPackManager.getInstance().initialize();
                        LOGGER.info("Shader Pack Manager initialized");

                        // 如果配置启用了光影，自动加载配置中的光影包
                        if (UHDFastConfig.enableShaderPacks.get()) {
                            String packName = UHDFastConfig.shaderPackName.get();
                            if (packName != null && !packName.isEmpty()) {
                                boolean loaded = com.xor.uhdfast.shaderpack.ShaderPackManager.getInstance()
                                        .loadPack(packName);
                                if (loaded) {
                                    LOGGER.info("Shader pack loaded: {}", packName);
                                } else {
                                    LOGGER.warn("Failed to load shader pack: {}", packName);
                                }
                            }
                        }
                    } else {
                        LOGGER.info("Built-in shader pack loader disabled (Iris/Oculus detected)");
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize shader pack system: {}", e.getMessage());
                }

            } catch (Exception e) {
                LOGGER.error("Failed to initialize optimizations: {}", e.getMessage(), e);
            }
        });

        LOGGER.info("Common setup dispatched");
    }

    private void applyPerformanceOptimizations() {
        long start = System.nanoTime();

        applyAggressiveRenderingOptimizations();
        applyMemoryOptimizations();

        LOGGER.info("Performance optimizations applied in {} ms", formatMs(System.nanoTime() - start));
    }

    private void applyAggressiveRenderingOptimizations() {
        LOGGER.info("--- Applying Aggressive Rendering Optimizations ---");

        try {
            Minecraft mc = Minecraft.getInstance();
            Options opts = mc.options;

            if (opts != null) {
                // 不强制修改renderDistance/simulationDistance，让用户配置优先
                // 其他优化选项不会影响视距，可安全应用
                opts.graphicsMode().set(net.minecraft.client.GraphicsStatus.FAST);
                LOGGER.info("  Graphics Mode: FAST");

                setOptionValue(opts, "clouds", Boolean.class, false);

                opts.entityShadows().set(false);
                LOGGER.info("  Entity Shadows: OFF");

                opts.mipmapLevels().set(0);
                LOGGER.info("  Mipmap Levels: 0");

                opts.biomeBlendRadius().set(2);
                LOGGER.info("  Biome Blend: 2 (default)");

                setOptionValue(opts, "smoothLighting", Boolean.class, false);
                LOGGER.info("  Smooth Lighting: OFF");
            }
        } catch (Exception e) {
            LOGGER.debug("  Rendering options skipped: {}", e.getMessage());
        }

        try {
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            LOGGER.info("  OpenGL culling applied");
        } catch (Exception e) {
            LOGGER.debug("  OpenGL hints failed: {}", e.getMessage());
        }

        LOGGER.info("--- Rendering Optimizations Complete ---");
    }

    @SuppressWarnings({ "unchecked", "null" })
    private <T> void setOptionValue(Options opts, String fieldName, Class<T> type, T value) {
        try {
            Field field = Options.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            @SuppressWarnings("all")
            OptionInstance<T> option = (OptionInstance<T>) field.get(opts);
            option.set(value);
            LOGGER.info("  {} -> {}", fieldName, value);
        } catch (NoSuchFieldException e) {
            try {
                Field[] fields = Options.class.getDeclaredFields();
                for (Field f : fields) {
                    if (OptionInstance.class.isAssignableFrom(f.getType())
                            && f.getName().toLowerCase().contains(fieldName.toLowerCase())) {
                        f.setAccessible(true);
                        @SuppressWarnings("all")
                        OptionInstance<T> option = (OptionInstance<T>) f.get(opts);
                        option.set(value);
                        LOGGER.info("  {} (mapped from {}) -> {}", f.getName(), fieldName, value);
                        return;
                    }
                }
            } catch (Exception ex) {
                LOGGER.debug("  Failed to set option {}: {}", fieldName, ex.getMessage());
            }
        } catch (Exception e) {
            LOGGER.debug("  Failed to set option {}: {}", fieldName, e.getMessage());
        }
    }

    private void applyMemoryOptimizations() {
        LOGGER.info("--- Applying Memory Optimizations ---");

        Runtime rt = Runtime.getRuntime();
        LOGGER.info("  JVM Max Memory: {} MB", rt.maxMemory() / 1024 / 1024);
        LOGGER.info("  JVM Free Memory: {} MB", rt.freeMemory() / 1024 / 1024);

        // 移除System.gc()调用，避免进入游戏时的卡顿
        LOGGER.info("  Memory optimization applied (no forced GC)");

        LOGGER.info("--- Memory Optimizations Complete ---");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Client setup complete");
    }

    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            com.xor.uhdfast.optimizer.XeEntityOptimizer.onFrameStart();
        }
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        hudToggleKey = new KeyMapping("key.uhdfast.togglehud", GLFW.GLFW_KEY_F6, "key.categories.uhdfast");
        event.register(hudToggleKey);
    }

    @SuppressWarnings("all")
    private void registerGuiLayers(RegisterGuiLayersEvent event) {
        @SuppressWarnings("all")
        net.minecraft.resources.ResourceLocation hudId = PerformanceHUD.HUD_ID;
        event.registerAboveAll(hudId, (guiGraphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            PerformanceHUD.render(guiGraphics, deltaTracker.getGameTimeDeltaPartialTick(false),
                    mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        });
    }

    private static String formatMs(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }
}
