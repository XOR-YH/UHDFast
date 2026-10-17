package com.xor.uhdfast.shaderpack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/**
 * 光影渲染管线协调器（单例）。
 *
 * <p>
 * 协调光影各渲染 pass（G-Buffer、阴影、composite、final）的执行顺序，
 * 管理 G-Buffer FBO、阴影贴图 FBO 以及光影程序缓存。该类是 UHDFast 光影加载器
 * 与 Minecraft 主渲染管线之间的桥接层，由 Mixin 在 {@code LevelRenderer.renderLevel}
 * 和 {@code GameRenderer.render} 中调用。
 * </p>
 *
 * <p>
 * 典型渲染流程：
 * </p>
 * <ol>
 * <li>{@link #beginShadowPass()} —— 在 GameRenderer.render HEAD 注入，绑定阴影 FBO</li>
 * <li>{@link #endShadowPass()} —— 在 GameRenderer.render RETURN 注入，解绑阴影 FBO</li>
 * <li>{@link #beginGBufferPass()} —— 在 LevelRenderer.renderLevel HEAD 注入，绑定
 * G-Buffer FBO</li>
 * <li>场景渲染期间，通过 {@link #bindShaderProgram(String)} 绑定各 gbuffer 程序</li>
 * <li>{@link #endGBufferPass()} —— 在 LevelRenderer.renderLevel RETURN 注入，解绑
 * G-Buffer FBO</li>
 * <li>{@link #renderCompositePass(int)} —— 执行后处理 pass</li>
 * <li>{@link #renderFinalPass()} —— 输出最终画面到默认 FBO</li>
 * </ol>
 *
 * <p>
 * <b>线程安全性：</b>非线程安全，所有方法必须在渲染线程调用。
 * GL 操作通过 {@link RenderSystem#isOnRenderThread()} 隐式校验。
 * </p>
 */
public class ShaderRenderPipeline {

    /** 单例实例 */
    private static ShaderRenderPipeline instance;

    /** G-Buffer 帧缓冲（多渲染目标） */
    private GBufferFramebuffer gbuffer;

    /** 阴影贴图帧缓冲 */
    private ShadowMapFramebuffer shadowMap;

    /** 光影程序缓存 */
    private ShaderProgramCache programCache;

    /** 是否已初始化 */
    private boolean initialized;

    /** 阴影 pass 是否启用（用于 GameRendererShaderMixin 标记） */
    private boolean shadowPassEnabled;

    /** 上次使用的窗口宽度，用于检测尺寸变化 */
    private int lastWidth;

    /** 上次使用的窗口高度，用于检测尺寸变化 */
    private int lastHeight;

    /** G-Buffer pass 期间保存的 GL 状态快照 */
    private RenderStateSnapshot gbufferStateSnapshot;

    /** 阴影 pass 期间保存的 GL 状态快照 */
    private RenderStateSnapshot shadowStateSnapshot;

    /**
     * 私有构造器，禁止外部实例化。
     */
    private ShaderRenderPipeline() {
        this.initialized = false;
        this.shadowPassEnabled = false;
        this.lastWidth = 0;
        this.lastHeight = 0;
    }

