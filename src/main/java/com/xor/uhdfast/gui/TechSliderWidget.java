package com.xor.uhdfast.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 科技风格滑块控件
 * Intel Arc 风格：细线轨道、渐变填充条、圆形滑块、数值标签
 */
public class TechSliderWidget extends TechWidget {

    private final String labelKey;
    private final double min, max, step;
    private double value;
    private boolean dragging = false;

    // 配色
    private static final int COLOR_TRACK = 0xFF223344;
    private static final int COLOR_FILL_START = 0xFF0071C5;
    private static final int COLOR_FILL_END = 0xFF00AEEF;
    private static final int COLOR_KNOB = 0xFFFFFFFF;
    private static final int COLOR_LABEL_BG = 0xFF0A1628;
    private static final int COLOR_LABEL_TEXT = 0xFF00D4FF;

    public TechSliderWidget(int x, int y, int width, int height, String labelKey, double min, double max, double step) {
        super(x, y, width, height, IntelArcBackgroundRenderer.COLOR_INTEL_BLUE);
        this.labelKey = labelKey;
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = min;
        this.glowColor = COLOR_FILL_END;
    }

    public void set(double v) {
        this.value = Math.max(min, Math.min(max, v));
    }

    public double get() {
        return value;
    }

    @Override
    @SuppressWarnings("null")
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick, int drawY) {
        updateAnimation(partialTick, isMouseOver(mouseX, mouseY, drawY));

        int currentY = drawY;

        // 背景（半透明深色）
        int bgColor = hoverAlpha > 0.1f ? 0xFF1A2535 : 0xFF111822;
        drawRoundedRect(gg, x, currentY, width, height, 4, bgColor);

        // 悬停边框
        if (hoverAlpha > 0.05f) {
            int borderAlpha = (int) (hoverAlpha * 60);
            int borderColor = (borderAlpha << 24) | (0x0071C5 & 0x00FFFFFF);
            gg.renderOutline(x, currentY, width, height, borderColor);
        }

        // 标签文字
        var font = Minecraft.getInstance().font;
        Component label = Component.translatable(labelKey);
        String valText = step < 1 ? String.format("%.2f", value) : String.valueOf((int) value);
        String displayText = label.getString() + ": " + valText;

        // 计算文字宽度,将数值标签放在右侧
        int textWidth = font.width(displayText);

        // 数值标签(带背景)
        int labelWidth = textWidth + 8;
        int labelHeight = font.lineHeight + 4;
        int labelX = x + width - labelWidth - 2;
        int labelY = currentY + (height - labelHeight) / 2;

        // 数值标签背景
        drawRoundedRect(gg, labelX, labelY, labelWidth, labelHeight, 3, COLOR_LABEL_BG);
        gg.renderOutline(labelX, labelY, labelWidth, labelHeight, 0xFF00AEEF);

        // 数值文字
        gg.drawString(font, displayText, labelX + 4, labelY + 2, COLOR_LABEL_TEXT, false);

        // 轨道（细线）
        int trackY = currentY + height - 5;
        int trackHeight = 3;
        drawRoundedRect(gg, x + 4, trackY, width - 8, trackHeight, 1, COLOR_TRACK);

        // 填充条（渐变）
        double ratio = (value - min) / (max - min);
        int fillWidth = (int) ((width - 8) * ratio);

        if (fillWidth > 0) {
            // 发光效果
            int glowAlpha = (int) (40 + hoverAlpha * 30);
            int glowColor = (glowAlpha << 24) | (0x00AEEF & 0x00FFFFFF);
            gg.fill(x + 2, trackY - 2, x + fillWidth + 6, trackY + trackHeight + 2, glowColor);

            // 填充条渐变
            drawGradientFill(gg, x + 4, trackY, fillWidth, trackHeight, COLOR_FILL_START, COLOR_FILL_END);
        }

        // 滑块圆钮
        int knobSize = 10;
        int knobRadius = knobSize / 2;
        int knobX = x + 4 + fillWidth - knobRadius;
        int knobY = trackY - knobRadius + trackHeight / 2;

        // 滑块阴影
        drawShadow(gg, knobX, knobY, knobSize, knobSize, 60);

        // 滑块本体
        drawRoundedRect(gg, knobX, knobY, knobSize, knobSize, knobRadius, COLOR_KNOB);

        // 滑块高光
        gg.fill(knobX + 2, knobY + 1, knobX + knobSize - 2, knobY + knobSize / 2, 0xFFE0E0E0);

        // 滑块悬停时发光
        boolean knobHovered = mouseX >= knobX - 2 && mouseX <= knobX + knobSize + 2 &&
                mouseY >= knobY - 2 && mouseY <= knobY + knobSize + 2;
        if (knobHovered || dragging) {
            int knobGlow = 0x60 << 24 | (0x00AEEF & 0x00FFFFFF);
            gg.fill(knobX - 2, knobY - 2, knobX + knobSize + 2, knobY + knobSize + 2, knobGlow);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int drawY) {
        if (isMouseOver(mx, my, drawY)) {
            dragging = true;
            updateFromMouse(mx);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int drawY) {
        if (dragging) {
            updateFromMouse(mx);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        dragging = false;
        return false;
    }

    private void updateFromMouse(double mx) {
        double ratio = Math.max(0, Math.min(1, (mx - x - 4) / (width - 8)));
        double nv = min + ratio * (max - min);
        value = Math.max(min, Math.min(max, Math.round(nv / step) * step));
    }
}
