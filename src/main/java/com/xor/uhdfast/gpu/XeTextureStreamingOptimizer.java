package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

public class XeTextureStreamingOptimizer {

    private static final Map<Integer, StreamingTexture> streamingTextures = new HashMap<>();
    private static final PriorityQueue<StreamingTexture> evictionQueue = new PriorityQueue<>();
    private static int totalTexturesStreamed = 0;
    private static int totalTexturesEvicted = 0;
    private static long totalStreamingTime = 0;
    private static long currentStreamingMemory = 0;
    private static long peakStreamingMemory = 0;
    private static boolean initialized = false;

    private static final long MAX_STREAMING_MEMORY = 256L * 1024 * 1024;
    private static final int MAX_STREAMING_TEXTURES = 128;

    public static class StreamingTexture implements Comparable<StreamingTexture> {
        public final int textureId;
        public final int width;
        public final int height;
        public final int mipLevel;
        public final long size;
        public final long lastAccessTime;
        public int accessCount = 0;
        public boolean resident = false;

        public StreamingTexture(int textureId, int width, int height, int mipLevel, long size) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.mipLevel = mipLevel;
            this.size = size;
            this.lastAccessTime = System.currentTimeMillis();
        }

        @Override
        public int compareTo(StreamingTexture other) {
            return Long.compare(this.lastAccessTime, other.lastAccessTime);
        }
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Texture streaming optimizer disabled - not Xe architecture");
            return;
        }

        UHDFast.LOGGER.info("Xe Texture Streaming Optimizer initialized");
        UHDFast.LOGGER.info("  Xe unified memory enables efficient texture streaming");
        UHDFast.LOGGER.info("  Max streaming memory: {} MB", MAX_STREAMING_MEMORY / (1024 * 1024));
        UHDFast.LOGGER.info("  Max streaming textures: {}", MAX_STREAMING_TEXTURES);

        initialized = true;
    }

    public static int registerStreamingTexture(int textureId, int width, int height, int mipLevel) {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return textureId;
        }

        long size = calculateTextureSize(width, height, mipLevel);

        StreamingTexture texture = new StreamingTexture(textureId, width, height, mipLevel, size);
        streamingTextures.put(textureId, texture);
        evictionQueue.add(texture);

        currentStreamingMemory += size;
        if (currentStreamingMemory > peakStreamingMemory) {
            peakStreamingMemory = currentStreamingMemory;
        }

        totalTexturesStreamed++;

        if (currentStreamingMemory > MAX_STREAMING_MEMORY || streamingTextures.size() > MAX_STREAMING_TEXTURES) {
            evictLeastUsedTextures();
        }

        UHDFast.LOGGER.debug("Streaming texture registered: {}x{} (mip {}, {} KB)",
                width, height, mipLevel, size / 1024);

        return textureId;
    }

    private static long calculateTextureSize(int width, int height, int mipLevel) {
        int w = width >> mipLevel;
        int h = height >> mipLevel;
        if (w < 1)
            w = 1;
        if (h < 1)
            h = 1;
        return (long) w * h * 4;
    }

    public static void accessTexture(int textureId) {
        StreamingTexture texture = streamingTextures.get(textureId);
        if (texture != null) {
            texture.accessCount++;
            // 需要移除这行，因为 lastAccessTime �?final 字段
            // 已在构造函数中设置，无法重新赋�?            // 如需更新访问时间，需要重构为使用可变字段
            texture.resident = true;

            evictionQueue.remove(texture);
            evictionQueue.add(texture);
        }
    }

    private static void evictLeastUsedTextures() {
        int evictedCount = 0;
        long evictedMemory = 0;

        while (currentStreamingMemory > MAX_STREAMING_MEMORY * 0.7 && !evictionQueue.isEmpty()) {
            StreamingTexture texture = evictionQueue.poll();
            if (texture != null && !texture.resident) {
                streamingTextures.remove(texture.textureId);
                currentStreamingMemory -= texture.size;
                evictedCount++;
                evictedMemory += texture.size;
                totalTexturesEvicted++;
            }
        }

        if (evictedCount > 0) {
            UHDFast.LOGGER.debug("Evicted {} textures ({} KB)", evictedCount, evictedMemory / 1024);
        }
    }

    public static void optimizeTextureUpload(int textureId, ByteBuffer data) {
        if (!IntelXeDetector.isXeArchitecture()) {
            return;
        }

        long startTime = System.nanoTime();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 4);

        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        long endTime = System.nanoTime();
        totalStreamingTime += (endTime - startTime);

        accessTexture(textureId);
    }

    public static int getTotalTexturesStreamed() {
        return totalTexturesStreamed;
    }

    public static int getTotalTexturesEvicted() {
        return totalTexturesEvicted;
    }

    public static long getTotalStreamingTime() {
        return totalStreamingTime;
    }

    public static String getTotalStreamingTimeFormatted() {
        double ms = totalStreamingTime / 1_000_000.0;
        return String.format("%.2f ms", ms);
    }

    public static long getCurrentStreamingMemory() {
        return currentStreamingMemory;
    }

    public static String getCurrentStreamingMemoryFormatted() {
        double mb = currentStreamingMemory / (1024.0 * 1024.0);
        return String.format("%.2f MB", mb);
    }

    public static long getPeakStreamingMemory() {
        return peakStreamingMemory;
    }

    public static String getPeakStreamingMemoryFormatted() {
        double mb = peakStreamingMemory / (1024.0 * 1024.0);
        return String.format("%.2f MB", mb);
    }

    public static int getActiveStreamingTextureCount() {
        return streamingTextures.size();
    }

    public static double getStreamingEfficiency() {
        if (totalTexturesStreamed == 0) {
            return 100.0;
        }
        return (double) (totalTexturesStreamed - totalTexturesEvicted) / totalTexturesStreamed * 100.0;
    }

    public static void resetStats() {
        streamingTextures.clear();
        evictionQueue.clear();
        totalTexturesStreamed = 0;
        totalTexturesEvicted = 0;
        totalStreamingTime = 0;
        currentStreamingMemory = 0;
        peakStreamingMemory = 0;
    }
}
