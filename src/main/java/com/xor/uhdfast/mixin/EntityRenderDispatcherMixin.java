package com.xor.uhdfast.mixin;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.UHDFastConfig;
import com.xor.uhdfast.gpu.EntityBatchManager;
import com.xor.uhdfast.gpu.XeInstancedRenderer;
import com.xor.uhdfast.optimizer.EntityLODManager;
import com.xor.uhdfast.optimizer.EntityOptimizer;
import com.xor.uhdfast.optimizer.XeEntityOptimizer;
import com.xor.uhdfast.optimizer.XeSpatialEntityOptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void onRenderEntity(E entity, double x, double y, double z, float partialTick,
            float extraFlag, com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        if (entity == null) {
            return;
        }

        // ====== 实体剔除：距离/视锥/类型 ======
        // 优先使用 EntityOptimizer（已有完整的距离+类型剔除逻辑）
        if (UHDFastConfig.enableEntityRenderOptimization.get()) {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.world.entity.player.Player player = mc.player;
            if (player != null) {
                // 使用 EntityOptimizer 的完整剔除逻辑
                if (!EntityOptimizer.shouldRenderEntity(entity, player.getX(), player.getY(), player.getZ())) {
                    return; // 被剔除，跳过渲染（不 cancel，给 GPU 实例化机会）
                }
            }
        }

        // 双层剔除：XeEntityOptimizer 和 XeSpatialEntityOptimizer
        // 只有两个优化器都允许渲染时，才继续渲染
        if (!XeEntityOptimizer.shouldRenderEntity(entity)) {
            ci.cancel();
            return;
        }

        if (!XeSpatialEntityOptimizer.shouldRenderEntity(entity)) {
            ci.cancel();
            return;
        }

        EntityLODManager.applyLOD(entity, partialTick);

        // GPU实例化渲染集成：将适合的实体收集到EntityBatchManager，跳过原始渲染
        if (XeInstancedRenderer.isActive()) {
            try {
                boolean collected = EntityBatchManager.getInstance().collectEntity(entity);
                if (collected) {
                    ci.cancel(); // 跳过原始渲染，由flushBatch统一处理
                }
            } catch (Exception e) {
                // GPU实例化渲染失败，回退到普通渲染路径
                UHDFast.LOGGER.debug("Entity batch collection failed, using normal render: {}", e.getMessage());
            }
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private <E extends Entity> void onRenderEntityReturn(E entity, double x, double y, double z, float partialTick,
            float extraFlag, com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource bufferSource, int packedLight, CallbackInfo ci) {
        EntityLODManager.resetShadow();
    }
}
