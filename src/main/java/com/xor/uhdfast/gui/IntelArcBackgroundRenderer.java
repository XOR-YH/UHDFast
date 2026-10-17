package com.xor.uhdfast.gui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.*;

/**
 * Intel Arc 风格背景渲染器（性能优化版）
 * 使用最少 draw call 实现科技感背景
 */
public class IntelArcBackgroundRenderer {

    // Intel Arc 配色方案
    public static final int COLOR_DEEP_BLUE = 0xFF0A1628;
    public static final int COLOR_DARK_BLUE = 0xFF0D1F3C;
    public static final int COLOR_INTEL_BLUE = 0xFF0071C5;
    public static final int COLOR_CYAN_BLUE = 0xFF00AEEF;
    public static final int COLOR_NEON_BLUE = 0xFF00D4FF;
    public static final int COLOR_ACCENT_GREEN = 0xFF00FFAA;

    // 电路图案
    private static final List<CircuitLine> circuitLines = new ArrayList<>();
    private static final List<CircuitNode> circuitNodes = new ArrayList<>();

    // 粒子系统
    private static final List<Particle> particles = new ArrayList<>();
    private static final int MAX_PARTICLES = 15; // 从35减到15

    // 共享随机实例
    private static final Random SHARED_RANDOM = new Random(42);
    private static final Random PARTICLE_RANDOM = new Random(12345);

    // 初始化标记
    private static boolean initialized = false;
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    // 呼吸光效缓存（避免每帧计算）
    private static float cachedBreath = 1.0f;
    private static long lastBreathTime = 0;

    public static void initialize(int width, int height) {
        if (initialized && lastWidth == width && lastHeight == height) {
            return;
        }

        lastWidth = width;
        lastHeight = height;
        circuitLines.clear();
        circuitNodes.clear();
        particles.clear();

        generateCircuitPattern(width, height);
        initializeParticles(width, height);

        initialized = true;
    }

    private static void generateCircuitPattern(int width, int height) {
        int gridSpacing = 60; // 增大间距减少节点数
        int cols = width / gridSpacing + 2;
        int rows = height / gridSpacing + 2;

        // 减少电路节点（从25减到10）
        for (int i = 0; i < 10; i++) {
            int x = SHARED_RANDOM.nextInt(cols) * gridSpacing + SHARED_RANDOM.nextInt(gridSpacing);
            int y = SHARED_RANDOM.nextInt(rows) * gridSpacing + SHARED_RANDOM.nextInt(gridSpacing);
            circuitNodes.add(new CircuitNode(x, y, SHARED_RANDOM.nextInt(2) + 2));
        }

        // 减少电路走线（从40减到15）
        for (int i = 0; i < 15; i++) {
            CircuitNode start = circuitNodes.get(SHARED_RANDOM.nextInt(circuitNodes.size()));
            CircuitNode end = circuitNodes.get(SHARED_RANDOM.nextInt(circuitNodes.size()));

            if (start == end)
                continue;

            int alpha = SHARED_RANDOM.nextInt(10) + 5;
            circuitLines.add(new CircuitLine(
                    start.x, start.y,
                    end.x, end.y,
                    alpha,
                    SHARED_RANDOM.nextBoolean()));
        }
    }

    private static void initializeParticles(int width, int height) {
        for (int i = 0; i < MAX_PARTICLES; i++) {
            particles.add(new Particle(
                    PARTICLE_RANDOM.nextInt(width),
                    PARTICLE_RANDOM.nextInt(height),
                    PARTICLE_RANDOM.nextFloat() * 0.3f + 0.1f,
                    PARTICLE_RANDOM.nextFloat() * 0.2f - 0.1f,
                    PARTICLE_RANDOM.nextInt(2) + 2,
                    PARTICLE_RANDOM.nextInt(40) + 30));
        }
    }

    public static void render(GuiGraphics gg, int width, int height, float partialTick) {
        if (!initialized) {
            initialize(width, height);
        }

        // 1. 底层纯色背景 + 简单渐变（仅2层）
        renderBackground(gg, width, height);

        // 2. 电路图案（已精简）
        renderCircuitPattern(gg, width, height);

        // 3. 粒子层（已精简）
        renderParticles(gg, width, height, partialTick);
    }

