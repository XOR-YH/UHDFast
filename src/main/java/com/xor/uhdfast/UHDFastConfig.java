package com.xor.uhdfast;

import net.neoforged.neoforge.common.ModConfigSpec;

public class UHDFastConfig {
        private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

        public static final ModConfigSpec SPEC;

        public static final ModConfigSpec.BooleanValue enableOptimizations;
        public static final ModConfigSpec.BooleanValue enableRenderDistanceOptimization;
        public static final ModConfigSpec.BooleanValue enableChunkLoadingOptimization;
        public static final ModConfigSpec.BooleanValue enableEntityRenderOptimization;
        public static final ModConfigSpec.IntValue entityRenderDistance;
        public static final ModConfigSpec.IntValue maxEntitiesPerFrame;
        public static final ModConfigSpec.BooleanValue enableEntityLOD;
        public static final ModConfigSpec.DoubleValue entityLODHighDistance;
        public static final ModConfigSpec.DoubleValue entityLODMediumDistance;
        public static final ModConfigSpec.DoubleValue entityLODLowDistance;
        public static final ModConfigSpec.BooleanValue enableEntityFrustumCulling;
        public static final ModConfigSpec.BooleanValue enableEntityOcclusionCulling;
        public static final ModConfigSpec.BooleanValue enableEntityTickOptimization;
        public static final ModConfigSpec.BooleanValue enableEntityTickInterpolation;
        public static final ModConfigSpec.BooleanValue enableRedundantAISkip;
        public static final ModConfigSpec.IntValue entityTickBudgetMs;
        public static final ModConfigSpec.IntValue entityTickLodHalfDistance;
        public static final ModConfigSpec.IntValue entityTickLodQuarterDistance;
        public static final ModConfigSpec.IntValue entityTickLodSkipAIDistance;
        public static final ModConfigSpec.IntValue entityTickLodFrozenDistance;
        public static final ModConfigSpec.BooleanValue enableTextureOptimization;
        public static final ModConfigSpec.BooleanValue enableGPUMemoryOptimization;
        public static final ModConfigSpec.BooleanValue enableCPULoadBalancing;
        public static final ModConfigSpec.DoubleValue renderDistanceMultiplier;
        public static final ModConfigSpec.BooleanValue enableVSyncOptimization;
        public static final ModConfigSpec.IntValue targetFPS;

        public static final ModConfigSpec.BooleanValue enableXeShaderCache;
        public static final ModConfigSpec.BooleanValue enableXeMediaEngine;
        public static final ModConfigSpec.BooleanValue enableXeUnifiedMemory;
        public static final ModConfigSpec.BooleanValue enableXeBatchRenderer;
        public static final ModConfigSpec.BooleanValue enableXeComputePipeline;
        public static final ModConfigSpec.BooleanValue enableXeTextureStreaming;
        public static final ModConfigSpec.BooleanValue enableXePerformanceProfiler;
        public static final ModConfigSpec.BooleanValue enableXeChunkGPUAcceleration;
        public static final ModConfigSpec.IntValue xeShaderCacheSize;
        public static final ModConfigSpec.IntValue xeMaxBatchSize;
        public static final ModConfigSpec.DoubleValue xeMemoryWarningThreshold;
        public static final ModConfigSpec.IntValue xeTextureStreamingBufferSize;
        public static final ModConfigSpec.IntValue xeChunkGPUWorkGroupSize;

        public static final ModConfigSpec.BooleanValue enableChunkPreloading;
        public static final ModConfigSpec.BooleanValue enableChunkCaching;
        public static final ModConfigSpec.IntValue chunkPreloadDistance;
        public static final ModConfigSpec.IntValue chunkCacheSize;

        public static final ModConfigSpec.BooleanValue enablePersistentBuffer;
        public static final ModConfigSpec.BooleanValue enableMeshCache;
        public static final ModConfigSpec.IntValue meshCacheSize;
        public static final ModConfigSpec.BooleanValue enableRenderStateRecovery;

        public static final ModConfigSpec.BooleanValue enableGPUUsageLimit;
        public static final ModConfigSpec.IntValue maxGPUUsagePercent;
        public static final ModConfigSpec.BooleanValue enableFrameRateLimiter;
        public static final ModConfigSpec.IntValue maxFrameRate;

        public static final ModConfigSpec.BooleanValue enableResolutionScaling;
        public static final ModConfigSpec.IntValue renderScalePercent;
        public static final ModConfigSpec.IntValue scalingQuality;

