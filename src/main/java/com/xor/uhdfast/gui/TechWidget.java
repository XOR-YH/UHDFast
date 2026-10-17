package com.xor.uhdfast.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 科技风格控件基类
 * 提供圆角矩形、发光边框、悬停动画等科技风格特性
 */
public abstract class TechWidget {

    protected int y;
    protected int x;
    protected int width;
    protected int height;
    protected int color;

    // 悬停状态
    protected boolean isHovered = false;
    protected float hoverAlpha = 0.0f; // 悬停动画进度 (0-1)
    protected static final float HOVER_SPEED = 8.0f; // 动画速度

    // 发光效果
    protected int glowColor = 0;
    protected float glowIntensity = 0.0f;

    public TechWidget(int x, int y, int width, int height, int color) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.color = color;
    }

    /**
     * 更新动画状态
     */
    public void updateAnimation(float partialTick, boolean hovered) {
        this.isHovered = hovered;

        // 平滑过渡悬停动画
        float targetAlpha = hovered ? 1.0f : 0.0f;
        float delta = HOVER_SPEED * partialTick;
        this.hoverAlpha = Mth.lerp(Math.min(1.0f, delta), this.hoverAlpha, targetAlpha);
    }

    /**
     * 绘制圆角矩形（使用多层fill模拟）
     */
    protected void drawRoundedRect(GuiGraphics gg, int x, int y, int width, int height, int radius, int color) {
        // 主体矩形
        gg.fill(x + radius, y, x + width - radius, y + height, color);
        gg.fill(x, y + radius, x + width, y + height - radius, color);

        // 四角（简化为矩形）
        // 左上
        drawCorner(gg, x, y, radius, color);
        // 右上
        drawCorner(gg, x + width - radius, y, radius, color);
        // 左下
        drawCorner(gg, x, y + height - radius, radius, color);
        // 右下
        drawCorner(gg, x + width - radius, y + height - radius, radius, color);
    }

    /**
     * 绘制圆角（简化）
     */
    private void drawCorner(GuiGraphics gg, int x, int y, int radius, int color) {
        gg.fill(x, y, x + radius, y + radius, color);
    }

    /**
     * 绘制发光边框（多层半透明边框模拟霓虹光效）
     */
    protected void drawGlowBorder(GuiGraphics gg, int x, int y, int width, int height, int glowColor, float intensity) {
        if (intensity <= 0)
            return;

        int alpha = (int) (intensity * 40);
        int outerGlow = (alpha << 24) | (glowColor & 0x00FFFFFF);

        // 外层光晕
        gg.fill(x - 2, y - 2, x + width + 2, y + height + 2, outerGlow);

        // 中层光晕
        alpha = (int) (intensity * 80);
        int midGlow = (alpha << 24) | (glowColor & 0x00FFFFFF);
        gg.fill(x - 1, y - 1, x + width + 1, y + height + 1, midGlow);

        // 核心边框
        alpha = (int) (intensity * 150);
        int coreBorder = (alpha << 24) | (glowColor & 0x00FFFFFF);
        gg.renderOutline(x, y, width, height, coreBorder);
    }

    /**
     * 绘制渐变填充
     */
    protected void drawGradientFill(GuiGraphics gg, int x, int y, int width, int height, int colorStart, int colorEnd) {
        int steps = Math.min(height / 2, 10);
        if (steps <= 0)
            steps = 1;

        int r1 = (colorStart >> 16) & 0xFF;
        int g1 = (colorStart >> 8) & 0xFF;
        int b1 = colorStart & 0xFF;

        int r2 = (colorEnd >> 16) & 0xFF;
        int g2 = (colorEnd >> 8) & 0xFF;
        int b2 = colorEnd & 0xFF;

        for (int i = 0; i < steps; i++) {
            float ratio = i / (float) (steps - 1);
            int r = (int) (r1 + (r2 - r1) * ratio);
            int g = (int) (g1 + (g2 - g1) * ratio);
            int b = (int) (b1 + (b2 - b1) * ratio);

            int rowY = y + (int) (ratio * height);
            int rowH = Math.max(1, height / steps);

            int color = 0xFF000000 | (r << 16) | (g << 8) | b;
            gg.fill(x, rowY, x + width, rowY + rowH, color);
        }
    }

    /**
     * 绘制阴影效果
     */
    protected void drawShadow(GuiGraphics gg, int x, int y, int width, int height, int alpha) {
        int shadowColor = (alpha << 24); // 纯黑阴影

        // 底部阴影
        gg.fill(x + 2, y + height, x + width - 2, y + height + 3, shadowColor);

        // 右侧阴影
        gg.fill(x + width, y + 2, x + width + 3, y + height - 2, shadowColor >> 1);
    }

    /**
     * 绘制带发光效果的文字（单层偏移，避免中文重影）
     */
    @SuppressWarnings("null")
    protected void drawTextWithGlow(GuiGraphics gg, Component text, int x, int y, int textColor, int glowColor,
            float glowIntensity) {
        var font = Minecraft.getInstance().font;
        if (glowIntensity > 0.01f) {
            int glowAlpha = (int) (glowIntensity * 20);
            int glow = (glowAlpha << 24) | (glowColor & 0x00FFFFFF);
            gg.drawString(font, text, x - 1, y, glow, false);
            gg.drawString(font, text, x + 1, y, glow, false);
        }
        gg.drawString(font, text, x, y, textColor, false);
    }

    /**
     * 绘制扫描线（CRT 风格水平细线）
     */
    protected void drawScanLine(GuiGraphics gg, int x, int y, int width, int alpha) {
        int color = (alpha << 24);
        gg.fill(x, y, x + width, y + 1, color);
    }

    // ==================== 抽象方法 ====================

    /**
     * 渲染控件
     */
    public abstract void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick, int drawY);

    /**
     * 鼠标点击
     */
    public boolean mouseClicked(double mx, double my, int btn, int drawY) {
        return false;
    }

    /**
     * 鼠标拖拽
     */
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int drawY) {
        return false;
    }

    /**
     * 鼠标释放
     */
    public boolean mouseReleased(double mx, double my, int btn) {
        return false;
    }

    /**
     * 检查鼠标是否在控件上方
     */
    public boolean isMouseOver(double mx, double my, int drawY) {
        return mx >= x && mx <= x + width && my >= drawY && my <= drawY + height;
    }
}
