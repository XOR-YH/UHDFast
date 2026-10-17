package com.xor.uhdfast.gui;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import com.xor.uhdfast.shaderpack.ShaderPack;
import com.xor.uhdfast.shaderpack.ShaderPackManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 光影管理主界面。
 * <p>
 * 提供光影包列表浏览、启用/禁用切换、加载（应用）选中的光影包、
 * 卸载当前光影包等功能。加载操作在独立线程中执行，避免阻塞主线程，
 * 加载结果通过状态消息反馈给用户。
 * </p>
 */
@SuppressWarnings("null")
public class ShaderPackScreen extends Screen {

    /** 光影包列表组件 */
    private ShaderPackListWidget packList;

    /** 父界面，返回时恢复 */
    private final Screen parentScreen;

    /** 状态消息（加载中/成功/错误等） */
    private String statusMessage;

    /** 当前选中的光影包名（可能为 null） */
    private String selectedPack;

    // 布局常量
    private static final int LIST_WIDTH = 200;
    private static final int LIST_ITEM_HEIGHT = 24;
    private static final int HEADER_HEIGHT = 40;
    private static final int FOOTER_HEIGHT = 40;
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;

    /**
     * 构造光影管理界面。
     *
     * @param parent 父界面，返回时恢复
     */
    public ShaderPackScreen(Screen parent) {
        super(Component.translatable("options.uhdfast.shaderpacks"));
        this.parentScreen = parent;
    }