        public static final ModConfigSpec.BooleanValue enableGPUWorldgen;
        public static final ModConfigSpec.IntValue gpuGenerationBatchSize;
        public static final ModConfigSpec.BooleanValue fallbackToCPU;

        public static final ModConfigSpec.BooleanValue enableGPUChunkMeshBuilding;
        public static final ModConfigSpec.BooleanValue enableGPUChunkRendering;
        public static final ModConfigSpec.BooleanValue enableGPUChunkFrustumCulling;
        public static final ModConfigSpec.BooleanValue enableMDI;
        public static final ModConfigSpec.IntValue mdiBatchSize;
        public static final ModConfigSpec.IntValue gpuChunkLODHighDistance;
        public static final ModConfigSpec.IntValue gpuChunkLODMediumDistance;
        public static final ModConfigSpec.IntValue gpuChunkLODLowDistance;
        public static final ModConfigSpec.IntValue gpuChunkLoaderThreads;
        public static final ModConfigSpec.IntValue gpuChunkMaxPendingLoads;

        public static final ModConfigSpec.BooleanValue enableGPUChunkyIntegration;
        public static final ModConfigSpec.IntValue gpuChunkyWorkerThreads;
        public static final ModConfigSpec.IntValue gpuChunkyBatchSize;
        public static final ModConfigSpec.BooleanValue enableGPUDistantHorizonsIntegration;
        public static final ModConfigSpec.IntValue gpuDHWorkerThreads;
        public static final ModConfigSpec.IntValue gpuDHDefaultDetailLevel;

        // GPU Generation Throttle
        public static final ModConfigSpec.BooleanValue enableGPUThrottle;
        public static final ModConfigSpec.IntValue gpuThrottleMaxUsagePercent;
        public static final ModConfigSpec.IntValue gpuThrottleCriticalUsagePercent;
        public static final ModConfigSpec.IntValue gpuThrottleMinFPS;
        public static final ModConfigSpec.IntValue gpuThrottleTargetFPS;
        public static final ModConfigSpec.IntValue gpuThrottleMaxBatchSize;
        public static final ModConfigSpec.IntValue gpuThrottleMinBatchSize;
        public static final ModConfigSpec.IntValue gpuThrottleMaxChunksPerWindow;
        public static final ModConfigSpec.IntValue gpuThrottleWindowSizeMs;

        // Xe Power Optimizer
        public static final ModConfigSpec.BooleanValue enableXePowerOptimizer;
        public static final ModConfigSpec.IntValue xePowerOptimizerFrameBudgetPercent;
        public static final ModConfigSpec.IntValue xePowerOptimizerGpuUtilizationTarget;
        public static final ModConfigSpec.IntValue xePowerOptimizerTemporalSpreadFrames;
        public static final ModConfigSpec.IntValue xePowerOptimizerWorkStealThreshold;
        public static final ModConfigSpec.IntValue xePowerOptimizerWorkReturnThreshold;
        public static final ModConfigSpec.IntValue xePowerOptimizerPowerSaveEscalationFrames;
        public static final ModConfigSpec.IntValue xePowerOptimizerPowerSaveDeescalationFrames;
        public static final ModConfigSpec.IntValue xePowerOptimizerEmergencyPauseDurationMs;
        public static final ModConfigSpec.IntValue xePowerOptimizerMaxChunksPerFrame;
        public static final ModConfigSpec.IntValue xePowerOptimizerMinBatchThreshold;

        // CPU-GPU Coordinator
        public static final ModConfigSpec.BooleanValue enableCpuGpuCoordinator;
        public static final ModConfigSpec.IntValue targetCpuUsage;
        public static final ModConfigSpec.IntValue targetGpuUsage;
        public static final ModConfigSpec.IntValue cpuLowThreshold;
        public static final ModConfigSpec.IntValue cpuHighThreshold;
        public static final ModConfigSpec.IntValue gpuLowThreshold;
        public static final ModConfigSpec.IntValue gpuHighThreshold;
        public static final ModConfigSpec.IntValue quietModeThreshold;
        public static final ModConfigSpec.DoubleValue quietModeWorkFactor;
        public static final ModConfigSpec.IntValue overloadThreshold;

        // Shader Pack Loader
        public static final ModConfigSpec.BooleanValue enableShaderPacks;
        public static final ModConfigSpec.ConfigValue<String> shaderPackName;
        public static final ModConfigSpec.IntValue shadowMapResolution;
        public static final ModConfigSpec.DoubleValue shaderRenderScale;
        public static final ModConfigSpec.IntValue shaderQuality;
        public static final ModConfigSpec.BooleanValue enableShadowRendering;
        public static final ModConfigSpec.BooleanValue enableVolumetricClouds;

