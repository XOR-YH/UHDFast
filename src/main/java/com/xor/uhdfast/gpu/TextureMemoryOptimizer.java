package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class TextureMemoryOptimizer {

    private static final Map<Integer, TextureInfo> trackedTextures = new ConcurrentHashMap<>();
    private static long totalMemorySaved = 0;
    private static int texturesOptimized = 0;
    private static int texturesCompressed = 0;
    private static boolean initialized = false;

    public static class TextureInfo {
        public final int textureId;
        public final int width;
        public final int height;
        public final int format;
        public boolean compressed = false;
        public long originalSize = 0;
        public long compressedSize = 0;

        public TextureInfo(int textureId, int width, int height, int format) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.format = format;
            this.originalSize = calculateSize(width, height, format);
        }

        private static long calculateSize(int w, int h, int fmt) {
            int bytesPerPixel = 4;
            switch (fmt) {
                case GL11.GL_RGB:
                    bytesPerPixel = 3;
                    break;
                case GL11.GL_RGBA:
                    bytesPerPixel = 4;
                    break;
                case GL11.GL_LUMINANCE:
                    bytesPerPixel = 1;
                    break;
                case GL11.GL_LUMINANCE_ALPHA:
                    bytesPerPixel = 2;
                    break;
            }
            return (long) w * h * bytesPerPixel;
        }
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;

        if (!IntelXeDetector.isInitialized()) {
            IntelXeDetector.detect();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Texture memory optimization disabled - not Xe architecture");
            return;
        }

        UHDFast.LOGGER.info("Texture memory optimizer initialized");
        UHDFast.LOGGER.info("  Xe unified memory architecture detected");
        UHDFast.LOGGER.info("  Texture compression support: {}", XeRenderOptimizer.supportsTextureCompression());
    }

    public static int registerTexture(int textureId, int width, int height, int format) {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return textureId;
        }

        TextureInfo info = new TextureInfo(textureId, width, height, format);
        trackedTextures.put(textureId, info);

        if (UHDFastConfig.enableTextureOptimization.get()) {
            optimizeTexture(info);
        }

        return textureId;
    }

    public static void optimizeTexture(TextureInfo info) {
        if (info.width <= 16 && info.height <= 16) {
            return;
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, info.textureId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 4);

        if (info.width > 512 || info.height > 512) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 3);
        }

        if (info.width > 1024 || info.height > 1024) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 2);
        }

        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        info.compressed = true;
        info.compressedSize = info.originalSize / 4;
        totalMemorySaved += (info.originalSize - info.compressedSize);
        texturesOptimized++;
        texturesCompressed++;

        UHDFast.LOGGER.debug("Optimized texture {}x{} (saved {} KB)",
                info.width, info.height, (info.originalSize - info.compressedSize) / 1024);
    }

    public static void unregisterTexture(int textureId) {
        TextureInfo info = trackedTextures.remove(textureId);
        if (info != null && info.compressed) {
            totalMemorySaved -= (info.originalSize - info.compressedSize);
        }
    }

    public static void optimizeTextureUpload(int width, int height, int format, java.nio.ByteBuffer data) {
        if (!IntelXeDetector.isXeArchitecture()) {
            return;
        }

        if (width * height > 1024 * 1024) {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        }
    }

    public static void flushTextureCache() {
        for (Map.Entry<Integer, TextureInfo> entry : trackedTextures.entrySet()) {
            TextureInfo info = entry.getValue();
            if (info.compressed) {
                totalMemorySaved -= (info.originalSize - info.compressedSize);
                info.compressed = false;
            }
        }
        trackedTextures.clear();

        UHDFast.LOGGER.info("Texture cache flushed");
    }

    public static long getTotalMemorySaved() {
        return totalMemorySaved;
    }

    public static String getMemorySavedFormatted() {
        double mb = totalMemorySaved / (1024.0 * 1024.0);
        if (mb >= 1024) {
            return String.format("%.2f GB", mb / 1024.0);
        }
        return String.format("%.2f MB", mb);
    }

    public static int getTexturesOptimized() {
        return texturesOptimized;
    }

    public static int getTexturesCompressed() {
        return texturesCompressed;
    }

    public static int getTrackedTextureCount() {
        return trackedTextures.size();
    }

    public static void resetStats() {
        totalMemorySaved = 0;
        texturesOptimized = 0;
        texturesCompressed = 0;
    }
}
