package com.xor.uhdfast.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 科技风格按钮控件
 * 用于在配置界面中执行操作（如打开文件夹、打开管理界面等）
 */
@SuppressWarnings("null")
public class TechButtonWidget extends TechWidget {

    private final Component label;
    private final Runnable onClick;

    // 配色
    private static final int COLOR_BG_HOVER = 0xFF1A2A3A;
    private static final int COLOR_BG_NORMAL = 0xFF111822;
    private static final int COLOR_BORDER = 0xFF0071C5;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_HOVER = 0xFF00AEEF;

    public TechButtonWidget(int x, int y, int width, int height, Component label, Runnable onClick) {
        super(x, y, width, height, IntelArcBackgroundRenderer.COLOR_INTEL_BLUE);
        this.label = label;
        this.onClick = onClick;
        this.glowColor = COLOR_BORDER;
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick, int drawY) {
        updateAnimation(partialTick, isMouseOver(mouseX, mouseY, drawY));

        int currentY = drawY;

        // 背景（悬停时微亮）
        int bgColor = hoverAlpha > 0.1f ? COLOR_BG_HOVER : COLOR_BG_NORMAL;
        drawRoundedRect(gg, x, currentY, width, height, 4, bgColor);

        // 悬停边框
        if (hoverAlpha > 0.05f) {
            int borderAlpha = (int) (hoverAlpha * 60);
            int borderColor = (borderAlpha << 24) | (COLOR_BORDER & 0x00FFFFFF);
            gg.renderOutline(x, currentY, width, height, borderColor);
        }

        // 标签文字
        var font = Minecraft.getInstance().font;
        int textX = x + 4;
        int textY = currentY + (height - font.lineHeight) / 2;

        // 悬停时文字发光
        if (hoverAlpha > 0.1f) {
            int glowAlpha = (int) (hoverAlpha * 15);
            int glow = (glowAlpha << 24) | (COLOR_BORDER & 0x00FFFFFF);
            gg.drawString(font, label, textX - 1, textY, glow, false);
            gg.drawString(font, label, textX + 1, textY, glow, false);
        }

        int textColor = hoverAlpha > 0.1f ? COLOR_TEXT_HOVER : COLOR_TEXT;
        gg.drawString(font, label, textX, textY, textColor, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int drawY) {
        if (isMouseOver(mx, my, drawY) && btn == 0) {
            if (onClick != null) {
                onClick.run();
            }
            return true;
        }
        return false;
    }
}