        static {
                BUILDER.push("UHDFast - Intel UHD Graphics Optimization");

                BUILDER.comment("General Settings")
                                .push("general");

                enableOptimizations = BUILDER
                                .comment("Enable all UHDFast optimizations")
                                .define("enableOptimizations", true);

                BUILDER.pop();

                BUILDER.comment("Render Optimizations")
                                .push("render");

                enableRenderDistanceOptimization = BUILDER
                                .comment("Optimize render distance for Intel UHD Graphics")
                                .define("enableRenderDistanceOptimization", true);

                renderDistanceMultiplier = BUILDER
                                .comment("Render distance multiplier (0.3 = reduced, 1.0 = normal, 3.0 = 96 chunks)")
                                .defineInRange("renderDistanceMultiplier", 1.5, 0.3, 3.0);

                enableVSyncOptimization = BUILDER
                                .comment("Enable VSync optimization for smoother frame pacing")
                                .define("enableVSyncOptimization", true);

                targetFPS = BUILDER
                                .comment("Target FPS for frame pacing optimization")
                                .defineInRange("targetFPS", 60, 30, 120);

                BUILDER.pop();

                BUILDER.comment("Chunk Loading Optimizations")
                                .push("chunks");

                enableChunkLoadingOptimization = BUILDER
                                .comment("Optimize chunk loading and rebuilding")
                                .define("enableChunkLoadingOptimization", true);

                BUILDER.pop();

                BUILDER.comment("Entity Render Optimizations")
                                .push("entities");

                enableEntityRenderOptimization = BUILDER
                                .comment("Optimize entity rendering with batching")
                                .define("enableEntityRenderOptimization", true);

                entityRenderDistance = BUILDER
                                .comment("Maximum entity render distance in blocks (64-512, higher = more entities visible)")
                                .defineInRange("entityRenderDistance", 256, 64, 512);

                maxEntitiesPerFrame = BUILDER
                                .comment("Maximum entities to render per frame (128-1024, higher = more load)")
                                .defineInRange("maxEntitiesPerFrame", 512, 128, 1024);

                enableEntityLOD = BUILDER
                                .comment("Enable entity LOD (Level of Detail) rendering. Distant entities use simplified rendering for better performance.")
                                .define("enableEntityLOD", true);

                entityLODHighDistance = BUILDER
                                .comment("Distance (in blocks) for HIGH detail rendering. Closer entities get full quality. (default: 24)")
                                .defineInRange("entityLODHighDistance", 24.0, 8.0, 64.0);

                entityLODMediumDistance = BUILDER
                                .comment("Distance (in blocks) for MEDIUM detail rendering. Medium distance entities use simplified shadows/layers. (default: 48)")
                                .defineInRange("entityLODMediumDistance", 48.0, 16.0, 128.0);

                entityLODLowDistance = BUILDER
                                .comment("Distance (in blocks) for LOW detail rendering. Far entities use minimal rendering. (default: 80)")
                                .defineInRange("entityLODLowDistance", 80.0, 32.0, 256.0);

                enableEntityFrustumCulling = BUILDER
                                .comment("Enable entity frustum culling (skip off-screen entities)")
                                .define("enableEntityFrustumCulling", true);

                enableEntityOcclusionCulling = BUILDER
                                .comment("Enable entity occlusion culling (skip blocked entities)")
                                .define("enableEntityOcclusionCulling", true);

                enableEntityTickOptimization = BUILDER
                                .comment("Enable entity tick LOD optimization (reduce AI updates for distant entities)")
                                .define("enableEntityTickOptimization", true);

                enableEntityTickInterpolation = BUILDER
                                .comment("Enable entity tick interpolation (smooth out stutter for reduced-tick entities)")
                                .define("enableEntityTickInterpolation", true);

                enableRedundantAISkip = BUILDER
                                .comment("Skip redundant AI calculations for idle entities (no target, no navigation)")
                                .define("enableRedundantAISkip", true);

                entityTickBudgetMs = BUILDER
                                .comment("Per-frame entity tick budget in milliseconds (higher = more entities ticked per frame)")
                                .defineInRange("entityTickBudgetMs", 12, 4, 30);

                entityTickLodHalfDistance = BUILDER
                                .comment("Distance (in blocks) for HALF_RATE tick updates (default: 16)")
                                .defineInRange("entityTickLodHalfDistance", 16, 8, 64);

                entityTickLodQuarterDistance = BUILDER
                                .comment("Distance (in blocks) for QUARTER_RATE tick updates (default: 48)")
                                .defineInRange("entityTickLodQuarterDistance", 48, 16, 128);

                entityTickLodSkipAIDistance = BUILDER
                                .comment("Distance (in blocks) to skip AI updates entirely (default: 96)")
                                .defineInRange("entityTickLodSkipAIDistance", 96, 32, 256);

                entityTickLodFrozenDistance = BUILDER
                                .comment("Distance (in blocks) to freeze entities completely (default: 160)")
                                .defineInRange("entityTickLodFrozenDistance", 160, 64, 384);

                BUILDER.pop();

                BUILDER.comment("Texture Optimizations")
                                .push("textures");

                enableTextureOptimization = BUILDER
                                .comment("Optimize texture loading and memory usage")
                                .define("enableTextureOptimization", true);

                BUILDER.pop();

                BUILDER.comment("GPU Memory Optimizations")
                                .push("gpu_memory");

                enableGPUMemoryOptimization = BUILDER
                                .comment("Optimize GPU memory usage and reduce VRAM consumption")
                                .define("enableGPUMemoryOptimization", true);

                BUILDER.pop();

                BUILDER.comment("CPU/GPU Load Balancing")
                                .push("load_balancing");

                enableCPULoadBalancing = BUILDER
                                .comment("Transfer some CPU workload to GPU where possible")
                                .define("enableCPULoadBalancing", true);

                BUILDER.pop();

                BUILDER.comment("Xe Architecture Specific Optimizations")
                                .push("xe_architecture");

                enableXeShaderCache = BUILDER
                                .comment("Enable Xe shader compilation cache for faster shader loading")
                                .define("enableXeShaderCache", true);

                enableXeMediaEngine = BUILDER
                                .comment("Enable Xe Media Engine for hardware-accelerated video/image processing")
                                .define("enableXeMediaEngine", true);

                enableXeUnifiedMemory = BUILDER
                                .comment("Enable Xe unified memory management for optimal RAM/VRAM sharing")
                                .define("enableXeUnifiedMemory", true);

                enableXeBatchRenderer = BUILDER
                                .comment("Enable Xe batch renderer for reduced draw calls")
                                .define("enableXeBatchRenderer", true);

                enableXeComputePipeline = BUILDER
                                .comment("Enable Xe compute pipeline for parallel GPU processing")
                                .define("enableXeComputePipeline", true);

                enableXeTextureStreaming = BUILDER
                                .comment("Enable Xe texture streaming optimizer for efficient texture loading")
                                .define("enableXeTextureStreaming", true);

                enableXePerformanceProfiler = BUILDER
                                .comment("Enable Xe performance profiler for detailed frame analysis")
                                .define("enableXePerformanceProfiler", true);

                enableXeChunkGPUAcceleration = BUILDER
                                .comment("Enable Xe GPU compute shader acceleration for chunk mesh building (like Nvidium/CUDA)")
                                .define("enableXeChunkGPUAcceleration", true);

                xeShaderCacheSize = BUILDER
                                .comment("Maximum number of shaders to cache (higher = more memory, faster reloads)")
                                .defineInRange("xeShaderCacheSize", 256, 64, 1024);

                xeMaxBatchSize = BUILDER
                                .comment("Maximum batch size for Xe batch renderer (optimized for EU count)")
                                .defineInRange("xeMaxBatchSize", 512, 128, 2048);

                xeMemoryWarningThreshold = BUILDER
                                .comment("Unified memory warning threshold (0.0-1.0, triggers cleanup at this percentage)")
                                .defineInRange("xeMemoryWarningThreshold", 0.8, 0.5, 0.95);

                xeTextureStreamingBufferSize = BUILDER
                                .comment("Texture streaming buffer size in MB (for Xe unified memory)")
                                .defineInRange("xeTextureStreamingBufferSize", 256, 64, 1024);

                xeChunkGPUWorkGroupSize = BUILDER
                                .comment("GPU compute work group size for chunk building (higher = more parallel, more memory)")
                                .defineInRange("xeChunkGPUWorkGroupSize", 64, 32, 128);

                BUILDER.pop();

                BUILDER.comment("Chunk Preloading & Caching")
                                .push("chunk_preload_cache");

                enableChunkPreloading = BUILDER
                                .comment("Enable chunk preloading in player movement direction")
                                .define("enableChunkPreloading", true);

                enableChunkCaching = BUILDER
                                .comment("Enable chunk data caching to avoid redundant mesh building")
                                .define("enableChunkCaching", true);

                chunkPreloadDistance = BUILDER
                                .comment("Chunk preloading distance ahead of player (8-32 chunks)")
                                .defineInRange("chunkPreloadDistance", 16, 8, 32);

                chunkCacheSize = BUILDER
                                .comment("Maximum number of chunks to cache (50-500)")
                                .defineInRange("chunkCacheSize", 200, 50, 500);

                BUILDER.pop();

                BUILDER.comment("Accelerated Rendering (from AcceleratedRendering mod)")
                                .push("accelerated_rendering");

                enablePersistentBuffer = BUILDER
                                .comment("Enable persistent mapped buffers (OpenGL 4.6+) for zero-copy GPU buffer updates")
                                .define("enablePersistentBuffer", true);

                enableMeshCache = BUILDER
                                .comment("Enable entity mesh hash caching to avoid redundant vertex collection")
                                .define("enableMeshCache", true);

                meshCacheSize = BUILDER
                                .comment("Maximum number of meshes to cache (higher = more memory, fewer re-collections)")
                                .defineInRange("meshCacheSize", 256, 64, 1024);

                enableRenderStateRecovery = BUILDER
                                .comment("Enable GL state recording/restoration for shader pack compatibility")
                                .define("enableRenderStateRecovery", true);

                BUILDER.pop();

                BUILDER.comment("GPU Usage Control")
                                .push("gpu_usage_control");

                enableGPUUsageLimit = BUILDER
                                .comment("Enable GPU usage limit to reduce heat and power consumption")
                                .define("enableGPUUsageLimit", true);

                maxGPUUsagePercent = BUILDER
                                .comment("Maximum GPU usage percentage (50-100, lower = less GPU load)")
                                .defineInRange("maxGPUUsagePercent", 80, 50, 100);

                enableFrameRateLimiter = BUILDER
                                .comment("Enable frame rate limiter to cap FPS and reduce GPU usage")
                                .define("enableFrameRateLimiter", true);

                maxFrameRate = BUILDER
                                .comment("Maximum frame rate limit (30-240, 0 = unlimited)")
                                .defineInRange("maxFrameRate", 120, 0, 240);

                BUILDER.pop();

                BUILDER.comment("Super Resolution Scaling (like OptiFine Render Scale / FSR)")
                                .push("super_resolution");

                enableResolutionScaling = BUILDER
                                .comment("Enable resolution scaling to render at a lower internal resolution and upscale to native")
                                .define("enableResolutionScaling", true);

                renderScalePercent = BUILDER
                                .comment("Render scale percentage (25-200). 50 = half resolution, 100 = native, 200 = 2x supersampling")
                                .defineInRange("renderScalePercent", 100, 25, 200);

                scalingQuality = BUILDER
                                .comment("Scaling quality: 0=Bilinear, 1=FSR EASU (recommended), 2=Lanczos")
                                .defineInRange("scalingQuality", 1, 0, 2);

                BUILDER.pop();

                BUILDER.comment("GPU Accelerated World Generation (like C2ME GPU)")
                                .push("gpu_worldgen");

                enableGPUWorldgen = BUILDER
                                .comment("Enable GPU-accelerated world generation. Offloads noise/biome/heightfield/carver calculations to GPU compute shaders for massive speedups (up to +79% chunks/sec)")
                                .define("enableGPUWorldgen", true);

                gpuGenerationBatchSize = BUILDER
                                .comment("Number of chunks to batch together for GPU generation (4-64). Higher = more parallelism but more VRAM usage")
                                .defineInRange("gpuGenerationBatchSize", 16, 4, 64);

                fallbackToCPU = BUILDER
                                .comment("Automatically fall back to CPU generation if GPU generation fails. Disable to force GPU-only (may cause errors)")
                                .define("fallbackToCPU", true);

                BUILDER.pop();

                BUILDER.comment("GPU Accelerated Chunk Mesh Building & Rendering (GPU-Driven Terrain)")
                                .push("gpu_chunk_rendering");

                enableGPUChunkMeshBuilding = BUILDER
                                .comment("Enable GPU Compute Shader acceleration for chunk mesh building. Replaces CPU-based mesh generation with parallel GPU processing (up to 10x faster)")
                                .define("enableGPUChunkMeshBuilding", true);

                enableGPUChunkRendering = BUILDER
                                .comment("Enable GPU-driven chunk rendering using Indirect Drawing. The GPU handles frustum culling, LOD selection, and draw call generation automatically")
                                .define("enableGPUChunkRendering", true);

                enableGPUChunkFrustumCulling = BUILDER
                                .comment("Enable GPU-based frustum culling for chunks. Invisible chunks are culled on the GPU before rendering, reducing CPU overhead")
                                .define("enableGPUChunkFrustumCulling", true);

                enableMDI = BUILDER
                                .comment("Enable Multi-Draw Indirect (MDI) rendering. Replaces individual draw calls with a single batch submission, reducing CPU overhead by up to 90%")
                                .define("enableMDI", true);

                mdiBatchSize = BUILDER
                                .comment("Maximum number of indirect draw commands per batch (256-8192). Higher = fewer batches but more buffer memory")
                                .defineInRange("mdiBatchSize", 2048, 256, 8192);

                gpuChunkLODHighDistance = BUILDER
                                .comment("Distance (in blocks) for HIGH detail chunk rendering. Closer chunks get full geometry. (default: 32)")
                                .defineInRange("gpuChunkLODHighDistance", 32, 16, 64);

                gpuChunkLODMediumDistance = BUILDER
                                .comment("Distance (in blocks) for MEDIUM detail chunk rendering. Medium distance chunks use simplified geometry. (default: 64)")
                                .defineInRange("gpuChunkLODMediumDistance", 64, 32, 128);

                gpuChunkLODLowDistance = BUILDER
                                .comment("Distance (in blocks) for LOW detail chunk rendering. Far chunks use minimal geometry for maximum performance. (default: 128)")
                                .defineInRange("gpuChunkLODLowDistance", 128, 64, 256);

                gpuChunkLoaderThreads = BUILDER
                                .comment("Number of threads for async GPU chunk loading (2-8). Higher = faster loading but more CPU usage")
                                .defineInRange("gpuChunkLoaderThreads", 4, 2, 8);

                gpuChunkMaxPendingLoads = BUILDER
                                .comment("Maximum number of pending GPU chunk loads before dropping new requests (16-256). Lower = less memory usage, Higher = smoother loading")
                                .defineInRange("gpuChunkMaxPendingLoads", 64, 16, 256);

                BUILDER.pop();

                BUILDER.comment("GPU Accelerated Mod Integration (Distant Horizons & Chunky)")
                                .push("gpu_mod_integration");

                enableGPUChunkyIntegration = BUILDER
                                .comment("Enable GPU-accelerated chunk pregeneration for Chunky mod. Replaces CPU terrain generation with GPU compute shaders for up to 5-10x faster pregeneration")
                                .define("enableGPUChunkyIntegration", true);

                gpuChunkyWorkerThreads = BUILDER
                                .comment("Number of dedicated worker threads for Chunky GPU pregeneration (1-4). Higher = faster batch processing")
                                .defineInRange("gpuChunkyWorkerThreads", 2, 1, 4);

                gpuChunkyBatchSize = BUILDER
                                .comment("Maximum chunks per GPU batch for Chunky (4-32). Higher = more GPU parallelism but more VRAM usage")
                                .defineInRange("gpuChunkyBatchSize", 16, 4, 32);

                enableGPUDistantHorizonsIntegration = BUILDER
                                .comment("Enable GPU-accelerated LOD generation for Distant Horizons mod. Speeds up LOD building and world import by 3-5x using GPU compute shaders")
                                .define("enableGPUDistantHorizonsIntegration", true);

                gpuDHWorkerThreads = BUILDER
                                .comment("Number of dedicated worker threads for Distant Horizons GPU LOD generation (1-4)")
                                .defineInRange("gpuDHWorkerThreads", 2, 1, 4);

                gpuDHDefaultDetailLevel = BUILDER
                                .comment("Default LOD detail level for GPU generation: 0=HIGH (2x downsampling), 1=MEDIUM (4x), 2=LOW (8x). Higher detail = more VRAM")
                                .defineInRange("gpuDHDefaultDetailLevel", 1, 0, 2);

                BUILDER.pop();

                // GPU Generation Throttle Config
                BUILDER.comment("GPU Generation Throttle (Prevents FPS drops during Chunky/DH generation)")
                                .push("gpu_generation_throttle");

                enableGPUThrottle = BUILDER
                                .comment("Enable GPU generation throttle. Prevents Chunky pregeneration and DH LOD generation from impacting gameplay FPS")
                                .define("enableGPUThrottle", true);

                gpuThrottleMaxUsagePercent = BUILDER
                                .comment("Maximum GPU usage percentage before throttling chunk/LOD generation (50-95). Lower = more headroom for rendering")
                                .defineInRange("gpuThrottleMaxUsagePercent", 75, 50, 95);

                gpuThrottleCriticalUsagePercent = BUILDER
                                .comment("Critical GPU usage percentage that pauses all generation completely (80-100)")
                                .defineInRange("gpuThrottleCriticalUsagePercent", 90, 80, 100);

                gpuThrottleMinFPS = BUILDER
                                .comment("Minimum FPS threshold. Generation pauses completely when FPS drops below this (20-60)")
                                .defineInRange("gpuThrottleMinFPS", 45, 20, 60);

                gpuThrottleTargetFPS = BUILDER
                                .comment("Target FPS to maintain during generation. Throttle adjusts to keep FPS near this value (30-120)")
                                .defineInRange("gpuThrottleTargetFPS", 60, 30, 120);

                gpuThrottleMaxBatchSize = BUILDER
                                .comment("Maximum chunks per batch when GPU usage is low (2-32)")
                                .defineInRange("gpuThrottleMaxBatchSize", 8, 2, 32);

                gpuThrottleMinBatchSize = BUILDER
                                .comment("Minimum chunks per batch when GPU usage is moderate (1-8)")
                                .defineInRange("gpuThrottleMinBatchSize", 2, 1, 8);

                gpuThrottleMaxChunksPerWindow = BUILDER
                                .comment("Maximum chunks generated per time window (5-50). Controls overall generation rate")
                                .defineInRange("gpuThrottleMaxChunksPerWindow", 20, 5, 50);

                gpuThrottleWindowSizeMs = BUILDER
                                .comment("Time window in milliseconds for rate limiting (500-5000)")
                                .defineInRange("gpuThrottleWindowSizeMs", 2000, 500, 5000);

                BUILDER.pop();

                // Xe Power Optimizer Config
                BUILDER.comment("Xe Power Optimizer (Dynamic GPU Power Management)")
                                .push("xe_power_optimizer");

                enableXePowerOptimizer = BUILDER
                                .comment("Enable Xe Power Optimizer. Dynamically manages GPU power and workload to prevent FPS drops during chunk generation")
                                .define("enableXePowerOptimizer", true);

                xePowerOptimizerFrameBudgetPercent = BUILDER
                                .comment("GPU work budget as percentage of frame time (5-50). Lower = less GPU work per frame, better FPS")
                                .defineInRange("xePowerOptimizerFrameBudgetPercent", 50, 5, 50);

                xePowerOptimizerGpuUtilizationTarget = BUILDER
                                .comment("Target GPU utilization percentage (30-95). PID controller adjusts workload to maintain this level")
                                .defineInRange("xePowerOptimizerGpuUtilizationTarget", 95, 30, 95);

                xePowerOptimizerTemporalSpreadFrames = BUILDER
                                .comment("Number of frames to spread chunk generation work across (1-16). Higher = smoother but slower loading")
                                .defineInRange("xePowerOptimizerTemporalSpreadFrames", 3, 1, 16);

                xePowerOptimizerWorkStealThreshold = BUILDER
                                .comment("GPU usage threshold (%) to trigger CPU work stealing (50-95). When GPU exceeds this, tasks move to CPU")
                                .defineInRange("xePowerOptimizerWorkStealThreshold", 85, 50, 95);

                xePowerOptimizerWorkReturnThreshold = BUILDER
                                .comment("GPU usage threshold (%) to return work to GPU (30-90). When GPU drops below this, tasks move back to GPU")
                                .defineInRange("xePowerOptimizerWorkReturnThreshold", 60, 30, 90);

                xePowerOptimizerPowerSaveEscalationFrames = BUILDER
                                .comment("Frames of sustained high GPU usage before escalating power saving (10-300). Lower = faster escalation")
                                .defineInRange("xePowerOptimizerPowerSaveEscalationFrames", 60, 10, 300);

                xePowerOptimizerPowerSaveDeescalationFrames = BUILDER
                                .comment("Frames of sustained low GPU usage before reducing power saving (30-600). Higher = more conservative")
                                .defineInRange("xePowerOptimizerPowerSaveDeescalationFrames", 120, 30, 600);

                xePowerOptimizerEmergencyPauseDurationMs = BUILDER
                                .comment("Emergency pause duration in milliseconds when GPU is critically overloaded (1000-5000)")
                                .defineInRange("xePowerOptimizerEmergencyPauseDurationMs", 2000, 1000, 5000);

                xePowerOptimizerMaxChunksPerFrame = BUILDER
                                .comment("Maximum chunks allowed per frame (1-32). Limits per-frame GPU workload")
                                .defineInRange("xePowerOptimizerMaxChunksPerFrame", 32, 1, 32);

                xePowerOptimizerMinBatchThreshold = BUILDER
                                .comment("Minimum chunk count before using batched dispatch (1-8). Higher = more individual dispatches")
                                .defineInRange("xePowerOptimizerMinBatchThreshold", 2, 1, 8);

                BUILDER.pop();

                // CPU-GPU Coordinator Config
                BUILDER.comment("CPU-GPU Coordinator (Dynamic workload balancing between CPU and GPU)")
                                .push("cpu_gpu_coordinator");

                enableCpuGpuCoordinator = BUILDER
                                .comment("Enable CPU-GPU Coordinator. Dynamically balances workload between CPU and GPU to keep both utilization low")
                                .define("enableCpuGpuCoordinator", true);

                targetCpuUsage = BUILDER
                                .comment("Target CPU usage percentage (20-80). Coordinator adjusts workload to maintain this level")
                                .defineInRange("targetCpuUsage", 40, 20, 80);

                targetGpuUsage = BUILDER
                                .comment("Target GPU usage percentage (20-80). Coordinator adjusts workload to maintain this level")
                                .defineInRange("targetGpuUsage", 80, 20, 80);

                cpuLowThreshold = BUILDER
                                .comment("CPU usage low threshold (%). Below this, CPU is considered idle (10-50)")
                                .defineInRange("cpuLowThreshold", 20, 10, 50);

                cpuHighThreshold = BUILDER
                                .comment("CPU usage high threshold (%). Above this, CPU is considered busy (40-90)")
                                .defineInRange("cpuHighThreshold", 60, 40, 90);

                gpuLowThreshold = BUILDER
                                .comment("GPU usage low threshold (%). Below this, GPU is considered idle (10-50)")
                                .defineInRange("gpuLowThreshold", 20, 10, 50);

                gpuHighThreshold = BUILDER
                                .comment("GPU usage high threshold (%). Above this, GPU is considered busy (40-90)")
                                .defineInRange("gpuHighThreshold", 60, 40, 90);

                quietModeThreshold = BUILDER
                                .comment("Threshold (%) for quiet mode. When both CPU and GPU are below this, enter quiet mode (10-40)")
                                .defineInRange("quietModeThreshold", 20, 10, 40);

                quietModeWorkFactor = BUILDER
                                .comment("Work factor in quiet mode (0.1-0.5). Lower = less overall workload")
                                .defineInRange("quietModeWorkFactor", 0.3, 0.1, 0.5);

                overloadThreshold = BUILDER
                                .comment("Threshold (%) for overload mode. When both CPU and GPU exceed this, enter overload protection (60-95)")
                                .defineInRange("overloadThreshold", 80, 60, 95);

                BUILDER.pop();

                BUILDER.comment("Shader Pack Loader (Custom shader pack support like Iris)")
                                .push("shader_packs");

                enableShaderPacks = BUILDER
                                .comment("Enable custom shader pack loading. When disabled, uses Minecraft default rendering")
                                .define("enableShaderPacks", false);

                shaderPackName = BUILDER
                                .comment("Name of the active shader pack (zip filename without extension)")
                                .define("shaderPackName", "");

                shadowMapResolution = BUILDER
                                .comment("Shadow map resolution in pixels (1024-4096, higher = sharper shadows but more GPU load)")
                                .defineInRange("shadowMapResolution", 2048, 1024, 4096);

                shaderRenderScale = BUILDER
                                .comment("Render scale for shader rendering (0.5-2.0, 1.0 = native resolution)")
                                .defineInRange("shaderRenderScale", 1.0, 0.5, 2.0);

                shaderQuality = BUILDER
                                .comment("Shader quality level (0=Low, 1=Medium, 2=High, 3=Ultra)")
                                .defineInRange("shaderQuality", 2, 0, 3);

                enableShadowRendering = BUILDER
                                .comment("Enable shadow map rendering for shader packs that support it")
                                .define("enableShadowRendering", true);

                enableVolumetricClouds = BUILDER
                                .comment("Enable volumetric clouds (requires shader pack support)")
                                .define("enableVolumetricClouds", true);

                BUILDER.pop();

                BUILDER.pop();

                SPEC = BUILDER.build();
        }
}
