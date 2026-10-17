package com.xor.uhdfast.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 科技风格分段标题控件
 * Intel Arc 风格：发光标题、渐变分隔线、电路图标
 */
public class TechSectionWidget extends TechWidget {

    private final String key;
    private final int baseColor;
    private float glowPulse = 0.0f; // 呼吸灯动画

    public TechSectionWidget(int x, int y, int width, int height, String key, int color) {
        super(x, y, width, height, color);
        this.key = key;
        this.baseColor = color;
        this.glowColor = color;
    }

    @Override
    @SuppressWarnings("null")
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick, int drawY) {
        updateAnimation(partialTick, isMouseOver(mouseX, mouseY, drawY));

        // 更新呼吸灯
        glowPulse = (float) (Math.sin(System.currentTimeMillis() / 1000.0) * 0.3 + 0.7);

        int currentY = drawY;

        // 绘制左侧电路图标（菱形）
        drawCircuitIcon(gg, x + 2, currentY + 4, baseColor);

        // 标题文字（带发光效果）
        var font = Minecraft.getInstance().font;
        Component title = Component.translatable(key);
        int textX = x + 16;
        int textY = currentY + 4;

        // 发光层（单层偏移，减少中文重影）
        float glowAlpha = 0.08f * glowPulse * (0.5f + hoverAlpha * 0.5f);
        int glow = (int) (glowAlpha * 255);
        if (glow > 0) {
            int glowColor = glow << 24 | (baseColor & 0x00FFFFFF);
            gg.drawString(font, title, textX - 1, textY, glowColor, false);
            gg.drawString(font, title, textX + 1, textY, glowColor, false);
        }

        // 核心文字
        int coreAlpha = 220 + (int) (hoverAlpha * 35);
        int textColor = (coreAlpha << 24) | (baseColor & 0x00FFFFFF);
        gg.drawString(font, title, textX, textY, textColor, false);

        // 渐变分隔线（从Intel蓝到透明）
        drawGradientSeparator(gg, x + 16, currentY + height - 3, width - 16, 2, baseColor);
    }

    /**
     * 绘制电路图标（菱形）
     */
    private void drawCircuitIcon(GuiGraphics gg, int x, int y, int color) {
        int alpha = (int) (180 * glowPulse);
        int iconColor = (alpha << 24) | (color & 0x00FFFFFF);

        // 菱形
        gg.fill(x + 2, y, x + 6, y + 1, iconColor);
        gg.fill(x + 1, y + 1, x + 7, y + 3, iconColor);
        gg.fill(x, y + 3, x + 8, y + 5, iconColor);
        gg.fill(x + 1, y + 5, x + 7, y + 7, iconColor);
        gg.fill(x + 2, y + 7, x + 6, y + 8, iconColor);

        // 中心亮点
        int brightAlpha = (int) (220 * glowPulse);
        int brightColor = (brightAlpha << 24) | 0x00FFFFFF;
        gg.fill(x + 3, y + 3, x + 5, y + 5, brightColor);
    }

    /**
     * 绘制渐变分隔线
     */
    private void drawGradientSeparator(GuiGraphics gg, int x, int y, int width, int height, int color) {
        int steps = Math.max(1, width / 10);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        for (int i = 0; i < steps; i++) {
            float ratio = i / (float) (steps - 1);
            int alpha = (int) (200 * (1 - ratio));
            int stepColor = (alpha << 24) | (r << 16) | (g << 8) | b;

            int segX = x + (int) (ratio * width);
            int segW = Math.max(1, width / steps);
            gg.fill(segX, y, segX + segW, y + height, stepColor);
        }
    }
}