    /**
     * 渲染背景（极简版：纯色 + 1层渐变）
     */
    private static void renderBackground(GuiGraphics gg, int width, int height) {
        // 每3秒更新一次呼吸值
        long now = System.currentTimeMillis();
        if (now - lastBreathTime > 50) {
            cachedBreath = (float) (Math.sin(now / 3000.0) * 0.08 + 1.0);
            lastBreathTime = now;
        }

        // 底层纯色
        int baseColor = 0xFF0A1628;
        gg.fill(0, 0, width, height, baseColor);

        // 仅一层径向渐变（从中心到边缘）
        int centerX = width / 2;
        int centerY = height / 2;
        int maxRadius = (int) Math.sqrt(centerX * centerX + centerY * centerY);

        // 用5个同心矩形模拟渐变（从21层减到5层）
        for (int i = 4; i >= 0; i--) {
            float ratio = i / 4.0f;
            int radius = (int) (maxRadius * ratio);
            int x = centerX - radius;
            int y = centerY - radius;
            int w = radius * 2;
            int h = radius * 2;

            int r = (int) ((10 + 3 * ratio) * cachedBreath);
            int g = (int) ((22 + 9 * ratio) * cachedBreath);
            int b = (int) ((40 + 20 * ratio) * cachedBreath);

            r = Math.min(255, Math.max(0, r));
            g = Math.min(255, Math.max(0, g));
            b = Math.min(255, Math.max(0, b));

            gg.fill(x, y, x + w, y + h, 0xFF000000 | (r << 16) | (g << 8) | b);
        }
    }

    /**
     * 渲染电路图案
     */
    private static void renderCircuitPattern(GuiGraphics gg, int width, int height) {
        for (CircuitLine line : circuitLines) {
            int color = (line.alpha << 24) | (0x0071C5 & 0x00FFFFFF);

            if (line.horizontalFirst) {
                gg.fill(line.x1, line.y1, line.x2, line.y1 + 1, color);
                gg.fill(line.x2, line.y1, line.x2 + 1, line.y2, color);
            } else {
                gg.fill(line.x1, line.y1, line.x1 + 1, line.y2, color);
                gg.fill(line.x1, line.y2, line.x2, line.y2 + 1, color);
            }
        }

        for (CircuitNode node : circuitNodes) {
            int size = node.size;
            int alpha = 20;
            int color = (alpha << 24) | (0x00AEEF & 0x00FFFFFF);
            gg.fill(node.x - size, node.y - size, node.x + size, node.y + size, color);
        }
    }

    /**
     * 渲染粒子（精简版：无光晕，仅核心）
     */
    private static void renderParticles(GuiGraphics gg, int width, int height, float partialTick) {
        for (Particle p : particles) {
            p.y -= p.speed * partialTick;
            p.x += p.drift * partialTick;

            if (p.y < -10) {
                p.y = height + 10;
                p.x = PARTICLE_RANDOM.nextInt(width);
            }
            if (p.x < -10)
                p.x = width + 10;
            if (p.x > width + 10)
                p.x = -10;

            // 仅渲染核心（去掉光晕，减少一半draw call）
            int coreColor = p.alpha << 24 | (0x00D4FF & 0x00FFFFFF);
            gg.fill((int) p.x - p.size, (int) p.y - p.size,
                    (int) p.x + p.size, (int) p.y + p.size, coreColor);
        }
    }

    public static void shutdown() {
        circuitLines.clear();
        circuitNodes.clear();
        particles.clear();
        initialized = false;
    }

    // ==================== 内部类 ====================

    private static class CircuitLine {
        int x1, y1, x2, y2;
        int alpha;
        boolean horizontalFirst;

        CircuitLine(int x1, int y1, int x2, int y2, int alpha, boolean horizontalFirst) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.alpha = alpha;
            this.horizontalFirst = horizontalFirst;
        }
    }

    private static class CircuitNode {
        int x, y, size;

        CircuitNode(int x, int y, int size) {
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    private static class Particle {
        float x, y;
        float speed, drift;
        int size, alpha;

        Particle(float x, float y, float speed, float drift, int size, int alpha) {
            this.x = x;
            this.y = y;
            this.speed = speed;
            this.drift = drift;
            this.size = size;
            this.alpha = alpha;
        }
    }
}
