package com.xor.uhdfast.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * G-Buffer 帧缓冲管理类
 *
 * <p>
 * 管理多渲染目标（MRT）FBO，用于延迟渲染光影效果。该 FBO 包含 4 个颜色附件：
 * <ul>
 * <li>{@code albedo}（{@code RGB8}）- 漫反射颜色</li>
 * <li>{@code normal}（{@code RGB16F}）- 视空间法线（浮点精度）</li>
 * <li>{@code specular}（{@code RGB8}）- 高光/材质参数</li>
 * <li>{@code lightmap}（{@code RGB8}）- 光照贴图坐标</li>
 * </ul>
 * 同时包含一个 32 位浮点深度纹理，供光影程序读取深度信息。
 * </p>
 *
 * <p>
 * 该类仅负责 GL 资源管理，不涉及绘制调用。所有 GL 操作必须在渲染线程上执行，
 * 调用前会通过 {@link RenderSystem#isOnRenderThread()} 进行线程校验。
 * </p>
 *
 * <p>
 * <b>线程安全性：</b>非线程安全，只能在渲染线程使用。
 * </p>
 */
public class GBufferFramebuffer {

    /** 颜色附件数量（albedo、normal、specular、lightmap） */
    public static final int COLOR_ATTACHMENT_COUNT = 4;

    /** albedo 附件索引（RGB8 漫反射颜色） */
    public static final int INDEX_ALBEDO = 0;
    /** normal 附件索引（RGB16F 视空间法线） */
    public static final int INDEX_NORMAL = 1;
    /** specular 附件索引（RGB8 高光材质） */
    public static final int INDEX_SPECULAR = 2;
    /** lightmap 附件索引（RGB8 光照贴图） */
    public static final int INDEX_LIGHTMAP = 3;

    /** 各颜色附件的内部格式 */
    private static final int[] COLOR_FORMATS = new int[] {
            GL11.GL_RGB8, // albedo
            GL30.GL_RGB16F, // normal
            GL11.GL_RGB8, // specular
            GL11.GL_RGB8 // lightmap
    };

    /** 每个像素占用的字节数（用于内存估算）：RGB8=3 + RGB16F=6 + RGB8=3 + RGB8=3 + 深度32F=4 = 19 */
    private static final int BYTES_PER_PIXEL = 3 + 6 + 3 + 3 + 4;

    /** FBO 对象 ID */
    private int fboId;
    /** 颜色附件纹理 ID 数组（4 个） */
    private int[] colorAttachments;
    /** 深度缓冲纹理 ID */
    private int depthTextureId;
    /** FBO 宽度（像素） */
    private int width;
    /** FBO 高度（像素） */
    private int height;

    /**
     * 构造 G-Buffer 帧缓冲。
     *
     * <p>
     * 构造器只记录尺寸，不分配 GL 资源。需调用 {@link #create()} 完成实际的 FBO 创建。
     * </p>
     *
     * @param width  FBO 宽度（像素，必须 &gt; 0）
     * @param height FBO 高度（像素，必须 &gt; 0）
     */
    public GBufferFramebuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("G-Buffer 尺寸必须为正数: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.fboId = 0;
        this.depthTextureId = 0;
        this.colorAttachments = new int[COLOR_ATTACHMENT_COUNT];
    }

    /**
     * 创建 G-Buffer FBO 及其所有附件。
     *
     * <p>
     * 步骤：
     * <ol>
     * <li>生成 FBO 并绑定</li>
     * <li>生成 4 个颜色附件纹理，按指定格式分配存储并附加到 COLOR_ATTACHMENT0~3</li>
     * <li>生成深度纹理（{@code GL_DEPTH_COMPONENT32F}）并附加到深度附件点</li>
     * <li>设置绘制缓冲为目标 4 个颜色附件</li>
     * <li>检查 FBO 完整性</li>
     * </ol>
     * </p>
     *
     * @throws RuntimeException 如果不在渲染线程调用，或 FBO 创建后完整性检查失败
     */
    public void create() {
        checkRenderThread();

        // 生成 FBO
        fboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);

        // 生成颜色附件纹理
        for (int i = 0; i < COLOR_ATTACHMENT_COUNT; i++) {
            colorAttachments[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorAttachments[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, COLOR_FORMATS[i], width, height, 0,
                    GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            // 过滤参数：albedo/specular/lightmap 用线性，normal 用最近邻避免插值错误
            int filter = (i == INDEX_NORMAL) ? GL11.GL_NEAREST : GL11.GL_LINEAR;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
            // 包裹参数：钳制到边缘避免接缝
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // 附加到 FBO
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                    GL11.GL_TEXTURE_2D, colorAttachments[i], 0);
        }

        // 创建深度纹理（32 位浮点，供光影程序读取线性深度）
        depthTextureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTextureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTextureId, 0);

        // 设置绘制缓冲为 4 个颜色附件
        GL20.glDrawBuffers(new int[] {
                GL30.GL_COLOR_ATTACHMENT0,
                GL30.GL_COLOR_ATTACHMENT1,
                GL30.GL_COLOR_ATTACHMENT2,
                GL30.GL_COLOR_ATTACHMENT3
        });

        // 检查 FBO 完整性
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            String msg = "G-Buffer FBO 创建失败，状态码: 0x" + Integer.toHexString(status);
            UHDFast.LOGGER.error(msg);
            // 清理已分配的资源避免泄漏
            destroy();
            throw new RuntimeException(msg);
        }

        UHDFast.LOGGER.debug("G-Buffer FBO 创建成功: {}x{} (id={}, 估算内存={} 字节)",
                width, height, fboId, getEstimatedMemoryBytes());
    }

    /**
     * 绑定 G-Buffer FBO 进行渲染。
     *
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public void bind() {
        checkRenderThread();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
    }

    /**
     * 解绑 G-Buffer FBO，将绑定的 FBO 恢复为默认（0）。
     *
     * @throws RuntimeException 如果不在渲染线程调用
     */
    public void unbind() {
        checkRenderThread();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * 将指定颜色附件绑定到给定纹理单元，供着色器采样。
     *
     * <p>
     * 调用后着色器可通过 {@code sampler2D} 读取该附件。该方法不会激活纹理单元，
     * 调用者需确保 {@code textureUnit} 已通过 {@code glActiveTexture} 激活或使用
     * {@link RenderSystem#activeTexture(int)} 激活。
     * </p>
     *
     * @param index       颜色附件索引（0~3，对应 albedo/normal/specular/lightmap）
     * @param textureUnit 纹理单元（如 {@code GL13.GL_TEXTURE0}）
     * @throws IllegalArgumentException 如果索引越界
     * @throws RuntimeException         如果不在渲染线程调用
     */
    public void bindColorAttachment(int index, int textureUnit) {
        if (index < 0 || index >= COLOR_ATTACHMENT_COUNT) {
            throw new IllegalArgumentException("颜色附件索引越界: " + index);
        }
        checkRenderThread();
        GL13.glActiveTexture(textureUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorAttachments[index]);
    }

    /**
     * 调整 G-Buffer 尺寸并重建 FBO。
     *
     * <p>
     * 先删除旧 FBO 的所有 GL 资源，再以新尺寸重新创建。重建期间不应进行渲染。
     * </p>
     *
     * @param newWidth  新宽度（像素，必须 &gt; 0）
     * @param newHeight 新高度（像素，必须 &gt; 0）
     * @throws IllegalArgumentException 如果新尺寸非正数
     * @throws RuntimeException         如果不在渲染线程调用或重建后完整性检查失败
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException("G-Buffer 新尺寸必须为正数: " + newWidth + "x" + newHeight);
        }
        if (newWidth == width && newHeight == height) {
            return; // 尺寸未变，无需重建
        }
        UHDFast.LOGGER.debug("G-Buffer FBO 重建: {}x{} -> {}x{}", width, height, newWidth, newHeight);
        destroy();
        this.width = newWidth;
        this.height = newHeight;
        create();
    }

    /**
     * 删除所有 GL 资源（FBO、颜色附件纹理、深度纹理）。
     *
     * <p>
     * 调用后对象处于未初始化状态，需重新调用 {@link #create()} 才能使用。
     * 重复调用是安全的。
     * </p>
     */
    public void destroy() {
        checkRenderThread();
        if (colorAttachments != null) {
            for (int i = 0; i < colorAttachments.length; i++) {
                if (colorAttachments[i] != 0) {
                    GL11.glDeleteTextures(colorAttachments[i]);
                    colorAttachments[i] = 0;
                }
            }
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
     * 计算公式：{@code width * height * BYTES_PER_PIXEL}，
     * 其中每像素字节数为 4 个颜色附件与深度附件之和（19 字节）。
     * </p>
     *
     * @return 估算的显存占用（字节）
     */
    public long getEstimatedMemoryBytes() {
        return (long) width * (long) height * BYTES_PER_PIXEL;
    }

    /** @return FBO 宽度（像素） */
    public int getWidth() {
        return width;
    }

    /** @return FBO 高度（像素） */
    public int getHeight() {
        return height;
    }

    /** @return FBO 对象 ID */
    public int getFboId() {
        return fboId;
    }

    /**
     * 获取指定颜色附件的纹理 ID。
     *
     * @param index 颜色附件索引（0~3）
     * @return 纹理 ID
     * @throws IllegalArgumentException 如果索引越界
     */
    public int getColorAttachment(int index) {
        if (index < 0 || index >= COLOR_ATTACHMENT_COUNT) {
            throw new IllegalArgumentException("颜色附件索引越界: " + index);
        }
        return colorAttachments[index];
    }

    /** @return 深度纹理 ID */
    public int getDepthTextureId() {
        return depthTextureId;
    }

    /**
     * 校验当前是否在渲染线程，否则抛出异常。
     */
    private void checkRenderThread() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new RuntimeException("G-Buffer 操作必须在渲染线程执行");
        }
    }
}
