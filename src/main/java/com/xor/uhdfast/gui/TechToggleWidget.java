package com.xor.uhdfast.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 科技风格开关控件
 * Intel Arc 风格：圆角胶囊开关、渐变填充、发光效果、平滑过渡动画
 */
public class TechToggleWidget extends TechWidget {

    private final String labelKey;
    private boolean value = false;
    private float animProgress = 0.0f; // 开关动画进度 (0-1)

    // 配色
    private static final int COLOR_ON_START = 0xFF00AA44;
    private static final int COLOR_ON_END = 0xFF00DD66;
    private static final int COLOR_OFF = 0xFF333344;
    private static final int COLOR_BG_HOVER = 0xFF1A2A3A;
    private static final int COLOR_BG_NORMAL = 0xFF111822;

    public TechToggleWidget(int x, int y, int width, int height, String labelKey, String descKey) {
        super(x, y, width, height, IntelArcBackgroundRenderer.COLOR_INTEL_BLUE);
        this.labelKey = labelKey;
        this.glowColor = COLOR_ON_END;
    }

    public void set(boolean v) {
        this.value = v;
        this.animProgress = v ? 1.0f : 0.0f;
    }

    public boolean get() {
        return this.value;
    }

    @Override
    @SuppressWarnings("null")
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick, int drawY) {
        updateAnimation(partialTick, isMouseOver(mouseX, mouseY, drawY));

        // 更新开关动画
        float targetAnim = value ? 1.0f : 0.0f;
        animProgress = Mth.lerp(Math.min(1.0f, 8.0f * partialTick), animProgress, targetAnim);

        int currentY = drawY;

        // 背景（悬停时微亮）
        int bgColor = hoverAlpha > 0.1f ? COLOR_BG_HOVER : COLOR_BG_NORMAL;
        drawRoundedRect(gg, x, currentY, width, height, 4, bgColor);

        // 悬停边框
        if (hoverAlpha > 0.05f) {
            int borderAlpha = (int) (hoverAlpha * 60);
            int borderColor = (borderAlpha << 24) | (0x0071C5 & 0x00FFFFFF);
            gg.renderOutline(x, currentY, width, height, borderColor);
        }

        // 开关滑块（胶囊形）
        int toggleWidth = 28;
        int toggleHeight = 12;
        int toggleY = currentY + (height - toggleHeight) / 2;
        int toggleX = x + width - toggleWidth - 6;

        drawToggleSwitch(gg, toggleX, toggleY, toggleWidth, toggleHeight);

        // 标签文字
        var font = Minecraft.getInstance().font;
        Component label = Component.translatable(labelKey);
        int textX = x + 4;
        int textY = currentY + (height - font.lineHeight) / 2;

        // 悬停时文字发光（单层偏移）
        if (hoverAlpha > 0.1f) {
            int glowAlpha = (int) (hoverAlpha * 15);
            int glow = (glowAlpha << 24) | (0x00AEEF & 0x00FFFFFF);
            gg.drawString(font, label, textX - 1, textY, glow, false);
            gg.drawString(font, label, textX + 1, textY, glow, false);
        }

        // 核心文字
        gg.drawString(font, label, textX, textY, 0xFFFFFFFF, false);

        // 开启状态指示器（发光方块）
        if (value && animProgress > 0.5f) {
            int iconAlpha = (int) ((animProgress - 0.5f) * 2 * 200);
            int iconColor = (iconAlpha << 24) | 0x00FF88;
            int indicatorX = x + width - toggleWidth - 22;
            int indicatorY = textY + 1;
            int indicatorSize = 6;
            gg.fill(indicatorX, indicatorY, indicatorX + indicatorSize, indicatorY + indicatorSize, iconColor);
            // 内部亮点
            int brightColor = (iconAlpha << 24) | 0xFFFFFF;
            gg.fill(indicatorX + 2, indicatorY + 2, indicatorX + 4, indicatorY + 4, brightColor);
        }
    }

    /**
     * 绘制开关滑块
     */
    private void drawToggleSwitch(GuiGraphics gg, int x, int y, int width, int height) {
        int radius = height / 2;

        // 滑块背景
        if (animProgress > 0.1f) {
            // 开启状态：渐变填充
            drawGradientFill(gg, x, y, width, height, COLOR_ON_START, COLOR_ON_END);

            // 发光效果
            int glowAlpha = (int) (animProgress * 40);
            int glow = (glowAlpha << 24) | (0x00FF66 & 0x00FFFFFF);
            gg.fill(x - 1, y - 1, x + width + 1, y + height + 1, glow);
        } else {
            // 关闭状态：暗灰
            drawRoundedRect(gg, x, y, width, height, radius, COLOR_OFF);
        }

        // 滑块圆钮
        int knobSize = height - 4;
        int knobRadius = knobSize / 2;
        int knobX = x + 2 + (int) (animProgress * (width - knobSize - 4));
        int knobY = y + 2;

        // 圆钮阴影
        int shadowAlpha = value ? 80 : 40;
        drawShadow(gg, knobX, knobY, knobSize, knobSize, shadowAlpha);

        // 圆钮本体
        int knobColor = value ? 0xFFFFFFFF : 0xFF888899;
        drawRoundedRect(gg, knobX, knobY, knobSize, knobSize, knobRadius, knobColor);

        // 圆钮高光
        int highlightColor = value ? 0xFFE0E0E0 : 0xFFAAAAAA;
        gg.fill(knobX + 2, knobY + 2, knobX + knobSize - 2, knobY + knobSize / 2, highlightColor);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int drawY) {
        if (isMouseOver(mx, my, drawY)) {
            value = !value;
            return true;
        }
        return false;
    }
}
