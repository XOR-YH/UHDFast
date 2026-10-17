package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

public class IntelXeDetector {

    private static boolean isIntelGPU = false;
    private static boolean isXeArchitecture = false;
    private static int euCount = 0;
    private static XeArchitectureType architectureType = XeArchitectureType.UNKNOWN;
    private static boolean initialized = false;
    private static String gpuVendorString = "";
    private static String gpuRendererString = "";
    private static String glVersion = "";
    private static String glslVersion = "";
    private static int maxTextureSize = 0;
    private static int maxRenderBufferSize = 0;
    private static boolean supportsComputeShaders = false;
    private static boolean supportsInstancedRendering = false;
    private static boolean supportsTextureCompression = false;
    private static boolean supportsUnifiedMemory = false;
    private static boolean supportsMediaEngine = false;
    private static int maxComputeWorkGroupSize = 0;
    private static int maxComputeWorkGroupCount = 0;
    private static double driverVersion = 0.0;

    public enum XeArchitectureType {
        GEN12_LP,
        GEN12_HP,
        XE_HPG,
        XE_HPC,
        XE2_LPG,
        XE2_HPG,
        UNKNOWN
    }

    public static void detect() {
        if (initialized) {
            return;
        }

        try {
            gpuVendorString = GL11.glGetString(GL11.GL_VENDOR);
            gpuRendererString = GL11.glGetString(GL11.GL_RENDERER);
            glVersion = GL11.glGetString(GL11.GL_VERSION);
            glslVersion = GL11.glGetString(GL30.GL_SHADING_LANGUAGE_VERSION);

            UHDFast.LOGGER.info("========================================");
            UHDFast.LOGGER.info("  GPU Detection Report");
            UHDFast.LOGGER.info("========================================");
            UHDFast.LOGGER.info("  Vendor: {}", gpuVendorString);
            UHDFast.LOGGER.info("  Renderer: {}", gpuRendererString);
            UHDFast.LOGGER.info("  GL Version: {}", glVersion);
            UHDFast.LOGGER.info("  GLSL Version: {}", glslVersion);

            isIntelGPU = gpuVendorString.toLowerCase().contains("intel");

            if (isIntelGPU) {
                analyzeXeArchitecture(gpuRendererString);
                queryGPUCapabilities();
                detectDriverVersion();
                detectUnifiedMemorySupport();
                detectMediaEngineSupport();

                UHDFast.LOGGER.info("  Intel GPU detected - Xe Architecture: {}", isXeArchitecture);
                UHDFast.LOGGER.info("  Architecture Type: {}", architectureType);
                UHDFast.LOGGER.info("  Estimated EU Count: {}", euCount);
                UHDFast.LOGGER.info("  Driver Version: {}", String.format("%.2f", driverVersion));
                UHDFast.LOGGER.info("  Unified Memory: {}", supportsUnifiedMemory);
                UHDFast.LOGGER.info("  Media Engine: {}", supportsMediaEngine);
                UHDFast.LOGGER.info("========================================");
            } else {
                UHDFast.LOGGER.info("  Non-Intel GPU detected, Xe optimizations disabled");
                UHDFast.LOGGER.info("========================================");
            }
        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to detect GPU: {}", e.getMessage());
        }

        initialized = true;
    }

