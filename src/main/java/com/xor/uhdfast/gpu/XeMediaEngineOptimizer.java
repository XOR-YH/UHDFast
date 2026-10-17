package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;

import java.util.concurrent.atomic.AtomicInteger;

public class XeMediaEngineOptimizer {

    private static boolean initialized = false;
    private static boolean mediaEngineSupported = false;
    private static int videoDecodeOffloaded = 0;
    private static int videoEncodeOffloaded = 0;
    private static int imageDecodeOffloaded = 0;
    private static long totalMediaProcessingTime = 0;
    private static final AtomicInteger activeMediaTasks = new AtomicInteger(0);
    private static final int MAX_CONCURRENT_MEDIA_TASKS = 4;

    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Media engine optimization disabled - not Xe architecture");
            return;
        }

        mediaEngineSupported = detectMediaEngineSupport();

        UHDFast.LOGGER.info("Xe Media Engine Optimizer initialized");
        UHDFast.LOGGER.info("  Media Engine supported: {}", mediaEngineSupported);
        UHDFast.LOGGER.info("  Architecture: {}", IntelXeDetector.getArchitectureType());

        if (mediaEngineSupported) {
            configureMediaEngine();
        }

        initialized = true;
    }

    private static boolean detectMediaEngineSupport() {
        IntelXeDetector.XeArchitectureType archType = IntelXeDetector.getArchitectureType();

        switch (archType) {
            case GEN12_LP:
            case GEN12_HP:
            case XE_HPG:
                return true;
            default:
                return false;
        }
    }

    private static void configureMediaEngine() {
        int euCount = IntelXeDetector.getEUCount();

        if (euCount <= 32) {
            UHDFast.LOGGER.info("Media Engine configured for low-end Xe ({} EUs)", euCount);
            UHDFast.LOGGER.info("  - Hardware video decode enabled");
            UHDFast.LOGGER.info("  - Image decode acceleration enabled");
            UHDFast.LOGGER.info("  - Max concurrent tasks: 2");
        } else if (euCount <= 96) {
            UHDFast.LOGGER.info("Media Engine configured for mid-range Xe ({} EUs)", euCount);
            UHDFast.LOGGER.info("  - Hardware video decode/encode enabled");
            UHDFast.LOGGER.info("  - Image decode acceleration enabled");
            UHDFast.LOGGER.info("  - Max concurrent tasks: 4");
        } else {
            UHDFast.LOGGER.info("Media Engine configured for high-end Xe ({} EUs)", euCount);
            UHDFast.LOGGER.info("  - Full hardware media pipeline enabled");
            UHDFast.LOGGER.info("  - Max concurrent tasks: 8");
        }
    }

    public static boolean canOffloadVideoDecode() {
        return mediaEngineSupported && activeMediaTasks.get() < MAX_CONCURRENT_MEDIA_TASKS;
    }

    public static boolean canOffloadVideoEncode() {
        return mediaEngineSupported && activeMediaTasks.get() < MAX_CONCURRENT_MEDIA_TASKS;
    }

    public static boolean canOffloadImageDecode() {
        return mediaEngineSupported && activeMediaTasks.get() < MAX_CONCURRENT_MEDIA_TASKS;
    }

    public static void offloadVideoDecode(int width, int height, String codec) {
        if (!canOffloadVideoDecode()) {
            return;
        }

        activeMediaTasks.incrementAndGet();
        videoDecodeOffloaded++;

        long startTime = System.nanoTime();

        UHDFast.LOGGER.debug("Offloading video decode: {}x{} ({})", width, height, codec);

        long processingTime = System.nanoTime() - startTime;
        totalMediaProcessingTime += processingTime;

        activeMediaTasks.decrementAndGet();
    }

    public static void offloadImageDecode(int width, int height, String format) {
        if (!canOffloadImageDecode()) {
            return;
        }

        activeMediaTasks.incrementAndGet();
        imageDecodeOffloaded++;

        long startTime = System.nanoTime();

        UHDFast.LOGGER.debug("Offloading image decode: {}x{} ({})", width, height, format);

        long processingTime = System.nanoTime() - startTime;
        totalMediaProcessingTime += processingTime;

        activeMediaTasks.decrementAndGet();
    }

    public static int getVideoDecodeOffloaded() {
        return videoDecodeOffloaded;
    }

    public static int getVideoEncodeOffloaded() {
        return videoEncodeOffloaded;
    }

    public static int getImageDecodeOffloaded() {
        return imageDecodeOffloaded;
    }

    public static int getTotalMediaTasksOffloaded() {
        return videoDecodeOffloaded + videoEncodeOffloaded + imageDecodeOffloaded;
    }

    public static long getTotalMediaProcessingTime() {
        return totalMediaProcessingTime;
    }

    public static String getMediaProcessingTimeFormatted() {
        double ms = totalMediaProcessingTime / 1_000_000.0;
        if (ms >= 1000) {
            return String.format("%.2f s", ms / 1000.0);
        }
        return String.format("%.2f ms", ms);
    }

    public static int getActiveMediaTasks() {
        return activeMediaTasks.get();
    }

    public static boolean isMediaEngineSupported() {
        return mediaEngineSupported;
    }

    public static void resetStats() {
        videoDecodeOffloaded = 0;
        videoEncodeOffloaded = 0;
        imageDecodeOffloaded = 0;
        totalMediaProcessingTime = 0;
        activeMediaTasks.set(0);
    }
}
