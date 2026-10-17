package com.xor.uhdfast.gui;

import com.xor.uhdfast.UHDFastConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * UHDFast 配置界面（Sodium 风格）
 * 左侧标签栏 + 右侧内容区的经典布局
 */
@SuppressWarnings("null")
public class UHDFastConfigScreen extends Screen {

        private final Screen parentScreen;
        private TechButton doneButton;
        private TechButton resetButton;
        private TechButton cancelButton;
        private TechTabBar tabBar;

        // 按分类存储的控件列表
        private final List<List<TechWidget>> categoryWidgets = new ArrayList<>();
        private int currentCategory = 0;

        private int scrollOffset = 0;
        private int maxScrollOffset = 0;
        private boolean isDraggingScrollbar = false;
        private double dragStartY = 0;
        private int dragStartScroll = 0;

        // 布局常量
        private static final int SIDEBAR_WIDTH = 70;
        private static final int SCROLLBAR_WIDTH = 8;
        private static final int CONTENT_LEFT = SIDEBAR_WIDTH + 10;
        private static final int CONTENT_RIGHT_OFFSET = 60;
        private static final int ITEM_HEIGHT = 28;
        private static final int HEADER_HEIGHT = 45;
        private static final int FOOTER_HEIGHT = 36;
        private static final int STATUS_BAR_HEIGHT = 24;

        public UHDFastConfigScreen(Screen parentScreen) {
                super(Component.translatable("uhdfast.config.title"));
                this.parentScreen = parentScreen;
        }