    private static void analyzeXeArchitecture(String renderer) {
        String rendererLower = renderer.toLowerCase();

        if (rendererLower.contains("arc") || rendererLower.contains("xe hpg")) {
            isXeArchitecture = true;
            architectureType = XeArchitectureType.XE_HPG;

            if (rendererLower.contains("a770") || rendererLower.contains("770m")) {
                euCount = 512;
            } else if (rendererLower.contains("a750") || rendererLower.contains("750m")) {
                euCount = 384;
            } else if (rendererLower.contains("a580") || rendererLower.contains("580m")) {
                euCount = 256;
            } else if (rendererLower.contains("a380") || rendererLower.contains("380m")) {
                euCount = 128;
            } else if (rendererLower.contains("a310") || rendererLower.contains("310m")) {
                euCount = 96;
            } else {
                euCount = 256;
            }
        } else if (rendererLower.contains("iris xe") || rendererLower.contains("xe graphics")) {
            isXeArchitecture = true;
            architectureType = XeArchitectureType.GEN12_LP;

            if (rendererLower.contains("96") || rendererLower.contains("max")) {
                euCount = 96;
            } else if (rendererLower.contains("80") || rendererLower.contains("85")) {
                euCount = 80;
            } else if (rendererLower.contains("730")) {
                euCount = 80;
            } else if (rendererLower.contains("655")) {
                euCount = 80;
            } else if (rendererLower.contains("630")) {
                euCount = 24;
            } else if (rendererLower.contains("620")) {
                euCount = 24;
            } else {
                euCount = 80;
            }
        } else if (rendererLower.contains("uhd graphics") || rendererLower.contains("uhd")) {
            isXeArchitecture = true;
            architectureType = XeArchitectureType.GEN12_LP;

            if (rendererLower.contains("770") || rendererLower.contains("750")) {
                euCount = 96;
            } else if (rendererLower.contains("730") || rendererLower.contains("655")) {
                euCount = 80;
            } else if (rendererLower.contains("640") || rendererLower.contains("635")) {
                euCount = 48;
            } else if (rendererLower.contains("630") || rendererLower.contains("620")) {
                euCount = 24;
            } else if (rendererLower.contains("610") || rendererLower.contains("605")
                    || rendererLower.contains("600")) {
                euCount = 32;
            } else {
                euCount = 48;
            }
        } else if (rendererLower.contains("hd graphics")) {
            isXeArchitecture = false;
            architectureType = XeArchitectureType.UNKNOWN;
            euCount = 0;
        } else if (rendererLower.contains("intel")) {
            isXeArchitecture = true;
            architectureType = XeArchitectureType.GEN12_LP;
            euCount = 48;
        }
    }

