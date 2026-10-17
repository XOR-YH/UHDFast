package com.xor.uhdfast.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 左侧标签栏组件（Sodium 风格）
 * 纯文字标签，支持滚轮滑动
 */
public class TechTabBar {

    private final List<Tab> tabs = new ArrayList<>();
    private int selectedTab = 0;
    private int x, y, width, height;
    private int tabHeight = 22;
    private int tabSpacing = 1;

    // 滚动
    private int scrollOffset = 0;
    private int maxScrollOffset = 0;

    // 配色
    private static final int COLOR_BG = 0xFF0D1F3C;
    private static final int COLOR_TAB_BG = 0xFF111822;
    private static final int COLOR_TAB_HOVER = 0xFF1A2535;
    private static final int COLOR_TAB_SELECTED = 0xFF0071C5;
    private static final int COLOR_TEXT = 0xFF99AABB;
    private static final int COLOR_TEXT_SELECTED = 0xFFFFFFFF;

    public TechTabBar(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * 添加标签
     */
    public void addTab(Component label, Runnable onClick) {
        tabs.add(new Tab(label, onClick));
    }

    /**
     * 设置选中标签
     */
    public void setSelectedTab(int index) {
        if (index >= 0 && index < tabs.size()) {
            selectedTab = index;
            // 确保选中标签可见
            scrollToVisible(index);
        }
    }

    /**
     * 获取选中标签索引
     */
    public int getSelectedTab() {
        return selectedTab;
    }

    /**
     * 计算最大滚动偏移
     */
    private void calculateMaxScroll() {
        int totalHeight = tabs.size() * (tabHeight + tabSpacing);
        maxScrollOffset = Math.max(0, totalHeight - height);
        scrollOffset = Math.min(scrollOffset, maxScrollOffset);
    }

    /**
     * 滚动到指定标签使其可见
     */
    private void scrollToVisible(int index) {
        int tabY = index * (tabHeight + tabSpacing);
        if (tabY < scrollOffset) {
            scrollOffset = tabY;
        } else if (tabY + tabHeight > scrollOffset + height) {
            scrollOffset = tabY + tabHeight - height;
        }
        scrollOffset = Math.min(scrollOffset, maxScrollOffset);
    }

    /**
     * 渲染标签栏
     */
    public void render(GuiGraphics gg, int mouseX, int mouseY) {
        calculateMaxScroll();

        // 背景
        gg.fill(x, y, x + width, y + height, COLOR_BG);

        // 右侧分隔线
        gg.fill(x + width - 1, y, x + width, y + height, 0xFF0071C5);

        // 裁剪区域
        gg.enableScissor(x, y, x + width, y + height);

        // 渲染每个标签
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            int tabY = y + i * (tabHeight + tabSpacing) - scrollOffset;
            boolean isSelected = (i == selectedTab);
            boolean isHovered = (mouseX >= x && mouseX < x + width &&
                    mouseY >= tabY && mouseY < tabY + tabHeight);

            renderTab(gg, tab, tabY, isSelected, isHovered);
        }

        gg.disableScissor();

        // 滚动条
        renderScrollbar(gg);
    }

    /**
     * 渲染单个标签
     */
    @SuppressWarnings("null")
    private void renderTab(GuiGraphics gg, Tab tab, int tabY, boolean isSelected, boolean isHovered) {
        var font = Minecraft.getInstance().font;

        // 背景
        int bgColor = isSelected ? COLOR_TAB_SELECTED : (isHovered ? COLOR_TAB_HOVER : COLOR_TAB_BG);
        gg.fill(x, tabY, x + width, tabY + tabHeight, bgColor);

        // 左侧高亮条（选中时）
        if (isSelected) {
            gg.fill(x, tabY, x + 3, tabY + tabHeight, 0xFF00D4FF);
        }

        // 文字（居中）
        int textWidth = font.width(tab.label);
        int textX = x + (width - textWidth) / 2;
        int textY = tabY + (tabHeight - 8) / 2;
        int textColor = isSelected ? COLOR_TEXT_SELECTED : COLOR_TEXT;
        gg.drawString(font, tab.label, textX, textY, textColor, false);
    }

    /**
     * 渲染滚动条
     */
    private void renderScrollbar(GuiGraphics gg) {
        if (maxScrollOffset <= 0)
            return;

        int barX = x + width - 3;
        int barH = height;
        int thumbH = Math.max(15, barH * barH / (maxScrollOffset + barH));
        int thumbY = y + (int) ((double) scrollOffset / maxScrollOffset * (barH - thumbH));

        gg.fill(barX, y, barX + 2, y + barH, 0xFF1A2535);
        gg.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFF00AEEF);
    }

    /**
     * 鼠标点击处理
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX < x || mouseX >= x + width)
            return false;

        for (int i = 0; i < tabs.size(); i++) {
            int tabY = y + i * (tabHeight + tabSpacing) - scrollOffset;
            if (mouseY >= tabY && mouseY < tabY + tabHeight) {
                selectedTab = i;
                tabs.get(i).onClick.run();
                return true;
            }
        }
        return false;
    }

    /**
     * 滚轮处理
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalDelta, double verticalDelta) {
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height)
            return false;

        if (maxScrollOffset > 0) {
            scrollOffset = (int) Math.max(0,
                    Math.min(maxScrollOffset, scrollOffset - (int) (verticalDelta * 15)));
        }
        return true;
    }

    /**
     * 获取标签数量
     */
    public int getTabCount() {
        return tabs.size();
    }

    // ==================== 内部类 ====================

    private static class Tab {
        final Component label;
        final Runnable onClick;

        Tab(Component label, Runnable onClick) {
            this.label = label;
            this.onClick = onClick;
        }
    }
}
