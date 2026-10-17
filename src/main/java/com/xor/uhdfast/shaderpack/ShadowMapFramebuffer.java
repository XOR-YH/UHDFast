package com.xor.uhdfast.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 阴影贴图帧缓冲管理类
 *
 * <p>
 * 管理用于阴影映射（Shadow Mapping）的 FBO。该 FBO 主要包含一个深度纹理，
 * 从光源视角渲染场景时记录每个片元的深度。深度纹理配置了硬件阴影比较模式，
 * 着色器使用 {@code sampler2DShadow} 采样时可自动完成深度比较并返回 [0,1] 的可见性。
 * </p>
 *
 * <p>
 * 此外还包含一个可选的颜色纹理（{@code GL_R8}），用于存储额外的阴影信息
 * （如阴影柔化/PCF 中间结果、阴影强度等），默认创建。
 * </p>
 *
 * <p>
 * 典型分辨率：1024×1024 或 2048×2048，可在配置中调整。
 * </p>
 *
 * <p>
 * <b>线程安全性：</b>非线程安全，只能在渲染线程使用。
 * 所有 GL 操作通过 {@link RenderSystem#isOnRenderThread()} 校验。
 * </p>
 */
public class ShadowMapFramebuffer {

    /** 深度纹理每像素字节数（32 位浮点 = 4 字节） */
    private static final int DEPTH_BYTES_PER_PIXEL = 4;
    /** 颜色纹理每像素字节数（R8 = 1 字节） */
    private static final int COLOR_BYTES_PER_PIXEL = 1;

    /** FBO 对象 ID */
    private int fboId;
    /** 阴影深度纹理 ID */
    private int depthTextureId;
    /** 阴影颜色纹理 ID（用于阴影柔化等附加数据） */
    private int colorTextureId;
    /** 阴影贴图宽度（像素） */
    private int width;
    /** 阴影贴图高度（像素） */
    private int height;

    /**
     * 构造阴影贴图帧缓冲。
     *
     * <p>
     * 构造器只记录分辨率，不分配 GL 资源。需调用 {@link #create()} 完成实际创建。
     * </p>
     *
     * @param width  阴影贴图宽度（像素，必须 &gt; 0）
     * @param height 阴影贴图高度（像素，必须 &gt; 0）
     */
    public ShadowMapFramebuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("阴影贴图尺寸必须为正数: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.fboId = 0;
        this.depthTextureId = 0;
        this.colorTextureId = 0;
    }

    /**
     * 创建阴影贴图 FBO 及其纹理附件。
     *
     * <p>
     * 步骤：
     * <ol>
     * <li>生成 FBO 并绑定</li>
     * <li>生成深度纹理（{@code GL_DEPTH_COMPONENT32F}），并启用硬件阴影比较：
     * {@code GL_TEXTURE_COMPARE_MODE = GL_COMPARE_R_TO_TEXTURE}，
     * {@code GL_TEXTURE_COMPARE_FUNC = GL_LEQUAL}，
     * 使着色器使用 {@code sampler2DShadow} 采样时自动返回可见性</li>
     * <li>生成颜色纹理（{@code GL_R8}）</li>
     * <li>将深度/颜色纹理附加到 FBO 对应的附件点</li>
     * <li>仅绘制到颜色附件（深度作为渲染目标，不读取）</li>
     * <li>检查 FBO 完整性</li>
     * </ol>
     * </p>
     *
     * @throws RuntimeException 如果不在渲染线程调用，或 FBO 完整性检查失败
     */
    public void create() {
        checkRenderThread();

        // 生成 FBO
        fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);

        // 创建深度纹理（启用阴影比较模式，供 sampler2DShadow 采样）
        depthTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTextureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
        // 阴影采样必须使用最近邻过滤（硬件比较后不过滤）
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // 启用硬件阴影比较：采样时自动将纹理中存储的深度与参考值比较
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL14.GL_COMPARE_R_TO_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        // 附加到深度附件点
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTextureId, 0);

        // 创建颜色纹理（R8，用于存储阴影柔化等附加数据）
        colorTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTextureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, width, height, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, colorTextureId, 0);

        // 仅绘制到颜色附件 0
        GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);

        // 检查 FBO 完整性
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            String msg = "阴影贴图 FBO 创建失败，状态码: 0x" + Integer.toHexString(status);
            UHDFast.LOGGER.error(msg);
            destroy();
            throw new RuntimeException(msg);
        }

        UHDFast.LOGGER.debug("阴影贴图 FBO 创建成功: {}x{} (id={}, 估算内存={} 字节)",
                width, height, fboId, getEstimatedMemoryBytes());
    }

    /**
     * 绑定阴影贴图 FBO 进行渲染。
     *
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public void bind() {
        checkRenderThread();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
    }

    /**
     * 解绑阴影贴图 FBO，将绑定的 FBO 恢复为默认（0）。
     *
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public void unbind() {
        checkRenderThread();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * 将阴影深度纹理绑定到指定纹理单元，供着色器采样。
     *
     * <p>
     * 由于深度纹理启用了比较模式，着色器中应使用 {@code sampler2DShadow} 和
     * {@code texture()} 函数，采样结果为 [0,1] 的可见性值。
     * </p>
     *
     * @param textureUnit 纹理单元（如 {@code GL13.GL_TEXTURE0}）
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public void bindDepthTexture(int textureUnit) {
        checkRenderThread();
        GL13.glActiveTexture(textureUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTextureId);
    }

    /**
     * 调整阴影贴图尺寸并重建 FBO。
     *
     * <p>
     * 先删除旧 FBO 的所有 GL 资源，再以新尺寸重新创建。
     * </p>
     *
     * @param newWidth  新宽度（像素，必须 &gt; 0）
     * @param newHeight 新高度（像素，必须 &gt; 0）
     * @throws IllegalArgumentException 如果新尺寸非正数
     * @throws RuntimeException         如果不在渲染线程调用或重建后完整性检查失败
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException("阴影贴图新尺寸必须为正数: " + newWidth + "x" + newHeight);
        }
        if (newWidth == width && newHeight == height) {
            return; // 尺寸未变，无需重建
        }
        UHDFast.LOGGER.debug("阴影贴图 FBO 重建: {}x{} -> {}x{}", width, height, newWidth, newHeight);
        destroy();
        this.width = newWidth;
        this.height = newHeight;
        create();
    }

    /**
     * 删除所有 GL 资源（FBO、深度纹理、颜色纹理）。
     *
     * <p>
     * 调用后对象处于未初始化状态，需重新调用 {@link #create()} 才能使用。
     * 重复调用是安全的。
     * </p>
     */
    public void destroy() {
        checkRenderThread();
        if (colorTextureId != 0) {
            GL11.glDeleteTextures(colorTextureId);
            colorTextureId = 0;
        }
        if (depthTextureId != 0) {
            GL11.glDeleteTextures(depthTextureId);
            depthTextureId = 0;
        }
        if (fboId != 0) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
    }

    /**
     * 估算 FBO 占用的 GPU 显存字节数。
     *
     * <p>
     * 计算公式：{@code width * height * (深度4字节 + 颜色R8 1字节)}。
     * </p>
     *
     * @return 估算的显存占用（字节）
     */
    public long getEstimatedMemoryBytes() {
        return (long) width * (long) height * (DEPTH_BYTES_PER_PIXEL + COLOR_BYTES_PER_PIXEL);
    }

    /** @return 阴影贴图宽度（像素） */
    public int getWidth() {
        return width;
    }

    /** @return 阴影贴图高度（像素） */
    public int getHeight() {
        return height;
    }

    /** @return FBO 对象 ID */
    public int getFboId() {
        return fboId;
    }

    /** @return 阴影深度纹理 ID */
    public int getDepthTextureId() {
        return depthTextureId;
    }

    /** @return 阴影颜色纹理 ID */
    public int getColorTextureId() {
        return colorTextureId;
    }

    /**
     * 校验当前是否在渲染线程，否则抛出异常。
     */
    private void checkRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new RuntimeException("阴影贴图操作必须在渲染线程执行");
        }
    }
}