        @Override
        protected void init() {
                this.clearWidgets();

                // 初始化分类列表（11个分类）
                // [光影功能已关闭] 原为12个分类，光影为第12个
                categoryWidgets.clear();
                for (int i = 0; i < 11; i++) {
                        categoryWidgets.add(new ArrayList<>());
                }

                // 创建标签栏（留出足够间距避免与状态栏重叠）
                int tabBarHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT - STATUS_BAR_HEIGHT - 10;
                tabBar = new TechTabBar(0, HEADER_HEIGHT, SIDEBAR_WIDTH, tabBarHeight);

                // 添加标签（纯文字，无图标）
                tabBar.addTab(Component.translatable("uhdfast.config.tab.general"), () -> switchCategory(0));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.render"), () -> switchCategory(1));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.particles"), () -> switchCategory(2));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.entityTick"), () -> switchCategory(3));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.other"), () -> switchCategory(4));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.xe"), () -> switchCategory(5));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.preloadCache"), () -> switchCategory(6));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.acceleratedRendering"),
                                () -> switchCategory(7));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.gpuUsageControl"), () -> switchCategory(8));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.superResolution"), () -> switchCategory(9));
                tabBar.addTab(Component.translatable("uhdfast.config.tab.gpuWorldgen"), () -> switchCategory(10));
                // [光影功能已关闭] 光影设置标签
                // tabBar.addTab(Component.translatable("uhdfast.config.tab.shaderPacks"), () ->
                // switchCategory(11));

                // 科技风格按钮
                int buttonWidth = 110;
                int buttonGap = 10;
                int totalWidth = buttonWidth * 3 + buttonGap * 2;
                int centerX = this.width / 2;
                int startX = centerX - totalWidth / 2;
                int buttonY = this.height - FOOTER_HEIGHT + 6;

                doneButton = new TechButton(startX, buttonY, buttonWidth, 22,
                                Component.translatable("uhdfast.config.done"), TechButton.ButtonStyle.PRIMARY);
                doneButton.setOnClick(() -> {
                        saveConfig();
                        this.minecraft.setScreen(parentScreen);
                });

                resetButton = new TechButton(startX + buttonWidth + buttonGap, buttonY, buttonWidth, 22,
                                Component.translatable("uhdfast.config.reset"), TechButton.ButtonStyle.DANGER);
                resetButton.setOnClick(() -> {
                        resetConfig();
                        refreshWidgets();
                });

                cancelButton = new TechButton(startX + buttonWidth * 2 + buttonGap * 2, buttonY, buttonWidth, 22,
                                Component.translatable("uhdfast.config.cancel"), TechButton.ButtonStyle.SECONDARY);
                cancelButton.setOnClick(() -> {
                        this.minecraft.setScreen(parentScreen);
                });

                this.addRenderableWidget(doneButton);
                this.addRenderableWidget(resetButton);
                this.addRenderableWidget(cancelButton);

                buildLayout();
                refreshWidgets();
                switchCategory(0);
        }

        private void buildLayout() {
                int contentWidth = this.width - CONTENT_LEFT - CONTENT_RIGHT_OFFSET;

                // === 通用设置 ===
                List<TechWidget> general = categoryWidgets.get(0);
                general.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20, "uhdfast.config.section.general",
                                IntelArcBackgroundRenderer.COLOR_INTEL_BLUE));
                general.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableOptimizations", "uhdfast.config.enableOptimizations.desc"));

                // === 渲染优化 ===
                List<TechWidget> render = categoryWidgets.get(1);
                render.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20, "uhdfast.config.section.render",
                                IntelArcBackgroundRenderer.COLOR_CYAN_BLUE));
                render.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableRenderDistanceOptimization",
                                "uhdfast.config.enableRenderDistanceOptimization.desc"));
                render.add(new TechSliderWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.renderDistanceMultiplier", 0.3, 3.0, 0.1));
                render.add(new TechToggleWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableVSyncOptimization",
                                "uhdfast.config.enableVSyncOptimization.desc"));
                render.add(new TechSliderWidget(CONTENT_LEFT, 104, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.targetFPS", 30, 120, 1));

                // === 粒子优化 ===
                List<TechWidget> particles = categoryWidgets.get(2);
                particles.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20,
                                "uhdfast.config.section.particles",
                                IntelArcBackgroundRenderer.COLOR_ACCENT_GREEN));
                particles.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableChunkLoadingOptimization",
                                "uhdfast.config.enableChunkLoadingOptimization.desc"));

                // === Entity Tick优化 ===
                List<TechWidget> entityTick = categoryWidgets.get(3);
                entityTick.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20,
                                "uhdfast.config.section.entityTick",
                                0xFFDD44FF));
                entityTick.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableEntityTickOptimization",
                                "uhdfast.config.enableEntityTickOptimization.desc"));
                entityTick.add(new TechToggleWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableEntityTickInterpolation",
                                "uhdfast.config.enableEntityTickInterpolation.desc"));
                entityTick.add(new TechToggleWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableRedundantAISkip", "uhdfast.config.enableRedundantAISkip.desc"));
                entityTick.add(new TechSliderWidget(CONTENT_LEFT, 104, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.entityTickBudgetMs", 4, 30, 1));
                entityTick.add(new TechSliderWidget(CONTENT_LEFT, 132, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.entityTickLodHalfDistance", 8, 64, 4));
                entityTick.add(new TechSliderWidget(CONTENT_LEFT, 160, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.entityTickLodQuarterDistance", 16, 128, 8));
                entityTick.add(new TechSliderWidget(CONTENT_LEFT, 188, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.entityTickLodSkipAIDistance", 32, 256, 16));
                entityTick.add(new TechSliderWidget(CONTENT_LEFT, 216, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.entityTickLodFrozenDistance", 64, 384, 16));

                // === 其他优化 ===
                List<TechWidget> other = categoryWidgets.get(4);
                other.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20, "uhdfast.config.section.other",
                                IntelArcBackgroundRenderer.COLOR_INTEL_BLUE));
                other.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableEntityRenderOptimization",
                                "uhdfast.config.enableEntityRenderOptimization.desc"));
                other.add(new TechToggleWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableTextureOptimization",
                                "uhdfast.config.enableTextureOptimization.desc"));
                other.add(new TechToggleWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableGPUMemoryOptimization",
                                "uhdfast.config.enableGPUMemoryOptimization.desc"));
                other.add(new TechToggleWidget(CONTENT_LEFT, 104, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableCPULoadBalancing", "uhdfast.config.enableCPULoadBalancing.desc"));

                // === Xe专属优化 ===
                List<TechWidget> xe = categoryWidgets.get(5);
                xe.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20, "uhdfast.config.section.xe",
                                0xFFFFAA00));
                xe.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableXeShaderCache", "uhdfast.config.enableXeShaderCache.desc"));
                xe.add(new TechSliderWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.xeShaderCacheSize", 64, 1024, 32));
                xe.add(new TechToggleWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableXeMediaEngine", "uhdfast.config.enableXeMediaEngine.desc"));
                xe.add(new TechToggleWidget(CONTENT_LEFT, 104, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableXeUnifiedMemory", "uhdfast.config.enableXeUnifiedMemory.desc"));
                xe.add(new TechToggleWidget(CONTENT_LEFT, 132, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableXeBatchRenderer", "uhdfast.config.enableXeBatchRenderer.desc"));
                xe.add(new TechSliderWidget(CONTENT_LEFT, 160, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.xeMaxBatchSize", 128, 2048, 64));
                xe.add(new TechToggleWidget(CONTENT_LEFT, 188, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableXeComputePipeline",
                                "uhdfast.config.enableXeComputePipeline.desc"));
                xe.add(new TechToggleWidget(CONTENT_LEFT, 216, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableXeTextureStreaming",
                                "uhdfast.config.enableXeTextureStreaming.desc"));
                xe.add(new TechSliderWidget(CONTENT_LEFT, 244, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.xeTextureStreamingBufferSize", 64, 1024, 32));
                xe.add(new TechToggleWidget(CONTENT_LEFT, 272, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableXePerformanceProfiler",
                                "uhdfast.config.enableXePerformanceProfiler.desc"));
                xe.add(new TechToggleWidget(CONTENT_LEFT, 300, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableXeChunkGPUAcceleration",
                                "uhdfast.config.enableXeChunkGPUAcceleration.desc"));
                xe.add(new TechSliderWidget(CONTENT_LEFT, 328, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.xeChunkGPUWorkGroupSize", 32, 128, 16));
                xe.add(new TechSliderWidget(CONTENT_LEFT, 356, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.xeMemoryWarningThreshold", 0.5, 0.95, 0.05));

                // === 预加载缓存 ===
                List<TechWidget> preloadCache = categoryWidgets.get(6);
                preloadCache.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20,
                                "uhdfast.config.section.preloadCache",
                                IntelArcBackgroundRenderer.COLOR_ACCENT_GREEN));
                preloadCache.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableChunkPreloading", "uhdfast.config.enableChunkPreloading.desc"));
                preloadCache.add(new TechSliderWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.chunkPreloadDistance", 8, 32, 4));
                preloadCache.add(new TechToggleWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableChunkCaching", "uhdfast.config.enableChunkCaching.desc"));
                preloadCache.add(new TechSliderWidget(CONTENT_LEFT, 104, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.chunkCacheSize", 50, 500, 50));

                // === 加速渲染 ===
                List<TechWidget> acceleratedRendering = categoryWidgets.get(7);
                acceleratedRendering.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20,
                                "uhdfast.config.section.acceleratedRendering",
                                0xFFFF6644));
                acceleratedRendering.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enablePersistentBuffer", "uhdfast.config.enablePersistentBuffer.desc"));
                acceleratedRendering.add(new TechToggleWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableMeshCache", "uhdfast.config.enableMeshCache.desc"));
                acceleratedRendering.add(new TechSliderWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.meshCacheSize", 64, 1024, 32));
                acceleratedRendering.add(new TechToggleWidget(CONTENT_LEFT, 104, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableRenderStateRecovery",
                                "uhdfast.config.enableRenderStateRecovery.desc"));

                // === GPU使用控制 ===
                List<TechWidget> gpuUsageControl = categoryWidgets.get(8);
                gpuUsageControl.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20,
                                "uhdfast.config.section.gpuUsageControl",
                                0xFFFF8844));
                gpuUsageControl.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableGPUUsageLimit", "uhdfast.config.enableGPUUsageLimit.desc"));
                gpuUsageControl.add(new TechSliderWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.maxGPUUsagePercent", 50, 100, 5));
                gpuUsageControl.add(new TechToggleWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableFrameRateLimiter", "uhdfast.config.enableFrameRateLimiter.desc"));
                gpuUsageControl.add(new TechSliderWidget(CONTENT_LEFT, 104, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.maxFrameRate", 0, 240, 10));

                // === 超分辨率 ===
                List<TechWidget> superResolution = categoryWidgets.get(9);
                superResolution.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20,
                                "uhdfast.config.section.superResolution",
                                0xFF44CCFF));
                superResolution.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableResolutionScaling",
                                "uhdfast.config.enableResolutionScaling.desc"));
                superResolution.add(new TechSliderWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.renderScalePercent", 25, 200, 25));
                superResolution.add(new TechSliderWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.scalingQuality", 0, 2, 1));

                // === GPU世界生成 ===
                List<TechWidget> gpuWorldgen = categoryWidgets.get(10);
                gpuWorldgen.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20,
                                "uhdfast.config.section.gpuWorldgen",
                                0xFF44FFAA));
                gpuWorldgen.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableGPUWorldgen", "uhdfast.config.enableGPUWorldgen.desc"));
                gpuWorldgen.add(new TechSliderWidget(CONTENT_LEFT, 48, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.gpuGenerationBatchSize", 4, 64, 4));
                gpuWorldgen.add(new TechToggleWidget(CONTENT_LEFT, 76, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.fallbackToCPU", "uhdfast.config.fallbackToCPU.desc"));
                gpuWorldgen.add(new TechToggleWidget(CONTENT_LEFT, 104, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.enableMDI", "uhdfast.config.enableMDI.desc"));
                gpuWorldgen.add(new TechSliderWidget(CONTENT_LEFT, 132, contentWidth, ITEM_HEIGHT,
                                "uhdfast.config.mdiBatchSize", 256, 8192, 128));

                // [光影功能已关闭] 光影设置控件
                /*
                 * // === 光影设置 ===
                 * List<TechWidget> shaderPacks = categoryWidgets.get(11);
                 * shaderPacks.add(new TechSectionWidget(CONTENT_LEFT, 0, contentWidth, 20,
                 * "uhdfast.config.section.shaderPacks",
                 * 0xFFFF8844));
                 * shaderPacks.add(new TechToggleWidget(CONTENT_LEFT, 20, contentWidth,
                 * ITEM_HEIGHT,
                 * "uhdfast.config.enableShaderPacks",
                 * "uhdfast.config.enableShaderPacks.desc"));
                 * shaderPacks.add(new TechButtonWidget(CONTENT_LEFT, 48, contentWidth,
                 * ITEM_HEIGHT,
                 * Component.translatable("uhdfast.config.shaderPackManager"),
                 * () -> this.minecraft.setScreen(new ShaderPackScreen(this))));
                 * shaderPacks.add(new TechButtonWidget(CONTENT_LEFT, 76, contentWidth,
                 * ITEM_HEIGHT,
                 * Component.translatable("uhdfast.config.openShaderFolder"),
                 * this::openShaderFolder));
                 */
        }

        private void switchCategory(int category) {
                currentCategory = category;
                scrollOffset = 0;
                calculateScroll();
        }

        private void refreshWidgets() {
                // 刷新所有分类的控件
                for (List<TechWidget> widgets : categoryWidgets) {
                        int toggleIdx = 0;
                        int sliderIdx = 0;

                        for (TechWidget w : widgets) {
                                if (w instanceof TechToggleWidget tw) {
                                        switch (toggleIdx) {
                                                case 0 -> tw.set(UHDFastConfig.enableOptimizations.get());
                                                case 1 -> tw.set(UHDFastConfig.enableRenderDistanceOptimization.get());
                                                case 2 -> tw.set(UHDFastConfig.enableVSyncOptimization.get());
                                                case 3 -> tw.set(UHDFastConfig.enableChunkLoadingOptimization.get());
                                                case 4 -> tw.set(UHDFastConfig.enableEntityTickOptimization.get());
                                                case 5 -> tw.set(UHDFastConfig.enableEntityTickInterpolation.get());
                                                case 6 -> tw.set(UHDFastConfig.enableRedundantAISkip.get());
                                                case 7 -> tw.set(UHDFastConfig.enableEntityRenderOptimization.get());
                                                case 8 -> tw.set(UHDFastConfig.enableTextureOptimization.get());
                                                case 9 -> tw.set(UHDFastConfig.enableGPUMemoryOptimization.get());
                                                case 10 -> tw.set(UHDFastConfig.enableCPULoadBalancing.get());
                                                case 11 -> tw.set(UHDFastConfig.enableXeShaderCache.get());
                                                case 13 -> tw.set(UHDFastConfig.enableXeMediaEngine.get());
                                                case 14 -> tw.set(UHDFastConfig.enableXeUnifiedMemory.get());
                                                case 15 -> tw.set(UHDFastConfig.enableXeBatchRenderer.get());
                                                case 16 -> tw.set(UHDFastConfig.enableXeComputePipeline.get());
                                                case 17 -> tw.set(UHDFastConfig.enableXeTextureStreaming.get());
                                                case 18 -> tw.set(UHDFastConfig.enableXePerformanceProfiler.get());
                                                case 19 -> tw.set(UHDFastConfig.enableXeChunkGPUAcceleration.get());
                                                case 20 -> tw.set(UHDFastConfig.enableChunkPreloading.get());
                                                case 21 -> tw.set(UHDFastConfig.enableChunkCaching.get());
                                                case 22 -> tw.set(UHDFastConfig.enablePersistentBuffer.get());
                                                case 23 -> tw.set(UHDFastConfig.enableMeshCache.get());
                                                case 24 -> tw.set(UHDFastConfig.enableRenderStateRecovery.get());
                                                case 25 -> tw.set(UHDFastConfig.enableGPUUsageLimit.get());
                                                case 26 -> tw.set(UHDFastConfig.enableFrameRateLimiter.get());
                                                case 27 -> tw.set(UHDFastConfig.enableResolutionScaling.get());
                                                case 28 -> tw.set(UHDFastConfig.enableGPUWorldgen.get());
                                                case 29 -> tw.set(UHDFastConfig.fallbackToCPU.get());
                                                case 30 -> tw.set(UHDFastConfig.enableMDI.get());
                                                // [光影功能已关闭] case 31 -> tw.set(UHDFastConfig.enableShaderPacks.get());
                                        }
                                        toggleIdx++;
                                } else if (w instanceof TechSliderWidget sw) {
                                        switch (sliderIdx) {
                                                case 0 -> sw.set(UHDFastConfig.renderDistanceMultiplier.get());
                                                case 1 -> sw.set(UHDFastConfig.targetFPS.get());
                                                case 2 -> sw.set(UHDFastConfig.entityTickBudgetMs.get());
                                                case 3 -> sw.set(UHDFastConfig.entityTickLodHalfDistance.get());
                                                case 4 -> sw.set(UHDFastConfig.entityTickLodQuarterDistance.get());
                                                case 5 -> sw.set(UHDFastConfig.entityTickLodSkipAIDistance.get());
                                                case 6 -> sw.set(UHDFastConfig.entityTickLodFrozenDistance.get());
                                                case 7 -> sw.set(UHDFastConfig.xeShaderCacheSize.get());
                                                case 8 -> sw.set(UHDFastConfig.xeMaxBatchSize.get());
                                                case 9 -> sw.set(UHDFastConfig.xeTextureStreamingBufferSize.get());
                                                case 10 -> sw.set(UHDFastConfig.xeChunkGPUWorkGroupSize.get());
                                                case 12 -> sw.set(UHDFastConfig.xeMemoryWarningThreshold.get());
                                                case 13 -> sw.set(UHDFastConfig.chunkPreloadDistance.get());
                                                case 14 -> sw.set(UHDFastConfig.chunkCacheSize.get());
                                                case 15 -> sw.set(UHDFastConfig.meshCacheSize.get());
                                                case 16 -> sw.set(UHDFastConfig.maxGPUUsagePercent.get());
                                                case 17 -> sw.set(UHDFastConfig.maxFrameRate.get());
                                                case 18 -> sw.set(UHDFastConfig.renderScalePercent.get());
                                                case 19 -> sw.set(UHDFastConfig.scalingQuality.get());
                                                case 20 -> sw.set(UHDFastConfig.gpuGenerationBatchSize.get());
                                                case 21 -> sw.set(UHDFastConfig.mdiBatchSize.get());
                                        }
                                        sliderIdx++;
                                }
                        }
                }
        }

        private void saveConfig() {
                // 保存所有分类的配置
                for (List<TechWidget> widgets : categoryWidgets) {
                        int toggleIdx = 0;
                        int sliderIdx = 0;

                        for (TechWidget w : widgets) {
                                if (w instanceof TechToggleWidget tw) {
                                        switch (toggleIdx) {
                                                case 0 -> UHDFastConfig.enableOptimizations.set(tw.get());
                                                case 1 -> UHDFastConfig.enableRenderDistanceOptimization.set(tw.get());
                                                case 2 -> UHDFastConfig.enableVSyncOptimization.set(tw.get());
                                                case 3 -> UHDFastConfig.enableChunkLoadingOptimization.set(tw.get());
                                                case 4 -> UHDFastConfig.enableEntityTickOptimization.set(tw.get());
                                                case 5 -> UHDFastConfig.enableEntityTickInterpolation.set(tw.get());
                                                case 6 -> UHDFastConfig.enableRedundantAISkip.set(tw.get());
                                                case 7 -> UHDFastConfig.enableEntityRenderOptimization.set(tw.get());
                                                case 8 -> UHDFastConfig.enableTextureOptimization.set(tw.get());
                                                case 9 -> UHDFastConfig.enableGPUMemoryOptimization.set(tw.get());
                                                case 10 -> UHDFastConfig.enableCPULoadBalancing.set(tw.get());
                                                case 11 -> UHDFastConfig.enableXeShaderCache.set(tw.get());
                                                case 12 -> UHDFastConfig.enableXeMediaEngine.set(tw.get());
                                                case 14 -> UHDFastConfig.enableXeUnifiedMemory.set(tw.get());
                                                case 15 -> UHDFastConfig.enableXeBatchRenderer.set(tw.get());
                                                case 16 -> UHDFastConfig.enableXeComputePipeline.set(tw.get());
                                                case 17 -> UHDFastConfig.enableXeTextureStreaming.set(tw.get());
                                                case 18 -> UHDFastConfig.enableXePerformanceProfiler.set(tw.get());
                                                case 19 -> UHDFastConfig.enableXeChunkGPUAcceleration.set(tw.get());
                                                case 20 -> UHDFastConfig.enableChunkPreloading.set(tw.get());
                                                case 21 -> UHDFastConfig.enableChunkCaching.set(tw.get());
                                                case 22 -> UHDFastConfig.enablePersistentBuffer.set(tw.get());
                                                case 23 -> UHDFastConfig.enableMeshCache.set(tw.get());
                                                case 24 -> UHDFastConfig.enableRenderStateRecovery.set(tw.get());
                                                case 25 -> UHDFastConfig.enableGPUUsageLimit.set(tw.get());
                                                case 26 -> UHDFastConfig.enableFrameRateLimiter.set(tw.get());
                                                case 27 -> UHDFastConfig.enableResolutionScaling.set(tw.get());
                                                case 28 -> UHDFastConfig.enableGPUWorldgen.set(tw.get());
                                                case 29 -> UHDFastConfig.fallbackToCPU.set(tw.get());
                                                case 30 -> UHDFastConfig.enableMDI.set(tw.get());
                                                // [光影功能已关闭] case 31 -> UHDFastConfig.enableShaderPacks.set(tw.get());
                                        }
                                        toggleIdx++;
                                } else if (w instanceof TechSliderWidget sw) {
                                        switch (sliderIdx) {
                                                case 0 -> UHDFastConfig.renderDistanceMultiplier.set(sw.get());
                                                case 1 -> UHDFastConfig.targetFPS.set((int) sw.get());
                                                case 2 -> UHDFastConfig.entityTickBudgetMs.set((int) sw.get());
                                                case 3 -> UHDFastConfig.entityTickLodHalfDistance.set((int) sw.get());
                                                case 4 ->
                                                        UHDFastConfig.entityTickLodQuarterDistance.set((int) sw.get());
                                                case 5 -> UHDFastConfig.entityTickLodSkipAIDistance.set((int) sw.get());
                                                case 6 -> UHDFastConfig.entityTickLodFrozenDistance.set((int) sw.get());
                                                case 7 -> UHDFastConfig.xeShaderCacheSize.set((int) sw.get());
                                                case 8 -> UHDFastConfig.xeMaxBatchSize.set((int) sw.get());
                                                case 9 ->
                                                        UHDFastConfig.xeTextureStreamingBufferSize.set((int) sw.get());
                                                case 11 -> UHDFastConfig.xeChunkGPUWorkGroupSize.set((int) sw.get());
                                                case 12 -> UHDFastConfig.xeMemoryWarningThreshold.set(sw.get());
                                                case 13 -> UHDFastConfig.chunkPreloadDistance.set((int) sw.get());
                                                case 14 -> UHDFastConfig.chunkCacheSize.set((int) sw.get());
                                                case 15 -> UHDFastConfig.meshCacheSize.set((int) sw.get());
                                                case 16 -> UHDFastConfig.maxGPUUsagePercent.set((int) sw.get());
                                                case 17 -> UHDFastConfig.maxFrameRate.set((int) sw.get());
                                                case 18 -> UHDFastConfig.renderScalePercent.set((int) sw.get());
                                                case 19 -> UHDFastConfig.scalingQuality.set((int) sw.get());
                                                case 20 -> UHDFastConfig.gpuGenerationBatchSize.set((int) sw.get());
                                                case 21 -> UHDFastConfig.mdiBatchSize.set((int) sw.get());
                                        }
                                        sliderIdx++;
                                }
                        }
                }
        }

        private void resetConfig() {
                UHDFastConfig.enableOptimizations.set(true);
                UHDFastConfig.enableRenderDistanceOptimization.set(true);
                UHDFastConfig.renderDistanceMultiplier.set(1.0);
                UHDFastConfig.enableVSyncOptimization.set(true);
                UHDFastConfig.targetFPS.set(60);
                UHDFastConfig.enableChunkLoadingOptimization.set(true);
                UHDFastConfig.enableEntityTickOptimization.set(true);
                UHDFastConfig.enableEntityTickInterpolation.set(true);
                UHDFastConfig.enableRedundantAISkip.set(true);
                UHDFastConfig.entityTickBudgetMs.set(12);
                UHDFastConfig.entityTickLodHalfDistance.set(16);
                UHDFastConfig.entityTickLodQuarterDistance.set(48);
                UHDFastConfig.entityTickLodSkipAIDistance.set(96);
                UHDFastConfig.entityTickLodFrozenDistance.set(160);
                UHDFastConfig.enableEntityRenderOptimization.set(true);
                UHDFastConfig.enableTextureOptimization.set(true);
                UHDFastConfig.enableGPUMemoryOptimization.set(true);
                UHDFastConfig.enableCPULoadBalancing.set(true);
                UHDFastConfig.enableXeShaderCache.set(true);
                UHDFastConfig.xeShaderCacheSize.set(256);
                UHDFastConfig.enableXeMediaEngine.set(true);
                UHDFastConfig.enableXeUnifiedMemory.set(true);
                UHDFastConfig.enableXeBatchRenderer.set(true);
                UHDFastConfig.xeMaxBatchSize.set(512);
                UHDFastConfig.enableXeComputePipeline.set(true);
                UHDFastConfig.enableXeTextureStreaming.set(true);
                UHDFastConfig.xeTextureStreamingBufferSize.set(256);
                UHDFastConfig.enableXePerformanceProfiler.set(true);
                UHDFastConfig.xeMemoryWarningThreshold.set(0.8);
                UHDFastConfig.enableXeChunkGPUAcceleration.set(true);
                UHDFastConfig.xeChunkGPUWorkGroupSize.set(64);
                UHDFastConfig.enableChunkPreloading.set(true);
                UHDFastConfig.enableChunkCaching.set(true);
                UHDFastConfig.chunkPreloadDistance.set(16);
                UHDFastConfig.chunkCacheSize.set(200);
                UHDFastConfig.enablePersistentBuffer.set(true);
                UHDFastConfig.enableMeshCache.set(true);
                UHDFastConfig.meshCacheSize.set(256);
                UHDFastConfig.enableRenderStateRecovery.set(true);
                UHDFastConfig.enableGPUUsageLimit.set(true);
                UHDFastConfig.maxGPUUsagePercent.set(80);
                UHDFastConfig.enableFrameRateLimiter.set(true);
                UHDFastConfig.maxFrameRate.set(120);
                UHDFastConfig.enableResolutionScaling.set(true);
                UHDFastConfig.renderScalePercent.set(100);
                UHDFastConfig.scalingQuality.set(1);
                UHDFastConfig.enableGPUWorldgen.set(true);
                UHDFastConfig.gpuGenerationBatchSize.set(16);
                UHDFastConfig.fallbackToCPU.set(true);
                UHDFastConfig.enableMDI.set(true);
                UHDFastConfig.mdiBatchSize.set(2048);
                // [光影功能已关闭] UHDFastConfig.enableShaderPacks.set(false);
                refreshWidgets();
        }

        // [光影功能已关闭] 打开光影文件夹方法
        /*
         * private void openShaderFolder() {
         * UHDFast.LOGGER.info("Opening shader folder...");
         * try {
         * Path shaderPacksDir =
         * Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
         * UHDFast.LOGGER.info("Shader folder path: {}",
         * shaderPacksDir.toAbsolutePath());
         * 
         * if (!Files.exists(shaderPacksDir)) {
         * UHDFast.LOGGER.info("Creating shader folder...");
         * Files.createDirectories(shaderPacksDir);
         * }
         * 
         * // Use Windows explorer command to open folder
         * String path = shaderPacksDir.toAbsolutePath().toString();
         * UHDFast.LOGGER.info("Executing explorer with path: {}", path);
         * Process process = Runtime.getRuntime().exec(new String[] { "explorer", path
         * });
         * process.waitFor();
         * UHDFast.LOGGER.info("Explorer process completed");
         * } catch (Exception e) {
         * UHDFast.LOGGER.error("Failed to open shader folder", e);
         * }
         * }
         */

        private void calculateScroll() {
                List<TechWidget> widgets = categoryWidgets.get(currentCategory);
                int contentHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT - STATUS_BAR_HEIGHT;
                int totalHeight = 0;
                for (TechWidget w : widgets) {
                        if (w.y + ITEM_HEIGHT > totalHeight) {
                                totalHeight = w.y + ITEM_HEIGHT;
                        }
                }
                maxScrollOffset = Math.max(0, totalHeight - contentHeight);
                scrollOffset = Math.min(scrollOffset, maxScrollOffset);
        }

        private int getClipTop() {
                return HEADER_HEIGHT;
        }

        private int getClipBottom() {
                return this.height - FOOTER_HEIGHT - STATUS_BAR_HEIGHT;
        }

        @Override
        public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
                // Intel Arc 风格背景
                IntelArcBackgroundRenderer.render(gg, this.width, this.height, partialTick);

                // 标题栏
                renderHeader(gg, mouseX, mouseY, partialTick);

                // 左侧标签栏
                tabBar.render(gg, mouseX, mouseY);

                // 内容区域（带裁剪）
                int clipTop = getClipTop();
                int clipBottom = getClipBottom();
                gg.enableScissor(CONTENT_LEFT, clipTop, this.width - CONTENT_RIGHT_OFFSET, clipBottom);

                List<TechWidget> widgets = categoryWidgets.get(currentCategory);
                for (TechWidget w : widgets) {
                        int drawY = w.y - scrollOffset + HEADER_HEIGHT;
                        if (drawY + ITEM_HEIGHT >= clipTop && drawY <= clipBottom) {
                                w.render(gg, mouseX, mouseY, partialTick, drawY);
                        }
                }

                gg.disableScissor();

                // 滚动条
                renderScrollbar(gg, clipTop, clipBottom);

                // 底部状态栏
                renderStatusBar(gg);

                // 按钮
                super.render(gg, mouseX, mouseY, partialTick);
        }

        private void renderHeader(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
                var font = this.font;

                gg.fill(0, 0, this.width, HEADER_HEIGHT, 0xCC0A1628);

                int separatorY = HEADER_HEIGHT - 2;
                int steps = this.width / 10;
                for (int i = 0; i < steps; i++) {
                        float ratio = i / (float) (steps - 1);
                        int alpha = (int) (180 * (1 - ratio));
                        int color = (alpha << 24) | 0x0071C5;
                        gg.fill(i * 10, separatorY, (i + 1) * 10, separatorY + 2, color);
                }

                String title = this.title.getString();
                int titleX = 15;
                int titleY = 12;

                int glowAlpha = 40;
                int glowColor = (glowAlpha << 24) | 0x00AEEF;
                for (int i = 2; i > 0; i--) {
                        gg.drawString(font, title, titleX - i, titleY, glowColor, false);
                        gg.drawString(font, title, titleX + i, titleY, glowColor, false);
                }

                gg.drawString(font, title, titleX, titleY, 0xFFFFFFFF, false);

                String subtitle = "Intel Arc GPU Optimization";
                int subtitleX = titleX + font.width(title) + 12;
                int subtitleColor = 0xFF00AEEF;
                gg.drawString(font, subtitle, subtitleX, titleY + 2, subtitleColor, false);

                String logo = "◆ ARC";
                int logoX = this.width - font.width(logo) - 15;
                int logoY = 12;
                int logoColor = 0xFF00D4FF;

                for (int i = 1; i > 0; i--) {
                        int glow = (20 << 24) | 0x00D4FF;
                        gg.drawString(font, logo, logoX - i, logoY, glow, false);
                        gg.drawString(font, logo, logoX + i, logoY, glow, false);
                }
                gg.drawString(font, logo, logoX, logoY, logoColor, false);
        }

        private void renderScrollbar(GuiGraphics gg, int clipTop, int clipBottom) {
                if (maxScrollOffset <= 0)
                        return;

                int barX = this.width - CONTENT_RIGHT_OFFSET + 10;
                int barH = clipBottom - clipTop;
                int thumbH = Math.max(20, barH * barH / (maxScrollOffset + barH));
                int thumbY = clipTop + (int) ((double) scrollOffset / maxScrollOffset * (barH - thumbH));

                gg.fill(barX, clipTop, barX + SCROLLBAR_WIDTH, clipTop + barH, 0xFF1A2535);
                gg.renderOutline(barX, clipTop, SCROLLBAR_WIDTH, barH, 0xFF223344);

                int thumbStart = 0xFF0071C5;
                int thumbEnd = 0xFF00AEEF;
                int thumbSteps = Math.max(2, thumbH / 4);
                for (int i = 0; i < thumbSteps; i++) {
                        float ratio = i / (float) (thumbSteps - 1);
                        int r = (int) (((thumbStart >> 16) & 0xFF)
                                        + (((thumbEnd >> 16) & 0xFF) - ((thumbStart >> 16) & 0xFF)) * ratio);
                        int g = (int) (((thumbStart >> 8) & 0xFF)
                                        + (((thumbEnd >> 8) & 0xFF) - ((thumbStart >> 8) & 0xFF)) * ratio);
                        int b = (int) ((thumbStart & 0xFF) + (thumbEnd & 0xFF) - (thumbStart & 0xFF)) * ratio > 255
                                        ? 255
                                        : (int) ((thumbStart & 0xFF)
                                                        + ((thumbEnd & 0xFF) - (thumbStart & 0xFF)) * ratio);

                        int color = 0xFF000000 | (r << 16) | (g << 8) | b;
                        int segY = thumbY + (int) (ratio * thumbH);
                        int segH = Math.max(1, thumbH / thumbSteps);
                        gg.fill(barX, segY, barX + SCROLLBAR_WIDTH, segY + segH, color);
                }

                gg.renderOutline(barX, thumbY, SCROLLBAR_WIDTH, thumbH, 0xFF00D4FF);
        }

        private void renderStatusBar(GuiGraphics gg) {
                int barY = this.height - STATUS_BAR_HEIGHT - FOOTER_HEIGHT + 4;
                int barH = STATUS_BAR_HEIGHT;

                gg.fill(0, barY, this.width, barY + barH, 0xAA0A1628);
                gg.fill(0, barY, this.width, barY + 1, 0xFF0071C5);

                var font = this.font;

                String statusText = "UHDFast Config";
                int statusColor = 0xFF00AEEF;
                gg.drawString(font, statusText, 10, barY + 6, statusColor, false);
        }

        @Override
        public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
                // 先检查标签栏点击
                if (tabBar.mouseClicked(mouseX, mouseY, button)) {
                        return true;
                }

                if (button == 0) {
                        int barX = this.width - CONTENT_RIGHT_OFFSET + 10;
                        int clipTop = getClipTop();
                        int clipBottom = getClipBottom();
                        int barH = clipBottom - clipTop;

                        if (maxScrollOffset > 0 && mouseX >= barX && mouseX <= barX + SCROLLBAR_WIDTH
                                        && mouseY >= clipTop
                                        && mouseY <= clipBottom) {
                                int thumbH = Math.max(20, barH * barH / (maxScrollOffset + barH));
                                int thumbY = clipTop
                                                + (int) ((double) scrollOffset / maxScrollOffset * (barH - thumbH));

                                if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
                                        isDraggingScrollbar = true;
                                        dragStartY = mouseY;
                                        dragStartScroll = scrollOffset;
                                        return true;
                                }

                                double clickRatio = (mouseY - clipTop) / barH;
                                scrollOffset = (int) Math.max(0,
                                                Math.min(maxScrollOffset, clickRatio * maxScrollOffset));
                                return true;
                        }

                        if (mouseX >= CONTENT_LEFT && mouseX <= this.width - CONTENT_RIGHT_OFFSET && mouseY >= clipTop
                                        && mouseY <= clipBottom) {
                                List<TechWidget> widgets = categoryWidgets.get(currentCategory);
                                for (TechWidget w : widgets) {
                                        int drawY = w.y - scrollOffset + HEADER_HEIGHT;
                                        if (w.mouseClicked(mouseX, mouseY, button, drawY)) {
                                                return true;
                                        }
                                }
                        }
                }
                return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
                if (isDraggingScrollbar && maxScrollOffset > 0) {
                        int clipTop = getClipTop();
                        int clipBottom = getClipBottom();
                        int barH = clipBottom - clipTop;
                        int thumbH = Math.max(20, barH * barH / (maxScrollOffset + barH));

                        double pixelDelta = mouseY - dragStartY;
                        double scrollDelta = pixelDelta / (barH - thumbH) * maxScrollOffset;
                        scrollOffset = (int) Math.max(0, Math.min(maxScrollOffset, dragStartScroll + scrollDelta));
                        return true;
                }

                if (mouseX >= CONTENT_LEFT && mouseX <= this.width - CONTENT_RIGHT_OFFSET) {
                        int clipTop = getClipTop();
                        int clipBottom = getClipBottom();
                        if (mouseY >= clipTop && mouseY <= clipBottom) {
                                List<TechWidget> widgets = categoryWidgets.get(currentCategory);
                                for (TechWidget w : widgets) {
                                        int drawY = w.y - scrollOffset + HEADER_HEIGHT;
                                        if (w.mouseDragged(mouseX, mouseY, button, deltaX, deltaY, drawY)) {
                                                return true;
                                        }
                                }
                        }
                }

                return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
                isDraggingScrollbar = false;
                List<TechWidget> widgets = categoryWidgets.get(currentCategory);
                for (TechWidget w : widgets) {
                        w.mouseReleased(mouseX, mouseY, button);
                }
                return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalDelta, double verticalDelta) {
                // 先检查是否在标签栏区域
                if (mouseX >= 0 && mouseX < SIDEBAR_WIDTH && mouseY >= HEADER_HEIGHT) {
                        if (tabBar != null && tabBar.mouseScrolled(mouseX, mouseY, horizontalDelta, verticalDelta)) {
                                return true;
                        }
                }

                // 否则滚动内容区
                if (maxScrollOffset > 0) {
                        scrollOffset = (int) Math.max(0,
                                        Math.min(maxScrollOffset, scrollOffset - (int) (verticalDelta * 15)));
                }
                return true;
        }
}
