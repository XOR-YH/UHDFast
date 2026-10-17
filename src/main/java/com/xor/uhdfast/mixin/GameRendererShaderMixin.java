package com.xor.uhdfast.mixin;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import com.xor.uhdfast.shaderpack.ShaderPackManager;
import com.xor.uhdfast.shaderpack.ShaderRenderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 光影阴影 pass 在 {@code GameRenderer.render} 中的注入 Mixin。
 *
 * <p>
 * 该 Mixin 在 {@code GameRenderer.render} 方法的 HEAD 和 RETURN 处注入，
 * 用于标记和准备阴影 pass。由于阴影 pass 需要渲染整个场景（从光源视角），
 * 实际的阴影场景渲染在 {@link LevelRendererShaderMixin} 中通过
 * {@link ShaderRenderPipeline#isShadowPassEnabled()} 判定后执行。
 * </p>
 *
 * <p>
 * 注入逻辑：
 * </p>
 * <ul>
 * <li><b>HEAD 注入：</b>若光影包已加载且 {@link UHDFastConfig#enableShadowRendering}
 * 为 {@code true}，调用 {@link ShaderRenderPipeline#beginShadowPass()}
 * 绑定阴影贴图 FBO 并标记阴影 pass 启用</li>
 * <li><b>RETURN 注入：</b>若开始了阴影 pass，调用
 * {@link ShaderRenderPipeline#endShadowPass()}
 * 解绑阴影贴图 FBO 并恢复 GL 状态</li>
 * </ul>
 *
 * <p>
 * 该类与项目已有的 {@code GameRendererMixin} 相互独立，仅负责阴影 pass 的注入逻辑。
 * </p>
 */
@Mixin(GameRenderer.class)
public class GameRendererShaderMixin {

    /** 标记本次 render 调用是否启动了阴影 pass，用于 RETURN 注入判断是否需要结束 */
    private boolean shadowPassStarted = false;

    /**
     * 在 {@code render} 方法 HEAD 处注入。
     *
     * <p>
     * 检查以下条件是否全部满足：
     * </p>
     * <ol>
     * <li>{@link ShaderPackManager#isShaderPackLoaded()} 返回
     * {@code true}（光影包已加载且启用）</li>
     * <li>{@link UHDFastConfig#enableShadowRendering} 返回
     * {@code true}（配置允许阴影渲染）</li>
     * <li>{@link ShaderRenderPipeline#isInitialized()} 返回
     * {@code true}（渲染管线已初始化）</li>
     * </ol>
     * 若条件满足，调用 {@link ShaderRenderPipeline#beginShadowPass()} 启动阴影 pass，
     * 并标记 {@code shadowPassStarted = true}。
     *
     * @param deltaTracker       帧增量追踪器
     * @param renderBlockOutline 是否渲染方块轮廓
     * @param ci                 回调信息（Mixin 注入参数）
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(DeltaTracker deltaTracker, boolean renderBlockOutline, CallbackInfo ci) {
        shadowPassStarted = false;
        try {
            // 检查光影包是否已加载
            if (!ShaderPackManager.getInstance().isShaderPackLoaded()) {
                return;
            }
            // 检查配置是否启用阴影渲染
            if (!UHDFastConfig.enableShadowRendering.get()) {
                return;
            }
            ShaderRenderPipeline pipeline = ShaderRenderPipeline.getInstance();
            // 确保渲染管线已初始化
            pipeline.ensureInitialized();
            if (!pipeline.isInitialized()) {
                UHDFast.LOGGER.debug("光影渲染管线未初始化，跳过阴影 pass");
                return;
            }
            // 启动阴影 pass：保存状态、绑定阴影贴图 FBO
            pipeline.beginShadowPass();
            shadowPassStarted = pipeline.isShadowPassEnabled();
        } catch (Exception e) {
            UHDFast.LOGGER.error("阴影 pass 启动失败: {}", e.getMessage(), e);
            shadowPassStarted = false;
        }
    }

    /**
     * 在 {@code render} 方法 RETURN 处注入。
     *
     * <p>
     * 若 HEAD 注入启动了阴影 pass（{@code shadowPassStarted == true}），
     * 调用 {@link ShaderRenderPipeline#endShadowPass()} 结束阴影 pass，
     * 解绑阴影贴图 FBO 并恢复 GL 状态。
     * </p>
     *
     * @param deltaTracker       帧增量追踪器
     * @param renderBlockOutline 是否渲染方块轮廓
     * @param ci                 回调信息（Mixin 注入参数）
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(DeltaTracker deltaTracker, boolean renderBlockOutline, CallbackInfo ci) {
        if (!shadowPassStarted) {
            return;
        }
        try {
            ShaderRenderPipeline pipeline = ShaderRenderPipeline.getInstance();
            // 结束阴影 pass：解绑 FBO、恢复状态
            pipeline.endShadowPass();
        } catch (Exception e) {
            UHDFast.LOGGER.error("阴影 pass 结束失败: {}", e.getMessage(), e);
        } finally {
            shadowPassStarted = false;
        }
    }
}
