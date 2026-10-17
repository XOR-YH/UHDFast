package com.xor.uhdfast.gui;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.shaderpack.ShaderPack;
import com.xor.uhdfast.shaderpack.ShaderPackManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 光影包列表组件（自定义实现，不依赖 Minecraft 已废弃的 ObjectSelectionList API）。
 * <p>
 * 展示 {@link ShaderPackManager#listAvailablePacks()} 返回的可用光影包，
 * 当前已加载的光影包会被高亮标记，点击条目时通过回调通知父界面。
 * </p>
 */
@SuppressWarnings("null")
public class ShaderPackListWidget extends AbstractWidget {

    /** 选择回调，参数为被点击的光影包名称 */
    private final Consumer<String> onSelectCallback;

    /** 所有光影包条目 */
    private final List<PackEntry> entries = new ArrayList<>();

    /** 滚动偏移 */
    private int scrollOffset = 0;

    /** 条目高度 */
    private final int itemHeight;

    // 配色
    private static final int COLOR_ACTIVE_BG = 0x800071C5;
    private static final int COLOR_HOVER_BG = 0x40FFFFFF;
    private static final int COLOR_NORMAL_BG = 0x20FFFFFF;
    private static final int COLOR_ACTIVE_BAR = 0xFF00AEEF;
    private static final int COLOR_ACTIVE_TEXT = 0xFFFFFF66;
    private static final int COLOR_NORMAL_TEXT = 0xFFFFFFFF;

    /**
     * 构造光影包列表组件。
     *
     * @param x          列表 x 坐标
     * @param top        列表顶部 y 坐标
     * @param width      列表宽度
     * @param height     列表高度
     * @param itemHeight 单个条目高度
     * @param onSelect   条目被点击时的回调（参数为光影包名）
     */
    public ShaderPackListWidget(int x, int top, int width, int height, int itemHeight,
            Consumer<String> onSelect) {
        super(x, top, width, height, Component.empty());
        this.itemHeight = itemHeight;
        this.onSelectCallback = onSelect;
        refreshPacks();
    }

    /**
     * 刷新光影包列表。
     */
    public void refreshPacks() {
        entries.clear();
        ShaderPackManager mgr = ShaderPackManager.getInstance();
        List<String> packs = mgr.listAvailablePacks();
        ShaderPack current = mgr.getCurrentPack();
        String currentName = (current != null) ? current.getName() : null;

        for (int i = 0; i < packs.size(); i++) {
            String name = packs.get(i);
            entries.add(new PackEntry(name, name.equals(currentName)));
        }

        UHDFast.LOGGER.debug("Refreshed shader pack list: {} packs available", packs.size());
    }

    @Override
    public void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int visibleCount = height / itemHeight;
        int startIndex = Math.max(0, scrollOffset / itemHeight);
        int endIndex = Math.min(entries.size(), startIndex + visibleCount + 1);

        for (int i = startIndex; i < endIndex; i++) {
            PackEntry entry = entries.get(i);
            int top = getY() + (i - startIndex) * itemHeight - (scrollOffset % itemHeight);
            if (top + itemHeight < getY() || top > getY() + height) {
                continue;
            }
            entry.render(gg, i, top, getX(), width, itemHeight, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (button != 0)
            return false;

        int relY = (int) mouseY - getY() + (scrollOffset % itemHeight);
        int index = (relY / itemHeight) + (scrollOffset / itemHeight);

        if (index >= 0 && index < entries.size()) {
            if (onSelectCallback != null) {
                onSelectCallback.accept(entries.get(index).packName);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, entries.size() * itemHeight - height);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * itemHeight)));
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }

    /**
     * 获取条目总数。
     * 
     * @return 当前列表中的条目数
     */
    public int getRowCount() {
        return entries.size();
    }

    // ─────────────────────────────────────────────────────────────────
    // 内部条目类
    // ─────────────────────────────────────────────────────────────────

    /**
     * 光影包列表条目。
     */
    private static class PackEntry {
        final String packName;
        final boolean isActive;

        PackEntry(String packName, boolean isActive) {
            this.packName = packName;
            this.isActive = isActive;
        }

        void render(GuiGraphics gg, int index, int top, int left, int width, int height,
                int mouseX, int mouseY, float partialTick) {
            var font = Minecraft.getInstance().font;

            boolean hovering = mouseX >= left && mouseX <= left + width
                    && mouseY >= top && mouseY <= top + height;

            // 背景色
            int bgColor;
            if (isActive) {
                bgColor = COLOR_ACTIVE_BG;
            } else if (hovering) {
                bgColor = COLOR_HOVER_BG;
            } else {
                bgColor = COLOR_NORMAL_BG;
            }
            gg.fill(left, top, left + width, top + height, bgColor);

            // 激活条目左侧标记条
            if (isActive) {
                gg.fill(left, top, left + 3, top + height, COLOR_ACTIVE_BAR);
            }

            // 包名文字
            String display = isActive ? ("\u25CF " + packName) : packName;
            int textColor = isActive ? COLOR_ACTIVE_TEXT : COLOR_NORMAL_TEXT;
            int textY = top + (height - font.lineHeight) / 2;
            gg.drawString(font, display, left + 10, textY, textColor, false);
        }
    }
}