package com.xor.uhdfast.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;

/**
 * OpenGL 渲染状态快照
 *
 * <p>
 * 用于在执行自定义渲染（如 G-Buffer、阴影贴图渲染）前后保存和恢复 OpenGL 渲染状态，
 * 避免自定义渲染破坏 Minecraft 主渲染管线假设的 GL 状态。
 * </p>
 *
 * <p>
 * 当前捕获的状态包括：
 * <ul>
 * <li>当前绑定的 FBO（{@code GL_FRAMEBUFFER_BINDING}）</li>
 * <li>视口（{@code GL_VIEWPORT}：x, y, width, height）</li>
 * <li>深度测试启用状态（{@code GL_DEPTH_TEST}）</li>
 * <li>混合启用状态（{@code GL_BLEND}）</li>
 * <li>当前着色器程序（{@code GL_CURRENT_PROGRAM}）</li>
 * </ul>
 * </p>
 *
 * <p>
 * 典型用法：
 * </p>
 * 
 * <pre>{@code
 * RenderStateSnapshot snapshot = RenderStateSnapshot.capture();
 * try {
 *     // 执行自定义渲染，修改 GL 状态
 *     gBuffer.bind();
 *     // ... 渲染 ...
 * } finally {
 *     snapshot.restore();
 * }
 * }</pre>
 *
 * <p>
 * <b>线程安全性：</b>非线程安全，只能在渲染线程使用。
 * </p>
 */
public class RenderStateSnapshot {

    /** 捕获时绑定的 FBO ID */
    private final int savedFbo;
    /** 捕获时的视口 X 偏移 */
    private final int savedViewportX;
    /** 捕获时的视口 Y 偏移 */
    private final int savedViewportY;
    /** 捕获时的视口宽度 */
    private final int savedViewportWidth;
    /** 捕获时的视口高度 */
    private final int savedViewportHeight;
    /** 捕获时深度测试是否启用 */
    private final boolean savedDepthTest;
    /** 捕获时混合是否启用 */
    private final boolean savedBlend;
    /** 捕获时当前着色器程序 ID */
    private final int savedProgram;

    /**
     * 私有构造器，由 {@link #capture()} 调用。
     *
     * @param savedFbo            捕获的 FBO ID
     * @param savedViewportX      捕获的视口 X
     * @param savedViewportY      捕获的视口 Y
     * @param savedViewportWidth  捕获的视口宽度
     * @param savedViewportHeight 捕获的视口高度
     * @param savedDepthTest      捕获的深度测试状态
     * @param savedBlend          捕获的混合状态
     * @param savedProgram        捕获的着色器程序 ID
     */
    private RenderStateSnapshot(int savedFbo, int savedViewportX, int savedViewportY,
            int savedViewportWidth, int savedViewportHeight,
            boolean savedDepthTest, boolean savedBlend, int savedProgram) {
        this.savedFbo = savedFbo;
        this.savedViewportX = savedViewportX;
        this.savedViewportY = savedViewportY;
        this.savedViewportWidth = savedViewportWidth;
        this.savedViewportHeight = savedViewportHeight;
        this.savedDepthTest = savedDepthTest;
        this.savedBlend = savedBlend;
        this.savedProgram = savedProgram;
    }

    /**
     * 捕获当前 OpenGL 渲染状态。
     *
     * <p>
     * 读取当前 FBO 绑定、视口、深度测试/混合启用状态以及当前着色器程序。
     * 必须在渲染线程调用。
     * </p>
     *
     * @return 包含当前 GL 状态的快照对象
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public static RenderStateSnapshot capture() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new RuntimeException("GL 状态捕获必须在渲染线程执行");
        }

        // 读取当前绑定的 FBO
        int fbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        // 读取视口（GL_VIEWPORT 返回 4 个值：x, y, width, height）
        IntBuffer viewport = IntBuffer.allocate(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpX = viewport.get(0);
        int vpY = viewport.get(1);
        int vpW = viewport.get(2);
        int vpH = viewport.get(3);

        // 读取深度测试和混合状态
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);

        // 读取当前着色器程序
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        UHDFast.LOGGER.trace("GL 状态捕获: fbo={}, viewport=[{},{},{},{}], depthTest={}, blend={}, program={}",
                fbo, vpX, vpY, vpW, vpH, depthTest, blend, program);

        return new RenderStateSnapshot(fbo, vpX, vpY, vpW, vpH, depthTest, blend, program);
    }

    /**
     * 恢复捕获的 OpenGL 渲染状态。
     *
     * <p>
     * 将 FBO 绑定、视口、深度测试/混合状态以及着色器程序恢复为捕获时的值。
     * 必须在渲染线程调用。
     * </p>
     *
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public void restore() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new RuntimeException("GL 状态恢复必须在渲染线程执行");
        }

        // 恢复着色器程序
        GL20.glUseProgram(savedProgram);

        // 恢复深度测试状态
        if (savedDepthTest) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }

        // 恢复混合状态
        if (savedBlend) {
            GL11.glEnable(GL11.GL_BLEND);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }

        // 恢复视口
        GL11.glViewport(savedViewportX, savedViewportY, savedViewportWidth, savedViewportHeight);

        // 恢复 FBO 绑定
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);

        UHDFast.LOGGER.trace("GL 状态已恢复: fbo={}, viewport=[{},{},{},{}]",
                savedFbo, savedViewportX, savedViewportY, savedViewportWidth, savedViewportHeight);
    }

    /** @return 捕获的 FBO ID */
    public int getSavedFbo() {
        return savedFbo;
    }

    /** @return 捕获的视口 X 偏移 */
    public int getSavedViewportX() {
        return savedViewportX;
    }

    /** @return 捕获的视口 Y 偏移 */
    public int getSavedViewportY() {
        return savedViewportY;
    }

    /** @return 捕获的视口宽度 */
    public int getSavedViewportWidth() {
        return savedViewportWidth;
    }

    /** @return 捕获的视口高度 */
    public int getSavedViewportHeight() {
        return savedViewportHeight;
    }

    /** @return 捕获时深度测试是否启用 */
    public boolean isSavedDepthTest() {
        return savedDepthTest;
    }

    /** @return 捕获时混合是否启用 */
    public boolean isSavedBlend() {
        return savedBlend;
    }

    /** @return 捕获的着色器程序 ID */
    public int getSavedProgram() {
        return savedProgram;
    }
}
