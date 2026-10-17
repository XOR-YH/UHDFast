package com.xor.uhdfast.compat;

import com.xor.uhdfast.UHDFast;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public class ModCompatibility {
    private static volatile boolean initialized = false;

    // Rendering replacement mods (replace vanilla chunk rendering)
    private static boolean hasSodium = false;
    private static boolean hasEmbeddium = false;
    private static boolean hasRubidium = false;
    private static boolean hasXenon = false;
    private static boolean hasCullLeaves = false;
    private static boolean hasEntityCulling = false;

    // Shader mods (may conflict with GPU pipelines)
    private static boolean hasIris = false;
    private static boolean hasOptiFine = false;
    private static boolean hasOculus = false;

    // Chunk/lighting optimization mods (compatible)
    private static boolean hasStarlight = false;
    private static boolean hasFerriteCore = false;
    private static boolean hasImmediatelyFast = false;
    private static boolean hasModernFix = false;

    // GPU chunk loading integration mods (can leverage GPU acceleration)
    private static boolean hasDistantHorizons = false;
    private static boolean hasDistantHorizonsAPI = false;
    private static boolean hasChunky = false;

    // Compatibility flags (auto-set based on detected mods)
    /**
     * True when a rendering replacement mod is detected (Sodium, Embeddium, etc.)
     */
    private static boolean hasRenderingReplacement = false;
    /** True when GPU chunk features should be disabled */
    private static boolean disableGPUChunkMesh = false;
    /** True when GPU indirect rendering should be disabled */
    private static boolean disableGPUChunkRendering = false;
    /** True when GPU chunk loading should be disabled */
    private static boolean disableGPUChunkLoader = false;

    /** 检测到 Iris/Oculus 光影 mod 时为 true，用于禁用内置光影加载器 */
    public static boolean irisOrOculusDetected = false;

    public static synchronized void init() {
        if (initialized)
            return;
        initialized = true;

        ModList modList = ModList.get();
        if (modList == null) {
            UHDFast.LOGGER.warn("ModList not available yet");
            return;
        }

        // Detect rendering replacement mods
        hasSodium = isModLoaded(modList, "sodium");
        hasEmbeddium = isModLoaded(modList, "embeddium");
        hasRubidium = isModLoaded(modList, "rubidium");
        hasXenon = isModLoaded(modList, "xenon");

        // Detect shader mods
        hasIris = isModLoaded(modList, "iris");
        hasOptiFine = isOptiFineLoaded();
        hasOculus = isModLoaded(modList, "oculus");

        // Detect compatible optimization mods
        hasStarlight = isModLoaded(modList, "starlight");
        hasFerriteCore = isModLoaded(modList, "ferritecore");
        hasImmediatelyFast = isModLoaded(modList, "immediatelyfast");
        hasModernFix = isModLoaded(modList, "modernfix");

        // Detect other useful mods
        hasCullLeaves = isModLoaded(modList, "cullleaves");
        hasEntityCulling = isModLoaded(modList, "entityculling");

        // Detect GPU chunk loading integration mods
        hasDistantHorizons = isModLoaded(modList, "distanthorizons");
        hasDistantHorizonsAPI = isModLoaded(modList, "distanthorizonsapi") || hasDistantHorizons;
        hasChunky = isModLoaded(modList, "chunky");

        // === Set compatibility flags ===

        // If any rendering replacement mod is present, disable ALL GPU chunk features
        // Sodium/Embeddium/etc. completely replace the chunk rendering pipeline
        hasRenderingReplacement = hasSodium || hasEmbeddium || hasRubidium || hasXenon;

        if (hasRenderingReplacement) {
            disableGPUChunkMesh = true;
            disableGPUChunkRendering = true;
            // GPU区块加载器（计算着色器）与渲染替换mod可共存：
            // 计算着色器在GPU上构建块数据→顶点/索引网格，
            // 这与Sodium的渲染管线是独立的两个阶段。
            // Sodium负责渲染，Xe GPU负责计算，互不冲突。
            // 仅在钠/镧/铷/氙等渲染替换mod存在时还需额外检查
            // 是否明确禁用GPU计算加载
            // disableGPUChunkLoader = true; // 注释掉：允许GPU计算着色器运行
        }

        // Shader mods may conflict with GPU pipelines - disable GPU rendering
        if (hasIris || hasOptiFine || hasOculus) {
            disableGPUChunkRendering = true;
            // But keep chunk mesh building unless a rendering replacement is also present
        }

        // 使用 FMLLoader 检测 Iris/Oculus 光影 mod（用于禁用内置光影加载器）
        try {
            for (var modInfo : FMLLoader.getLoadingModList().getMods()) {
                String modId = modInfo.getModId();
                if ("iris".equals(modId) || "oculus".equals(modId)) {
                    irisOrOculusDetected = true;
                    break;
                }
            }
        } catch (Exception e) {
            // 回退到 ModList 检测结果
            irisOrOculusDetected = hasIris || hasOculus;
        }

        if (irisOrOculusDetected) {
            UHDFast.LOGGER.info("检测到 Iris/Oculus 光影 mod，已禁用内置光影加载器");
        }

        // Log detection results
        StringBuilder sb = new StringBuilder("Mod compatibility detected:");
        if (hasSodium)
            sb.append(" [Sodium]");
        if (hasEmbeddium)
            sb.append(" [Embeddium]");
        if (hasRubidium)
            sb.append(" [Rubidium]");
        if (hasXenon)
            sb.append(" [Xenon]");
        if (hasIris)
            sb.append(" [Iris]");
        if (hasOptiFine)
            sb.append(" [OptiFine]");
        if (hasOculus)
            sb.append(" [Oculus]");
        if (hasStarlight)
            sb.append(" [Starlight]");
        if (hasFerriteCore)
            sb.append(" [FerriteCore]");
        if (hasImmediatelyFast)
            sb.append(" [ImmediatelyFast]");
        if (hasModernFix)
            sb.append(" [ModernFix]");
        if (hasCullLeaves)
            sb.append(" [CullLeaves]");
        if (hasEntityCulling)
            sb.append(" [EntityCulling]");
        if (hasDistantHorizons)
            sb.append(" [DistantHorizons]");
        if (hasChunky)
            sb.append(" [Chunky]");
        if (sb.toString().equals("Mod compatibility detected:")) {
            sb.append(" None");
        }
        UHDFast.LOGGER.info(sb.toString());

        if (hasOptiFine) {
            UHDFast.LOGGER.warn("OptiFine detected! UHDFast may have reduced compatibility.");
            UHDFast.LOGGER.warn("For best performance, consider using Sodium + Iris instead.");
        }

        if (hasRenderingReplacement) {
            String modName = hasSodium ? "Sodium" : hasEmbeddium ? "Embeddium" : hasRubidium ? "Rubidium" : "Xenon";
            UHDFast.LOGGER.info("{} detected - GPU chunk mesh/rendering disabled to avoid conflicts", modName);
            UHDFast.LOGGER.info("GPU chunk loader (compute shader) remains enabled for Xe GPU acceleration");
            UHDFast.LOGGER.info("UHDFast will still provide entity optimization, LOD, and other non-rendering features");
        }

        // Log GPU acceleration opportunities
        if (hasDistantHorizonsAPI) {
            UHDFast.LOGGER.info("Distant Horizons detected - GPU-accelerated LOD generation will be enabled");
        }
        if (hasChunky) {
            UHDFast.LOGGER.info("Chunky detected - GPU-accelerated chunk pregeneration will be enabled");
        }
    }

    // === Detection queries ===

    public static boolean hasSodium() {
        return hasSodium;
    }

    public static boolean hasIris() {
        return hasIris;
    }

    public static boolean hasOptiFine() {
        return hasOptiFine;
    }

    public static boolean hasEmbeddium() {
        return hasEmbeddium;
    }

    public static boolean hasRubidium() {
        return hasRubidium;
    }

    public static boolean hasStarlight() {
        return hasStarlight;
    }

    public static boolean hasFerriteCore() {
        return hasFerriteCore;
    }

    public static boolean hasXenon() {
        return hasXenon;
    }

    public static boolean hasImmediatelyFast() {
        return hasImmediatelyFast;
    }

    public static boolean hasModernFix() {
        return hasModernFix;
    }

    public static boolean hasDistantHorizons() {
        return hasDistantHorizons;
    }

    public static boolean hasDistantHorizonsAPI() {
        return hasDistantHorizonsAPI;
    }

    public static boolean hasChunky() {
        return hasChunky;
    }

    /**
     * True when GPU chunk loading integration mods are present (Distant Horizons,
     * Chunky)
     */
    public static boolean hasGPUChunkLoadingIntegration() {
        return hasDistantHorizonsAPI || hasChunky;
    }

    public static boolean hasPerformanceMod() {
        return hasSodium || hasEmbeddium || hasRubidium || hasOptiFine || hasXenon;
    }

    // === Compatibility flags ===

    /**
     * True when a rendering replacement mod (Sodium, Embeddium, etc.) is present
     */
    public static boolean hasRenderingReplacement() {
        return hasRenderingReplacement;
    }

    /** Returns true if GPU chunk mesh building should be disabled */
    public static boolean shouldDisableGPUChunkMesh() {
        return disableGPUChunkMesh;
    }

    /** Returns true if GPU indirect rendering should be disabled */
    public static boolean shouldDisableGPUChunkRendering() {
        return disableGPUChunkRendering;
    }

    /** Returns true if GPU chunk loading should be disabled */
    public static boolean shouldDisableGPUChunkLoader() {
        return disableGPUChunkLoader;
    }

    /** 检测到 Iris/Oculus 时返回 true，用于禁用内置光影加载器 */
    public static boolean shouldDisableBuiltinShaders() {
        return irisOrOculusDetected;
    }

    // === Helpers ===

    private static boolean isModLoaded(ModList modList, String modId) {
        try {
            return modList.isLoaded(modId);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isOptiFineLoaded() {
        try {
            Class.forName("optifine.Installer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
