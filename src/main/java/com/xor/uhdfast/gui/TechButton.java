package com.xor.uhdfast.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 科技风格按钮控件
 * Intel Arc 风格：圆角矩形、渐变填充、发光边框、点击反�?
 */
public class TechButton extends TechWidget implements Renderable, GuiEventListener, NarratableEntry {

    private Component text;
    private ButtonStyle style = ButtonStyle.PRIMARY;
    private Runnable onClick;
    private boolean isPressed = false;
    private float pressAlpha = 0.0f;
    private boolean focused = false;

    // 配色 - Intel Arc 主题
    private static final int COLOR_PRIMARY_BG = 0xFF0071C5;
    private static final int COLOR_PRIMARY_HOVER = 0xFF0088E5;
    private static final int COLOR_SECONDARY_BG = 0xFF1A2535;
    private static final int COLOR_SECONDARY_HOVER = 0xFF223344;
    private static final int COLOR_DANGER_BG = 0xFFCC3333;
    private static final int COLOR_DANGER_HOVER = 0xFFEE4444;

    public enum ButtonStyle {
        PRIMARY, SECONDARY, DANGER
    }

    public TechButton(int x, int y, int width, int height, Component text, ButtonStyle style) {
        super(x, y, width, height, IntelArcBackgroundRenderer.COLOR_INTEL_BLUE);
        this.text = text;
        this.style = style;
    }

    public void setOnClick(Runnable callback) {
        this.onClick = callback;
    }

    @Override
    @SuppressWarnings("null")
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        updateAnimation(partialTick, isMouseOver(mouseX, mouseY, y));

        // 按压动画
        float targetPress = isPressed ? 1.0f : 0.0f;
        pressAlpha = Mth.lerp(Math.min(1.0f, 10.0f * partialTick), pressAlpha, targetPress);

        int currentY = y;

        // 按压时微向下偏移
        int pressOffset = (int) (pressAlpha * 2);

        // 计算背景色
        int bgColor = getBackgroundColor();
        int glowColor = getGlowColor();

        // 按钮主体（圆角矩形）
        drawRoundedRect(gg, x, currentY + pressOffset, width, height, 5, bgColor);

        // 顶部高光条（模拟玻璃反射）
        int highlightAlpha = (int) (60 + hoverAlpha * 40);
        int highlightColor = (highlightAlpha << 24) | 0xFFFFFF;
        gg.fill(x + 2, currentY + pressOffset + 1, x + width - 2, currentY + pressOffset + 3, highlightColor);

        // 发光边框
        if (hoverAlpha > 0.05f) {
            drawGlowBorder(gg, x, currentY + pressOffset, width, height, glowColor, hoverAlpha * 0.6f);
        }

        // 文字（居中）
        var font = Minecraft.getInstance().font;
        int textWidth = font.width(text);
        int textX = x + (width - textWidth) / 2;
        int textY = currentY + (height - font.lineHeight) / 2 + pressOffset;

        // 文字发光（单层偏移，避免中文重影）
        if (hoverAlpha > 0.1f) {
            int glow = (int) (hoverAlpha * 12);
            int glowColorText = (glow << 24) | 0x00AEEF;
            gg.drawString(font, text, textX - 1, textY, glowColorText, false);
            gg.drawString(font, text, textX + 1, textY, glowColorText, false);
        }

        // 核心文字
        int textColor = style == ButtonStyle.SECONDARY ? 0xFFCCCCCC : 0xFFFFFFFF;
        gg.drawString(font, text, textX, textY, textColor, false);
    }

    /**
     * 渲染（兼容旧版 5 参数调用）
     */
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick, int drawY) {
        this.y = drawY;
        render(gg, mouseX, mouseY, partialTick);
    }

    /**
     * 获取背景颜色
     */
    private int getBackgroundColor() {
        return switch (style) {
            case PRIMARY -> hoverAlpha > 0.1f ? COLOR_PRIMARY_HOVER : COLOR_PRIMARY_BG;
            case SECONDARY -> hoverAlpha > 0.1f ? COLOR_SECONDARY_HOVER : COLOR_SECONDARY_BG;
            case DANGER -> hoverAlpha > 0.1f ? COLOR_DANGER_HOVER : COLOR_DANGER_BG;
        };
    }

    /**
     * 获取发光颜色
     */
    private int getGlowColor() {
        return switch (style) {
            case PRIMARY -> 0x00AEEF;
            case SECONDARY -> 0x0071C5;
            case DANGER -> 0xFF5555;
        };
    }

    // ==================== GuiEventListener ====================

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (isMouseOver(mx, my, y) && btn == 0) {
            isPressed = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (isPressed) {
            isPressed = isMouseOver(mx, my, y);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (isPressed) {
            boolean wasOver = isMouseOver(mx, my, y);
            isPressed = false;
            if (wasOver && onClick != null) {
                onClick.run();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    // ==================== NarratableEntry ====================

    @Override
    public NarrationPriority narrationPriority() {
        return NarrationPriority.HOVERED;
    }

    @Override
    @SuppressWarnings("null")
    public void updateNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, text);
    }
}
