package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;

import java.util.ArrayList;
import java.util.List;

public class XePerformanceProfiler {

    private static final List<ProfileFrame> frameHistory = new ArrayList<>();
    private static final int MAX_FRAME_HISTORY = 300;
    private static long currentFrameStartTime = 0;
    private static long totalProfiledFrames = 0;
    private static long totalFrameTime = 0;
    private static long minFrameTime = Long.MAX_VALUE;
    private static long maxFrameTime = 0;
    private static boolean initialized = false;

    public static class ProfileFrame {
        public final long frameNumber;
        public final long frameTimeNs;
        public final float fps;
        public final long renderTimeNs;
        public final long updateTimeNs;
        public final long gpuTimeNs;
        public final int qualityLevel;

        public ProfileFrame(long frameNumber, long frameTimeNs, float fps, long renderTimeNs,
                long updateTimeNs, long gpuTimeNs, int qualityLevel) {
            this.frameNumber = frameNumber;
            this.frameTimeNs = frameTimeNs;
            this.fps = fps;
            this.renderTimeNs = renderTimeNs;
            this.updateTimeNs = updateTimeNs;
            this.gpuTimeNs = gpuTimeNs;
            this.qualityLevel = qualityLevel;
        }
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            UHDFast.LOGGER.info("Performance profiler disabled - not Xe architecture");
            return;
        }

        UHDFast.LOGGER.info("Xe Performance Profiler initialized");
        UHDFast.LOGGER.info("  Tracking frame-by-frame performance metrics");
        UHDFast.LOGGER.info("  History buffer: {} frames", MAX_FRAME_HISTORY);

        initialized = true;
    }

    public static void beginFrame() {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return;
        }

        currentFrameStartTime = System.nanoTime();
    }

    public static void endFrame(long renderTimeNs, long updateTimeNs, long gpuTimeNs) {
        if (!IntelXeDetector.isXeArchitecture()) {
            return;
        }

        long frameTimeNs = System.nanoTime() - currentFrameStartTime;
        float fps = frameTimeNs > 0 ? 1_000_000_000.0f / frameTimeNs : 0;

        totalProfiledFrames++;
        totalFrameTime += frameTimeNs;

        if (frameTimeNs < minFrameTime) {
            minFrameTime = frameTimeNs;
        }
        if (frameTimeNs > maxFrameTime) {
            maxFrameTime = frameTimeNs;
        }

        ProfileFrame frame = new ProfileFrame(
                totalProfiledFrames,
                frameTimeNs,
                fps,
                renderTimeNs,
                updateTimeNs,
                gpuTimeNs,
                5); // Default quality level

        frameHistory.add(frame);

        if (frameHistory.size() > MAX_FRAME_HISTORY) {
            frameHistory.remove(0);
        }

        if (totalProfiledFrames % 60 == 0) {
            logFrameSummary();
        }
    }

    private static void logFrameSummary() {
        if (frameHistory.isEmpty()) {
            return;
        }

        long recentFrameTime = 0;
        int count = Math.min(60, frameHistory.size());

        for (int i = frameHistory.size() - count; i < frameHistory.size(); i++) {
            recentFrameTime += frameHistory.get(i).frameTimeNs;
        }

        float avgFps = count > 0 ? 1_000_000_000.0f / (recentFrameTime / count) : 0;

        UHDFast.LOGGER.debug("Performance summary (last {} frames): avg FPS={}, quality={}",
                count, String.format("%.1f", avgFps),
                5); // Default quality level
    }

    public static float getAverageFPS() {
        if (totalProfiledFrames == 0) {
            return 0;
        }
        return 1_000_000_000.0f / (totalFrameTime / totalProfiledFrames);
    }

    public static float getMinimumFPS() {
        if (maxFrameTime == 0) {
            return 0;
        }
        return 1_000_000_000.0f / maxFrameTime;
    }

    public static float getMaximumFPS() {
        if (minFrameTime == Long.MAX_VALUE) {
            return 0;
        }
        return 1_000_000_000.0f / minFrameTime;
    }

    public static float getFrameTimeVariance() {
        if (frameHistory.size() < 2) {
            return 0;
        }

        long meanFrameTime = totalFrameTime / totalProfiledFrames;
        long varianceSum = 0;

        for (ProfileFrame frame : frameHistory) {
            long diff = frame.frameTimeNs - meanFrameTime;
            varianceSum += diff * diff;
        }

        return (float) Math.sqrt(varianceSum / frameHistory.size());
    }

    public static float getFrameTimeVarianceMs() {
        return getFrameTimeVariance() / 1_000_000.0f;
    }

    public static long getTotalProfiledFrames() {
        return totalProfiledFrames;
    }

    public static String getAverageFrameTimeFormatted() {
        if (totalProfiledFrames == 0) {
            return "0.00 ms";
        }
        double avgMs = (totalFrameTime / totalProfiledFrames) / 1_000_000.0;
        return String.format("%.2f ms", avgMs);
    }

    public static String getMinFrameTimeFormatted() {
        if (minFrameTime == Long.MAX_VALUE) {
            return "0.00 ms";
        }
        return String.format("%.2f ms", minFrameTime / 1_000_000.0);
    }

    public static String getMaxFrameTimeFormatted() {
        return String.format("%.2f ms", maxFrameTime / 1_000_000.0);
    }

    public static double getFPSStabilityScore() {
        float variance = getFrameTimeVariance();
        if (totalFrameTime == 0) {
            return 100.0;
        }

        long meanFrameTime = totalFrameTime / totalProfiledFrames;
        if (meanFrameTime == 0) {
            return 100.0;
        }

        double coefficientOfVariation = (variance / meanFrameTime) * 100.0;
        return Math.max(0, 100.0 - coefficientOfVariation);
    }

    public static List<ProfileFrame> getFrameHistory() {
        return new ArrayList<>(frameHistory);
    }

    public static void resetStats() {
        frameHistory.clear();
        totalProfiledFrames = 0;
        totalFrameTime = 0;
        minFrameTime = Long.MAX_VALUE;
        maxFrameTime = 0;
        currentFrameStartTime = 0;
    }
}