    /**
     * 获取单例实例。
     *
     * @return {@link ShaderRenderPipeline} 单例
     */
    public static ShaderRenderPipeline getInstance() {
        if (instance == null) {
            synchronized (ShaderRenderPipeline.class) {
                if (instance == null) {
                    instance = new ShaderRenderPipeline();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化渲染管线。
     *
     * <p>
     * 创建 G-Buffer FBO（按指定尺寸）、阴影贴图 FBO（按配置的分辨率）以及
     * 光影程序缓存。若之前已初始化，会先销毁旧资源再重新创建。
     * </p>
     *
     * @param width  G-Buffer 宽度（像素，必须 &gt; 0）
     * @param height G-Buffer 高度（像素，必须 &gt; 0）
     */
    public void initialize(int width, int height) {
        if (width <= 0 || height <= 0) {
            UHDFast.LOGGER.warn("ShaderRenderPipeline 初始化尺寸非法: {}x{}", width, height);
            return;
        }
        // 若已初始化，先销毁旧资源
        if (initialized) {
            destroy();
        }
        try {
            // 创建 G-Buffer FBO
            gbuffer = new GBufferFramebuffer(width, height);
            gbuffer.create();

            // 创建阴影贴图 FBO（使用配置中的分辨率）
            int shadowRes = UHDFastConfig.shadowMapResolution.get();
            shadowMap = new ShadowMapFramebuffer(shadowRes, shadowRes);
            shadowMap.create();

            // 创建程序缓存
            programCache = new ShaderProgramCache();

            lastWidth = width;
            lastHeight = height;
            initialized = true;
            shadowPassEnabled = false;

            UHDFast.LOGGER.info("ShaderRenderPipeline 初始化完成: G-Buffer={}x{}, 阴影贴图={}x{}",
                    width, height, shadowRes, shadowRes);
        } catch (Exception e) {
            UHDFast.LOGGER.error("ShaderRenderPipeline 初始化失败: {}", e.getMessage(), e);
            initialized = false;
        }
    }

    /**
     * 确保渲染管线已初始化。
     *
     * <p>
     * 若未初始化，使用当前 Minecraft 窗口尺寸进行初始化。
     * 若已初始化，则检查窗口尺寸是否变化，必要时重建 FBO。
     * </p>
     */
    public void ensureInitialized() {
        if (initialized) {
            // 检查窗口尺寸是否变化
            try {
                Minecraft mc = Minecraft.getInstance();
                int width = mc.getWindow().getWidth();
                int height = mc.getWindow().getHeight();
                checkResize(width, height);
            } catch (Exception e) {
                UHDFast.LOGGER.debug("检查窗口尺寸变化失败: {}", e.getMessage());
            }
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            int width = mc.getWindow().getWidth();
            int height = mc.getWindow().getHeight();
            initialize(width, height);
        } catch (Exception e) {
            UHDFast.LOGGER.error("无法初始化 ShaderRenderPipeline: {}", e.getMessage(), e);
        }
    }

    /**
     * 检测尺寸变化并重建 FBO。
     *
     * @param width  当前窗口宽度
     * @param height 当前窗口高度
     */
    public void checkResize(int width, int height) {
        if (!initialized || width <= 0 || height <= 0) {
            return;
        }
        if (width != lastWidth || height != lastHeight) {
            UHDFast.LOGGER.debug("ShaderRenderPipeline 尺寸变化: {}x{} -> {}x{}, 重建 G-Buffer FBO",
                    lastWidth, lastHeight, width, height);
            try {
                gbuffer.resize(width, height);
                lastWidth = width;
                lastHeight = height;
            } catch (Exception e) {
                UHDFast.LOGGER.error("G-Buffer 重建失败: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 开始 G-Buffer pass。
     *
     * <p>
     * 保存当前 GL 状态，绑定 G-Buffer FBO，设置视口为 G-Buffer 尺寸，
     * 并启用深度测试。后续场景渲染将写入 G-Buffer 的多个颜色附件和深度附件。
     * </p>
     */
    public void beginGBufferPass() {
        if (!initialized) {
            return;
        }
        try {
            gbufferStateSnapshot = RenderStateSnapshot.capture();
            gbuffer.bind();
            // 设置视口为 G-Buffer 尺寸
            GL11.glViewport(0, 0, gbuffer.getWidth(), gbuffer.getHeight());
            // 启用深度测试
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            UHDFast.LOGGER.trace("G-Buffer pass 开始");
        } catch (Exception e) {
            UHDFast.LOGGER.error("G-Buffer pass 启动失败: {}", e.getMessage(), e);
            gbufferStateSnapshot = null;
        }
    }

    /**
     * 结束 G-Buffer pass。
     *
     * <p>
     * 解绑 G-Buffer FBO，恢复进入 G-Buffer pass 前的 GL 状态。
     * </p>
     */
    public void endGBufferPass() {
        if (!initialized || gbufferStateSnapshot == null) {
            return;
        }
        try {
            gbuffer.unbind();
            gbufferStateSnapshot.restore();
            UHDFast.LOGGER.trace("G-Buffer pass 结束");
        } catch (Exception e) {
            UHDFast.LOGGER.error("G-Buffer pass 结束失败: {}", e.getMessage(), e);
        } finally {
            gbufferStateSnapshot = null;
        }
    }

    /**
     * 绑定指定名称的光影程序。
     *
     * <p>
     * 流程：
     * </p>
     * <ol>
     * <li>检查程序缓存中是否已记录该程序的编译错误，若有则直接返回（不改变当前程序）</li>
     * <li>若程序未缓存，调用 {@link ShaderProgramCompiler#compileProgram} 编译并缓存</li>
     * <li>编译失败时记录错误并返回（不改变当前程序）</li>
     * <li>编译成功则 {@code glUseProgram} 激活程序，并通过
     * {@link ProgramUniforms#updateCommonUniforms} 上传 Minecraft 相机参数</li>
     * </ol>
     *
     * <p>
     * 若编译失败或程序不存在，当前 GL 程序不会被改变（回退到默认程序）。
     * </p>
     *
     * @param programName 程序名（如 "gbuffers_terrain"、"composite"、"final"）
     */
    public void bindShaderProgram(String programName) {
        if (!initialized || programName == null) {
            return;
        }
        try {
            // 已知编译错误，跳过
            if (programCache.hasError(programName)) {
                return;
            }

            ProgramUniforms uniforms = programCache.getProgram(programName);
            if (uniforms == null) {
                // 缓存未命中，尝试编译
                ShaderPack pack = ShaderPackManager.getInstance().getCurrentPack();
                if (pack == null) {
                    UHDFast.LOGGER.debug("无法绑定光影程序 '{}': 当前无光影包", programName);
                    return;
                }
                Map<String, String> macros = new HashMap<>();
                uniforms = ShaderProgramCompiler.compileProgram(pack, programName, macros);
                if (uniforms == null) {
                    // 编译失败或源码缺失，记录错误避免重复尝试
                    programCache.recordError(programName, "编译失败或源码缺失");
                    return;
                }
                programCache.putProgram(programName, uniforms.getProgramId(), uniforms);
            }

            // 激活程序
            GL20.glUseProgram(uniforms.getProgramId());

            // 上传 Minecraft 相机相关的通用 uniform
            Minecraft mc = Minecraft.getInstance();
            Camera camera = mc.gameRenderer.getMainCamera();
            double camX = camera.getPosition().x();
            double camY = camera.getPosition().y();
            double camZ = camera.getPosition().z();

            // 投影矩阵来自 RenderSystem
            Matrix4f proj;
            try {
                proj = RenderSystem.getProjectionMatrix();
            } catch (Exception e) {
                proj = new Matrix4f();
            }
            if (proj == null) {
                proj = new Matrix4f();
            }

            // 模型视图矩阵：从 RenderSystem 的 model-view stack 读取（NeoForge 1.21.1）
            Matrix4f modelView = new Matrix4f();
            try {
                Matrix4f stackMatrix = RenderSystem.getModelViewStack();
                if (stackMatrix != null) {
                    modelView.set(stackMatrix);
                }
            } catch (Exception e) {
                UHDFast.LOGGER.trace("无法获取 model-view 矩阵，使用单位矩阵: {}", e.getMessage());
            }

            // 计算帧时间计数器（秒）
            float time = 0.0f;
            int tick = 0;
            if (mc.level != null) {
                long gameTick = mc.level.getGameTime();
                // 不使用 mc.getDeltaTracker()（该方法在 1.21.1 不存在），使用 gameTime 作为近似
                time = gameTick / 20.0f;
                tick = (int) gameTick;
            }

            uniforms.updateCommonUniforms(camX, camY, camZ, proj, modelView, time, tick);
        } catch (Exception e) {
            UHDFast.LOGGER.error("绑定光影程序 '{}' 失败: {}", programName, e.getMessage(), e);
            programCache.recordError(programName, e.getMessage());
        }
    }

    /**
     * 解绑当前光影程序，恢复到默认程序。
     *
     * <p>
     * 调用 {@code glUseProgram(0)}，将当前着色器程序恢复为 OpenGL 默认（固定管线）。
     * </p>
     */
    public void unbindShaderProgram() {
        GL20.glUseProgram(0);
    }

    /**
     * 开始阴影 pass。
     *
     * <p>
     * 保存当前 GL 状态，绑定阴影贴图 FBO，设置视口为阴影贴图尺寸，
     * 启用深度测试，并标记 {@link #shadowPassEnabled} 为 {@code true}。
     * 后续场景渲染（如果触发）将写入阴影贴图 FBO 的深度附件。
     * </p>
     */
    public void beginShadowPass() {
        if (!initialized) {
            return;
        }
        try {
            shadowStateSnapshot = RenderStateSnapshot.capture();
            shadowMap.bind();
            GL11.glViewport(0, 0, shadowMap.getWidth(), shadowMap.getHeight());
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            shadowPassEnabled = true;
            UHDFast.LOGGER.trace("阴影 pass 开始");
        } catch (Exception e) {
            UHDFast.LOGGER.error("阴影 pass 启动失败: {}", e.getMessage(), e);
            shadowPassEnabled = false;
            shadowStateSnapshot = null;
        }
    }

    /**
     * 结束阴影 pass。
     *
     * <p>
     * 解绑阴影贴图 FBO，恢复进入阴影 pass 前的 GL 状态，
     * 并标记 {@link #shadowPassEnabled} 为 {@code false}。
     * </p>
     */
    public void endShadowPass() {
        if (!initialized) {
            shadowPassEnabled = false;
            return;
        }
        try {
            shadowMap.unbind();
            if (shadowStateSnapshot != null) {
                shadowStateSnapshot.restore();
            }
            UHDFast.LOGGER.trace("阴影 pass 结束");
        } catch (Exception e) {
            UHDFast.LOGGER.error("阴影 pass 结束失败: {}", e.getMessage(), e);
        } finally {
            shadowStateSnapshot = null;
            shadowPassEnabled = false;
        }
    }

    /**
     * 执行 composite（后处理）pass。
     *
     * <p>
     * 流程：
     * </p>
     * <ol>
     * <li>保存当前 GL 状态</li>
     * <li>将 G-Buffer 的 4 个颜色附件分别绑定到纹理单元 0~3</li>
     * <li>绑定 composite 程序（程序名为 "composite"，passIndex &gt; 0 时为
     * "composite1"、"composite2"...）</li>
     * <li>绘制全屏四边形，由片段着色器完成 G-Buffer 数据的合成</li>
     * <li>解绑程序并恢复 GL 状态</li>
     * </ol>
     *
     * @param passIndex composite pass 索引（0 对应 "composite"，1 对应 "composite1"，依此类推）
     */
    public void renderCompositePass(int passIndex) {
        if (!initialized) {
            return;
        }
        RenderStateSnapshot snapshot = null;
        try {
            snapshot = RenderStateSnapshot.capture();
            // 绑定 G-Buffer 颜色附件作为输入纹理
            for (int i = 0; i < GBufferFramebuffer.COLOR_ATTACHMENT_COUNT; i++) {
                gbuffer.bindColorAttachment(i, GL13.GL_TEXTURE0 + i);
            }

            // 绑定 composite 程序
            String programName = passIndex == 0 ? "composite" : "composite" + passIndex;
            bindShaderProgram(programName);

            // 绘制全屏四边形
            drawFullscreenQuad();

            // 解绑程序
            unbindShaderProgram();
        } catch (Exception e) {
            UHDFast.LOGGER.error("composite pass {} 失败: {}", passIndex, e.getMessage(), e);
        } finally {
            if (snapshot != null) {
                try {
                    snapshot.restore();
                } catch (Exception e) {
                    UHDFast.LOGGER.debug("composite pass 状态恢复失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 执行 final pass，将结果输出到默认 FBO。
     *
     * <p>
     * 与 {@link #renderCompositePass(int)} 类似，但绑定的是 "final" 程序。
     * 该 pass 是光影渲染的最后一步，将最终画面写入 Minecraft 默认 FBO。
     * </p>
     */
    public void renderFinalPass() {
        if (!initialized) {
            return;
        }
        RenderStateSnapshot snapshot = null;
        try {
            snapshot = RenderStateSnapshot.capture();
            // 绑定 G-Buffer 颜色附件作为输入纹理
            for (int i = 0; i < GBufferFramebuffer.COLOR_ATTACHMENT_COUNT; i++) {
                gbuffer.bindColorAttachment(i, GL13.GL_TEXTURE0 + i);
            }

            // 绑定 final 程序
            bindShaderProgram("final");

            // 绘制全屏四边形
            drawFullscreenQuad();

            // 解绑程序
            unbindShaderProgram();
        } catch (Exception e) {
            UHDFast.LOGGER.error("final pass 失败: {}", e.getMessage(), e);
        } finally {
            if (snapshot != null) {
                try {
                    snapshot.restore();
                } catch (Exception e) {
                    UHDFast.LOGGER.debug("final pass 状态恢复失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 销毁所有 GL 资源，重置为未初始化状态。
     *
     * <p>
     * 依次清理程序缓存、G-Buffer FBO 和阴影贴图 FBO。
     * 重复调用是安全的。
     * </p>
     */
    public void destroy() {
        if (!initialized) {
            return;
        }
        try {
            if (programCache != null) {
                programCache.clear();
                programCache = null;
            }
            if (gbuffer != null) {
                gbuffer.destroy();
                gbuffer = null;
            }
            if (shadowMap != null) {
                shadowMap.destroy();
                shadowMap = null;
            }
            initialized = false;
            shadowPassEnabled = false;
            gbufferStateSnapshot = null;
            shadowStateSnapshot = null;
            UHDFast.LOGGER.info("ShaderRenderPipeline 已销毁");
        } catch (Exception e) {
            UHDFast.LOGGER.error("ShaderRenderPipeline 销毁失败: {}", e.getMessage(), e);
        }
    }

    /**
     * @return 渲染管线是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * @return 阴影 pass 是否启用（即当前是否处于阴影 pass 渲染阶段）
     */
    public boolean isShadowPassEnabled() {
        return shadowPassEnabled;
    }

    /**
     * 绘制覆盖整个屏幕的四边形。
     *
     * <p>
     * 使用固定管线的 {@code glBegin/glEnd} 绘制，覆盖 NDC 的 [-1,1] 范围，
     * 并附带 [0,1] 的纹理坐标。绘制前会临时禁用深度测试和混合，
     * 确保全屏四边形覆盖整个视口。
     * </p>
     */
    private void drawFullscreenQuad() {
        // 临时禁用深度测试和混合以保证全屏四边形正确绘制
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        GL11.glBegin(GL11.GL_QUADS);
        // 左下角
        GL11.glTexCoord2f(0.0f, 0.0f);
        GL11.glVertex2f(-1.0f, -1.0f);
        // 右下角
        GL11.glTexCoord2f(1.0f, 0.0f);
        GL11.glVertex2f(1.0f, -1.0f);
        // 右上角
        GL11.glTexCoord2f(1.0f, 1.0f);
        GL11.glVertex2f(1.0f, 1.0f);
        // 左上角
        GL11.glTexCoord2f(0.0f, 1.0f);
        GL11.glVertex2f(-1.0f, 1.0f);
        GL11.glEnd();
    }
}