    @Override
    protected void init() {
        // 确保光影包管理器已初始化（创建 shaderpacks 目录）
        ShaderPackManager.getInstance().initialize();

        // 创建光影包列表（左侧）
        int listY = HEADER_HEIGHT;
        int listHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT;
        packList = new ShaderPackListWidget(10, listY, LIST_WIDTH, listHeight,
                LIST_ITEM_HEIGHT, this::onSelectPack);
        this.addRenderableWidget(packList);

        // 右侧按钮区
        int btnX = LIST_WIDTH + 24;
        int btnY = HEADER_HEIGHT;
        ShaderPackManager mgr = ShaderPackManager.getInstance();

        // 启用/禁用光影切换按钮（根据当前状态显示文本）
        Component toggleText = mgr.isEnabled()
                ? Component.translatable("options.uhdfast.shaderpacks.disable")
                : Component.translatable("options.uhdfast.shaderpacks.enable");
        this.addRenderableWidget(Button.builder(toggleText, b -> toggleShaderEnabled())
                .bounds(btnX, btnY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // 应用按钮 - 加载选中的光影包
        this.addRenderableWidget(Button.builder(
                Component.translatable("options.uhdfast.shaderpacks.apply"),
                b -> {
                    if (selectedPack != null) {
                        applySelectedPack(selectedPack);
                    }
                })
                .bounds(btnX, btnY + (BUTTON_HEIGHT + BUTTON_GAP), BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // 卸载按钮 - 卸载当前光影包
        this.addRenderableWidget(Button.builder(
                Component.translatable("options.uhdfast.shaderpacks.unload"),
                b -> unloadCurrentPack())
                .bounds(btnX, btnY + (BUTTON_HEIGHT + BUTTON_GAP) * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // 完成按钮 - 返回父界面
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                b -> Minecraft.getInstance().setScreen(parentScreen))
                .bounds(this.width / 2 - 100, this.height - 28, 200, BUTTON_HEIGHT).build());
    }

    /**
     * 选择光影包的回调。
     *
     * @param packName 被选中的光影包名
     */
    private void onSelectPack(String packName) {
        this.selectedPack = packName;
        UHDFast.LOGGER.info("Selected shader pack: {}", packName);
    }

    /**
     * 应用选中的光影包。
     * <p>
     * 在新线程中调用 {@link ShaderPackManager#loadPack(String)}，
     * 完成后回到主线程更新状态消息并刷新列表。
     * </p>
     *
     * @param packName 要加载的光影包名
     */
    private void applySelectedPack(String packName) {
        setStatus(Component.translatable("options.uhdfast.shaderpacks.loading", packName).getString());

        Thread loader = new Thread(() -> {
            boolean success = ShaderPackManager.getInstance().loadPack(packName);
            // 回到主线程更新 UI
            Minecraft.getInstance().execute(() -> {
                if (success) {
                    ShaderPackManager.getInstance().setEnabled(true);
                    UHDFastConfig.shaderPackName.set(packName);
                    UHDFastConfig.enableShaderPacks.set(true);
                    setStatus(Component.translatable("options.uhdfast.shaderpacks.loadSuccess", packName).getString());
                    UHDFast.LOGGER.info("Shader pack applied: {}", packName);
                } else {
                    setStatus(Component.translatable("options.uhdfast.shaderpacks.loadFail", packName).getString());
                    UHDFast.LOGGER.warn("Failed to apply shader pack: {}", packName);
                }
                if (packList != null) {
                    packList.refreshPacks();
                }
                // 重建按钮以刷新启用/禁用按钮文本
                rebuildWidgets();
            });
        }, "UHDFast-ShaderLoader");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 切换光影启用状态。
     */
    private void toggleShaderEnabled() {
        ShaderPackManager mgr = ShaderPackManager.getInstance();
        boolean newState = !mgr.isEnabled();
        mgr.setEnabled(newState);
        UHDFastConfig.enableShaderPacks.set(newState);

        if (newState) {
            setStatus(Component.translatable("options.uhdfast.shaderpacks.enabled").getString());
        } else {
            setStatus(Component.translatable("options.uhdfast.shaderpacks.disabled").getString());
        }
        UHDFast.LOGGER.info("Shader pack enabled state toggled: {}", newState);
        rebuildWidgets();
    }

    /**
     * 卸载当前光影包。
     */
    private void unloadCurrentPack() {
        ShaderPackManager mgr = ShaderPackManager.getInstance();
        mgr.unloadPack();
        UHDFastConfig.shaderPackName.set("");
        setStatus(Component.translatable("options.uhdfast.shaderpacks.unloaded").getString());
        UHDFast.LOGGER.info("Shader pack unloaded");
        if (packList != null) {
            packList.refreshPacks();
        }
        rebuildWidgets();
    }

    /**
     * 设置状态消息并记录时间。
     *
     * @param msg 状态消息文本
     */
    private void setStatus(String msg) {
        this.statusMessage = msg;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 背景
        graphics.fill(0, 0, this.width, this.height, 0xCC0A1628);

        // 标题
        graphics.drawString(this.font, this.title, 10, 12, 0xFFFFFFFF, false);

        // 当前光影包信息（右侧按钮区下方）
        ShaderPackManager mgr = ShaderPackManager.getInstance();
        ShaderPack current = mgr.getCurrentPack();
        int infoX = LIST_WIDTH + 24;
        int infoY = HEADER_HEIGHT + (BUTTON_HEIGHT + BUTTON_GAP) * 3 + 10;
        if (current != null) {
            String status = mgr.isEnabled() ? " (ON)" : " (OFF)";
            String info = current.getName() + status;
            graphics.drawString(this.font, info, infoX, infoY, 0xFF00AEEF, false);
        } else {
            graphics.drawString(this.font,
                    Component.translatable("options.uhdfast.shaderpacks.none").getString(),
                    infoX, infoY, 0xFF888888, false);
        }

        // 选中包提示
        if (selectedPack != null) {
            String sel = Component.translatable("options.uhdfast.shaderpacks.selected", selectedPack).getString();
            graphics.drawString(this.font, sel, infoX, infoY + 14, 0xFFCCCCCC, false);
        }

        // 状态消息
        if (statusMessage != null) {
            graphics.drawString(this.font, statusMessage, 10, this.height - 50, 0xFF00AEEF, false);
        }

        // 渲染列表与按钮
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