    private static void queryGPUCapabilities() {
        try {
            maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            maxRenderBufferSize = GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE);

            // 获取 OpenGL 版本
            int glMajorVersion = GL32.glGetInteger(GL32.GL_MAJOR_VERSION);
            int glMinorVersion = GL32.glGetInteger(GL32.GL_MINOR_VERSION);
            int glVersionInt = glMajorVersion * 10 + glMinorVersion;

            String extensions = GL11.glGetString(GL11.GL_EXTENSIONS);
            if (extensions != null) {
                // Compute shader: OpenGL 4.3+ 核心特性，或检查扩展
                supportsComputeShaders = glVersionInt >= 43 ||
                        extensions.contains("GL_ARB_compute_shader") ||
                        extensions.contains("GL_ES_VERSION_3_1");

                // Instanced rendering: OpenGL 3.1+ 核心特性，或检查扩展
                supportsInstancedRendering = glVersionInt >= 31 ||
                        extensions.contains("GL_ARB_draw_instanced") ||
                        extensions.contains("GL_ARB_instanced_arrays") ||
                        extensions.contains("GL_ES_VERSION_3_0");

                // Texture compression: 检查常用扩展
                supportsTextureCompression = extensions.contains("GL_ARB_texture_compression") ||
                        extensions.contains("GL_EXT_texture_compression_s3tc") ||
                        extensions.contains("GL_EXT_texture_compression_bptc");
            } else {
                // 没有扩展字符串时，根据 OpenGL 版本推断
                supportsComputeShaders = glVersionInt >= 43;
                supportsInstancedRendering = glVersionInt >= 31;
                supportsTextureCompression = glVersionInt >= 30;
            }

            UHDFast.LOGGER.info("  GPU Capabilities:");
            UHDFast.LOGGER.info("    OpenGL Version: {}.{}", glMajorVersion, glMinorVersion);
            UHDFast.LOGGER.info("    Max Texture Size: {}x{}", maxTextureSize, maxTextureSize);
            UHDFast.LOGGER.info("    Max Render Buffer: {}x{}", maxRenderBufferSize, maxRenderBufferSize);
            UHDFast.LOGGER.info("    Compute Shaders: {} (GL {}.{}+)", supportsComputeShaders, 4, 3);
            UHDFast.LOGGER.info("    Instanced Rendering: {} (GL {}.{}+)", supportsInstancedRendering, 3, 1);
            UHDFast.LOGGER.info("    Texture Compression: {}", supportsTextureCompression);
        } catch (Exception e) {
            UHDFast.LOGGER.warn("  Failed to query GPU capabilities: {}", e.getMessage());
        }
    }

    private static void detectDriverVersion() {
        try {
            String version = GL11.glGetString(GL11.GL_VERSION);
            if (version != null && version.contains("Intel")) {
                String[] parts = version.split(" ");
                for (String part : parts) {
                    if (part.matches("\\d+\\.\\d+")) {
                        driverVersion = Double.parseDouble(part);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            UHDFast.LOGGER.warn("  Failed to detect driver version: {}", e.getMessage());
        }
    }

    private static void detectUnifiedMemorySupport() {
        supportsUnifiedMemory = isXeArchitecture && architectureType == XeArchitectureType.GEN12_LP;
    }

    private static void detectMediaEngineSupport() {
        switch (architectureType) {
            case GEN12_LP:
            case GEN12_HP:
            case XE_HPG:
            case XE2_LPG:
            case XE2_HPG:
                supportsMediaEngine = true;
                break;
            default:
                supportsMediaEngine = false;
        }
    }

    public static boolean isIntelGPU() {
        return isIntelGPU;
    }

    public static boolean isXeArchitecture() {
        return isXeArchitecture;
    }

    public static int getEUCount() {
        return euCount;
    }

    public static XeArchitectureType getArchitectureType() {
        return architectureType;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static String getGPUVendorString() {
        return gpuVendorString;
    }

    public static String getGPURendererString() {
        return gpuRendererString;
    }

    public static String getGLVersion() {
        return glVersion;
    }

    public static String getGLSLVersion() {
        return glslVersion;
    }

    public static int getMaxTextureSize() {
        return maxTextureSize;
    }

    public static int getMaxRenderBufferSize() {
        return maxRenderBufferSize;
    }

    public static boolean supportsComputeShaders() {
        return supportsComputeShaders;
    }

    public static boolean supportsInstancedRendering() {
        return supportsInstancedRendering;
    }

    public static boolean supportsTextureCompression() {
        return supportsTextureCompression;
    }

    public static boolean supportsUnifiedMemory() {
        return supportsUnifiedMemory;
    }

    public static boolean supportsMediaEngine() {
        return supportsMediaEngine;
    }

    public static int getMaxComputeWorkGroupSize() {
        return maxComputeWorkGroupSize;
    }

    public static int getMaxComputeWorkGroupCount() {
        return maxComputeWorkGroupCount;
    }

    public static double getDriverVersion() {
        return driverVersion;
    }

    public static double getOptimizationPotential() {
        if (!isXeArchitecture) {
            return 0.5;
        }

        double basePotential = 0.5;

        switch (architectureType) {
            case GEN12_LP:
                basePotential = 0.85;
                break;
            case GEN12_HP:
                basePotential = 0.75;
                break;
            case XE_HPG:
                basePotential = 0.65;
                break;
            case XE_HPC:
                basePotential = 0.60;
                break;
            case XE2_LPG:
            case XE2_HPG:
                basePotential = 0.70;
                break;
            default:
                basePotential = 0.50;
        }

        if (supportsUnifiedMemory) {
            basePotential += 0.05;
        }
        if (supportsMediaEngine) {
            basePotential += 0.05;
        }
        if (driverVersion >= 31.0) {
            basePotential += 0.05;
        }

        return Math.min(1.0, basePotential);
    }

    public static String getArchitectureDescription() {
        switch (architectureType) {
            case GEN12_LP:
                return "Xe-LP (Low Power) - Integrated Graphics";
            case GEN12_HP:
                return "Xe-HP (High Performance)";
            case XE_HPG:
                return "Xe-HPG (High Performance Gaming) - Arc";
            case XE_HPC:
                return "Xe-HPC (High Performance Compute)";
            case XE2_LPG:
                return "Xe2-LPG (Next Gen Low Power)";
            case XE2_HPG:
                return "Xe2-HPG (Next Gen Gaming)";
            default:
                return "Unknown Intel Architecture";
        }
    }
}
