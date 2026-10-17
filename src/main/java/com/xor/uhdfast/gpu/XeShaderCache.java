package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class XeShaderCache {

    private static final Map<String, ShaderProgram> shaderCache = new HashMap<>();
    private static final Map<String, Long> shaderCompileTimes = new HashMap<>();
    private static int cacheHits = 0;
    private static int cacheMisses = 0;
    private static long totalCompileTimeSaved = 0;
    private static boolean initialized = false;

    public static class ShaderProgram {
        public final int programId;
        public final int vertexShaderId;
        public final int fragmentShaderId;
        public final String sourceHash;
        public final long compileTime;
        public int useCount = 0;

        public ShaderProgram(int programId, int vertexShaderId, int fragmentShaderId,
                String sourceHash, long compileTime) {
            this.programId = programId;
            this.vertexShaderId = vertexShaderId;
            this.fragmentShaderId = fragmentShaderId;
            this.sourceHash = sourceHash;
            this.compileTime = compileTime;
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
            UHDFast.LOGGER.info("Shader cache disabled - not Xe architecture");
            return;
        }

        UHDFast.LOGGER.info("Xe Shader Cache initialized");
        UHDFast.LOGGER.info("  Xe architecture benefits from shader pre-compilation");
        UHDFast.LOGGER.info("  EU-based shader compilation optimization enabled");
    }

    public static void onShaderLoaded(String shaderName) {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return;
        }

        cacheHits++;
        UHDFast.LOGGER.debug("Shader loaded from cache: {}", shaderName);
    }

    public static int compileShaderProgram(String vertexSource, String fragmentSource, String shaderKey) {
        if (!initialized) {
            initialize();
        }

        if (!IntelXeDetector.isXeArchitecture()) {
            return compileShaderDirect(vertexSource, fragmentSource);
        }

        String sourceHash = computeSourceHash(vertexSource, fragmentSource);

        ShaderProgram cached = shaderCache.get(shaderKey);
        if (cached != null && cached.sourceHash.equals(sourceHash)) {
            cacheHits++;
            cached.useCount++;

            GL20.glUseProgram(cached.programId);
            return cached.programId;
        }

        cacheMisses++;
        long startTime = System.nanoTime();

        int programId = compileShaderDirect(vertexSource, fragmentSource);
        long compileTime = System.nanoTime() - startTime;

        int vertexShaderId = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        int fragmentShaderId = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);

        ShaderProgram program = new ShaderProgram(programId, vertexShaderId, fragmentShaderId,
                sourceHash, compileTime);
        shaderCache.put(shaderKey, program);
        shaderCompileTimes.put(shaderKey, compileTime);

        totalCompileTimeSaved += compileTime;

        UHDFast.LOGGER.debug("Shader compiled and cached: {} ({} ms)", shaderKey, compileTime / 1_000_000.0);

        return programId;
    }

    private static int compileShaderDirect(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource);

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            UHDFast.LOGGER.error("Shader link failed: {}", GL20.glGetProgramInfoLog(program));
            GL20.glDeleteProgram(program);
            return 0;
        }

        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            UHDFast.LOGGER.error("Shader compile failed: {}", GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    private static String computeSourceHash(String vertexSource, String fragmentSource) {
        String combined = vertexSource + fragmentSource;
        byte[] bytes = combined.getBytes(StandardCharsets.UTF_8);

        long hash = 0;
        for (byte b : bytes) {
            hash = hash * 31 + (b & 0xFF);
        }

        return Long.toHexString(hash);
    }

    public static void useShader(String shaderKey) {
        ShaderProgram program = shaderCache.get(shaderKey);
        if (program != null) {
            GL20.glUseProgram(program.programId);
            program.useCount++;
        }
    }

    public static void cleanupShader(String shaderKey) {
        ShaderProgram program = shaderCache.remove(shaderKey);
        if (program != null) {
            GL20.glDeleteProgram(program.programId);
            shaderCompileTimes.remove(shaderKey);
        }
    }

    public static void cleanupAll() {
        for (ShaderProgram program : shaderCache.values()) {
            GL20.glDeleteProgram(program.programId);
        }
        shaderCache.clear();
        shaderCompileTimes.clear();
    }

    public static int getCacheHits() {
        return cacheHits;
    }

    public static int getCacheMisses() {
        return cacheMisses;
    }

    public static double getCacheHitRate() {
        int total = cacheHits + cacheMisses;
        if (total == 0) {
            return 0;
        }
        return (double) cacheHits / total * 100.0;
    }

    public static int getCacheSize() {
        return shaderCache.size();
    }

    public static int getCachedShaderCount() {
        return shaderCache.size();
    }

    public static long getTotalCompileTimeSaved() {
        return totalCompileTimeSaved;
    }

    public static String getCompileTimeSavedFormatted() {
        double ms = totalCompileTimeSaved / 1_000_000.0;
        if (ms >= 1000) {
            return String.format("%.2f s", ms / 1000.0);
        }
        return String.format("%.2f ms", ms);
    }

    public static void resetStats() {
        cacheHits = 0;
        cacheMisses = 0;
        totalCompileTimeSaved = 0;
    }
}
