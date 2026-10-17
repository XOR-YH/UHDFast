package com.xor.uhdfast.mixin;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.shaderpack.ShaderPackManager;
import com.xor.uhdfast.shaderpack.ShaderRenderPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 光影渲染管线在 {@code LevelRenderer.renderLevel} 中的注入 Mixin。
 * 
 * 注意：光影功能当前已关闭。所有 @Inject 方法以 `if (true) return;` 开头，
 * 这些死代码是故意的，用于在保留代码结构的同时禁用功能。
 * 重新启用时，删除这些 return 语句即可。
 * 
 * 该 Mixin 在 {@code LevelRenderer.renderLevel} 方法的 HEAD 和 RETURN 处注入，
 * 协调 G-Buffer pass 与 composite/final pass 的执行：
 * </p>
 * <ul>
 * <li><b>HEAD 注入：</b>检查光影包是否已加载，若是则确保渲染管线已初始化，
 * 并调用 {@link ShaderRenderPipeline#beginGBufferPass()} 启动 G-Buffer pass，
 * 使后续场景渲染写入 G-Buffer FBO 而非默认 FBO</li>
 * <li><b>RETURN 注入：</b>调用 {@link ShaderRenderPipeline#endGBufferPass()} 结束
 * G-Buffer pass，
 * 然后执行 {@link ShaderRenderPipeline#renderCompositePass(int)} 与
 * {@link ShaderRenderPipeline#renderFinalPass()} 完成后处理与最终输出</li>
 * </ul>
 */
@SuppressWarnings("all")
@Mixin(LevelRenderer.class)
public class LevelRendererShaderMixin {

    /**
     * 在 {@code renderLevel} 方法 HEAD 处注入。
     *
     * <p>
     * 检查光影包是否已加载并启用，若是：
     * </p>
     * <ol>
     * <li>调用 {@link ShaderRenderPipeline#ensureInitialized()} 确保渲染管线已初始化
     * （首次调用时使用当前窗口尺寸初始化，后续调用会检查窗口尺寸变化）</li>
     * <li>调用 {@link ShaderRenderPipeline#beginGBufferPass()} 启动 G-Buffer pass，
     * 保存当前 GL 状态并绑定 G-Buffer FBO</li>
     * </ol>
     *
     * @param deltaTracker          帧增量追踪器
     * @param renderBlockOutline    是否渲染方块轮廓
     * @param camera                当前相机
     * @param gameRenderer          GameRenderer 实例
     * @param lightTexture          光照贴图
     * @param projectionMatrix      投影矩阵
     * @param inverseRotationMatrix 反向旋转矩阵
     * @param ci                    回调信息（Mixin 注入参数）
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderLevelStart(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projectionMatrix, Matrix4f inverseRotationMatrix, CallbackInfo ci) {
        // [光影功能已关闭] 启用时删除此行
        if (true) return;
        try {
            if (!ShaderPackManager.getInstance().isShaderPackLoaded()) {
                return;
            }
            ShaderRenderPipeline pipeline = ShaderRenderPipeline.getInstance();
            // 确保渲染管线已初始化（首次或窗口尺寸变化时）
            pipeline.ensureInitialized();
            if (!pipeline.isInitialized()) {
                UHDFast.LOGGER.debug("光影渲染管线未初始化，跳过 G-Buffer pass");
                return;
            }
            // 启动 G-Buffer pass：保存状态、绑定 G-Buffer FBO
            pipeline.beginGBufferPass();
        } catch (Exception e) {
            UHDFast.LOGGER.error("光影 G-Buffer pass 启动失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 在 {@code renderLevel} 方法 RETURN 处注入。
     *
     * <p>
     * 若启用了光影，依次执行：
     * </p>
     * <ol>
     * <li>{@link ShaderRenderPipeline#endGBufferPass()} 结束 G-Buffer pass，
     * 解绑 G-Buffer FBO 并恢复 GL 状态</li>
     * <li>{@link ShaderRenderPipeline#renderCompositePass(int)} 执行第 0 个 composite
     * pass，
     * 从 G-Buffer 读取数据进行后处理</li>
     * <li>{@link ShaderRenderPipeline#renderFinalPass()} 执行 final pass，
     * 将最终画面写入默认 FBO</li>
     * </ol>
     *
     * @param deltaTracker          帧增量追踪器
     * @param renderBlockOutline    是否渲染方块轮廓
     * @param camera                当前相机
     * @param gameRenderer          GameRenderer 实例
     * @param lightTexture          光照贴图
     * @param projectionMatrix      投影矩阵
     * @param inverseRotationMatrix 反向旋转矩阵
     * @param ci                    回调信息（Mixin 注入参数）
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderLevelEnd(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projectionMatrix, Matrix4f inverseRotationMatrix, CallbackInfo ci) {
        // [光影功能已关闭] 启用时删除此行
        if (true) return;
        try {
            if (!ShaderPackManager.getInstance().isShaderPackLoaded()) {
                return;
            }
            ShaderRenderPipeline pipeline = ShaderRenderPipeline.getInstance();
            if (!pipeline.isInitialized()) {
                return;
            }
            // 结束 G-Buffer pass：解绑 FBO、恢复状态
            pipeline.endGBufferPass();
            // 执行 composite pass 0：从 G-Buffer 读取数据进行后处理
            pipeline.renderCompositePass(0);
            // 执行 final pass：将最终画面输出到默认 FBO
            pipeline.renderFinalPass();
        } catch (Exception e) {
            UHDFast.LOGGER.error("光影 G-Buffer pass 结束或后处理失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 在 {@code renderLevel} 中首次调用 {@code renderSectionLayer}（地形渲染层）前注入。
     *
     * <p>
     * {@code renderSectionLayer} 在 {@code renderLevel} 中被多次调用
     * （SOLID、CUTOUT_MIPPED、CUTOUT、TRANSLUCENT、TRIPWIRE），
     * 这里通过 {@code ordinal = 0} 仅在第一次调用（SOLID 层，地形渲染的开始）前注入，
     * 绑定 {@code gbuffers_terrain} 程序。后续阶段调用 {@code bindShaderProgram}
     * 时会通过 {@code glUseProgram} 自动替换当前程序，确保下一阶段从干净状态开始。
     * </p>
     *
     * <p>
     * 目标方法签名：
     * {@code private void renderSectionLayer(RenderType renderType, double x, double y, double z, Matrix4f frustrumMatrix, Matrix4f projectionMatrix)}
     * </p>
     *
     * @param deltaTracker          帧增量追踪器
     * @param renderBlockOutline    是否渲染方块轮廓
     * @param camera                当前相机
     * @param gameRenderer          GameRenderer 实例
     * @param lightTexture          光照贴图
     * @param projectionMatrix      投影矩阵
     * @param inverseRotationMatrix 反向旋转矩阵
     * @param ci                    回调信息（Mixin 注入参数）
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V", ordinal = 0))
    private void onTerrainRenderStage(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projectionMatrix, Matrix4f inverseRotationMatrix, CallbackInfo ci) {
        // [光影功能已关闭] 启用时删除此行
        if (true) return;
        try {
            if (!ShaderPackManager.getInstance().isShaderPackLoaded()) {
                return;
            }
            ShaderRenderPipeline pipeline = ShaderRenderPipeline.getInstance();
            if (!pipeline.isInitialized()) {
                return;
            }
            // 绑定 gbuffers_terrain 程序：地形渲染使用
            pipeline.bindShaderProgram("gbuffers_terrain");
        } catch (Exception e) {
            UHDFast.LOGGER.error("绑定 gbuffers_terrain 程序失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 在 {@code renderLevel} 中首次调用 {@code renderEntity}（实体渲染）前注入。
     *
     * <p>
     * {@code renderEntity} 在 {@code renderLevel} 的实体渲染循环中被多次调用
     * （每个可见实体一次），这里通过 {@code ordinal = 0} 仅在第一次调用前注入，
     * 绑定 {@code gbuffers_entities} 程序。后续实体的渲染会复用该程序，
     * 下一渲染阶段调用 {@code bindShaderProgram} 时会自动替换。
     * </p>
     *
     * <p>
     * 目标方法签名：
     * {@code private void renderEntity(Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource)}
     * </p>
     *
     * @param deltaTracker          帧增量追踪器
     * @param renderBlockOutline    是否渲染方块轮廓
     * @param camera                当前相机
     * @param gameRenderer          GameRenderer 实例
     * @param lightTexture          光照贴图
     * @param projectionMatrix      投影矩阵
     * @param inverseRotationMatrix 反向旋转矩阵
     * @param ci                    回调信息（Mixin 注入参数）
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V", ordinal = 0))
    private void onEntityRenderStage(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projectionMatrix, Matrix4f inverseRotationMatrix, CallbackInfo ci) {
        // [光影功能已关闭] 启用时删除此行
        if (true) return;
        try {
            if (!ShaderPackManager.getInstance().isShaderPackLoaded()) {
                return;
            }
            ShaderRenderPipeline pipeline = ShaderRenderPipeline.getInstance();
            if (!pipeline.isInitialized()) {
                return;
            }
            // 绑定 gbuffers_entities 程序：实体渲染使用
            pipeline.bindShaderProgram("gbuffers_entities");
        } catch (Exception e) {
            UHDFast.LOGGER.error("绑定 gbuffers_entities 程序失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 在 {@code renderLevel} 中首次调用 {@code renderWorldBorder}（基础渲染阶段）前注入。
     *
     * <p>
     * {@code renderWorldBorder} 在 {@code renderLevel} 末尾被调用（在天气渲染之后），
     * 代表基础/杂项渲染阶段。这里通过 {@code ordinal = 0} 在第一次调用前注入，
     * 绑定 {@code gbuffers_basic} 程序。
     * </p>
     *
     * <p>
     * 目标方法签名：
     * {@code private void renderWorldBorder(Camera camera)}
     * </p>
     *
     * @param deltaTracker          帧增量追踪器
     * @param renderBlockOutline    是否渲染方块轮廓
     * @param camera                当前相机
     * @param gameRenderer          GameRenderer 实例
     * @param lightTexture          光照贴图
     * @param projectionMatrix      投影矩阵
     * @param inverseRotationMatrix 反向旋转矩阵
     * @param ci                    回调信息（Mixin 注入参数）
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderWorldBorder(Lnet/minecraft/client/Camera;)V", ordinal = 0))
    private void onBasicRenderStage(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
            Matrix4f projectionMatrix, Matrix4f inverseRotationMatrix, CallbackInfo ci) {
        // [光影功能已关闭] 启用时删除此行
        if (true) return;
        try {
            if (!ShaderPackManager.getInstance().isShaderPackLoaded()) {
                return;
            }
            ShaderRenderPipeline pipeline = ShaderRenderPipeline.getInstance();
            if (!pipeline.isInitialized()) {
                return;
            }
            // 绑定 gbuffers_basic 程序：基础渲染阶段（世界边界等）使用
            pipeline.bindShaderProgram("gbuffers_basic");
        } catch (Exception e) {
            UHDFast.LOGGER.error("绑定 gbuffers_basic 程序失败: {}", e.getMessage(), e);
        }
    }
}
