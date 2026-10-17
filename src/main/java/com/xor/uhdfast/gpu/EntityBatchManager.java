package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entity Batch Manager - Manages entity batching for GPU instanced rendering
 * Collects entities per frame, groups them by type, and triggers GPU instanced
 * rendering
 * when the same type entity count >= threshold (3).
 */
public class EntityBatchManager {

    private static final int BATCH_THRESHOLD = 3;
    private static EntityBatchManager instance;

    // Entity batches grouped by entity type
    private final Map<EntityType<?>, List<Entity>> entityBatches;

    // Entities that should not be instanced (bosses, players, etc.)
    private final List<Entity> passthroughEntities;

    // Statistics
    private static final AtomicInteger totalBatches = new AtomicInteger(0);
    private static final AtomicInteger totalInstancedEntities = new AtomicInteger(0);
    private static final AtomicInteger totalSavedDrawCalls = new AtomicInteger(0);

    private int frameBatchCount;
    private int frameInstancedEntityCount;
    private int frameSavedDrawCalls;

    // 优化：缓存可复用的实例数据数组，避免每帧分配
    private float[] cachedInstancePositions = new float[256];
    private float[] cachedInstanceColors = new float[256];
    private float[] cachedInstanceScales = new float[128];

    private EntityBatchManager() {
        entityBatches = new HashMap<>();
        passthroughEntities = new ArrayList<>();
        resetFrameStats();
    }

    public static synchronized EntityBatchManager getInstance() {
        if (instance == null) {
            instance = new EntityBatchManager();
        }
        return instance;
    }

    /**
     * Collect an entity for batched rendering
     * 
     * @param entity The entity to collect
     * @return true if the entity was collected for batching, false if it should use
     *         normal rendering
     */
    public boolean collectEntity(Entity entity) {
        if (entity == null) {
            return false;
        }

        // Check if GPU instanced rendering is active
        if (!XeInstancedRenderer.isActive()) {
            return false;
        }

        // Check if entity is suitable for instancing
        if (!isSuitableForInstancing(entity)) {
            passthroughEntities.add(entity);
            return false;
        }

        // Add entity to appropriate batch
        EntityType<?> type = entity.getType();
        List<Entity> batch = entityBatches.computeIfAbsent(type, k -> new ArrayList<>());
        batch.add(entity);

        return true;
    }

    /**
     * Check if an entity is suitable for GPU instanced rendering
     */
    private boolean isSuitableForInstancing(Entity entity) {
        // Players should not be instanced (unique rendering requirements)
        if (entity instanceof Player) {
            return false;
        }

        // Boss entities should not be instanced (unique models/effects)
        if (entity instanceof WitherBoss || entity instanceof EnderDragonPart) {
            return false;
        }

        // Entities with custom names are likely unique/boss entities
        if (entity.hasCustomName()) {
            return false;
        }

        return true;
    }

    /**
     * Flush all collected entity batches - called at the end of each frame
     * Processes batches and triggers GPU instanced rendering for groups >=
     * threshold
     */
    public void flushBatch() {
        if (!XeInstancedRenderer.isActive()) {
            reset();
            return;
        }

        try {
            frameBatchCount = 0;
            frameInstancedEntityCount = 0;
            frameSavedDrawCalls = 0;

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.getEntityRenderDispatcher() == null) {
                reset();
                return;
            }

            // Process each entity type batch
            for (Map.Entry<EntityType<?>, List<Entity>> entry : entityBatches.entrySet()) {
                List<Entity> batch = entry.getValue();

                if (batch.size() >= BATCH_THRESHOLD) {
                    // Use GPU instanced rendering
                    renderBatchInstanced(batch);
                    frameBatchCount++;
                    frameInstancedEntityCount += batch.size();
                    frameSavedDrawCalls += (batch.size() - 1); // N entities -> 1 draw call
                } else {
                    // Not enough entities, use normal rendering
                    renderBatchNormal(batch);
                }
            }

            // Update total statistics
            totalBatches.addAndGet(frameBatchCount);
            totalInstancedEntities.addAndGet(frameInstancedEntityCount);
            totalSavedDrawCalls.addAndGet(frameSavedDrawCalls);

            if (frameBatchCount > 0) {
                UHDFast.LOGGER.debug("EntityBatchManager: {} batches, {} instanced entities, {} saved draw calls",
                        frameBatchCount, frameInstancedEntityCount, frameSavedDrawCalls);
            }

        } catch (Exception e) {
            UHDFast.LOGGER.error("Failed to flush entity batch: {}", e.getMessage(), e);
            // Fallback: render all remaining entities normally
            for (List<Entity> batch : entityBatches.values()) {
                renderBatchNormal(batch);
            }
        }

        // Reset for next frame
        reset();
    }

    /**
     * Render a batch of entities using GPU instancing
     * 优化：复用缓存数组，避免每帧分配
     */
    private void renderBatchInstanced(List<Entity> batch) {
        if (batch.isEmpty()) {
            return;
        }

        try {
            int instanceCount = batch.size();

            // 确保缓存数组足够大（自动扩容，不复位）
            int requiredPos = instanceCount * 3;
            if (cachedInstancePositions.length < requiredPos) {
                cachedInstancePositions = new float[requiredPos];
            }
            if (cachedInstanceColors.length < requiredPos) {
                cachedInstanceColors = new float[requiredPos];
            }
            if (cachedInstanceScales.length < instanceCount) {
                cachedInstanceScales = new float[instanceCount];
            }

            float[] instancePositions = cachedInstancePositions;
            float[] instanceColors = cachedInstanceColors;
            float[] instanceScales = cachedInstanceScales;

            for (int i = 0; i < instanceCount; i++) {
                Entity entity = batch.get(i);
                int idx = i * 3;

                // Position
                instancePositions[idx] = (float) entity.getX();
                instancePositions[idx + 1] = (float) entity.getY();
                instancePositions[idx + 2] = (float) entity.getZ();

                // Color (default white, can be customized based on entity type)
                instanceColors[idx] = 1.0f;
                instanceColors[idx + 1] = 1.0f;
                instanceColors[idx + 2] = 1.0f;

                // Scale
                instanceScales[i] = 1.0f;
            }

            // Get base vertices for the entity type (simplified cube for now)
            float[] baseVertices = getBaseVerticesForEntityType(batch.get(0).getType());
            int vertexCount = baseVertices.length / 3;

            // Get view-projection matrix
            float[] viewProjection = getViewProjectionMatrix();
            float[] modelMatrix = getModelMatrix();

            // Call GPU instanced renderer
            // Note: We need a VAO ID, but for entities we'll use 0 as placeholder
            // The actual implementation would need to create/manage VAOs per entity type
            XeInstancedRenderer.renderWithInstanceAttributes(
                    0, // vaoId placeholder
                    vertexCount,
                    instanceCount,
                    instancePositions,
                    instanceColors,
                    instanceScales,
                    viewProjection,
                    modelMatrix);

        } catch (Exception e) {
            UHDFast.LOGGER.warn("GPU instanced rendering failed, falling back to normal: {}", e.getMessage());
            // Fallback to normal rendering
            renderBatchNormal(batch);
        }
    }

    /**
     * Render a batch of entities using normal rendering path
     */
    @SuppressWarnings("null")
    private void renderBatchNormal(List<Entity> batch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getEntityRenderDispatcher() == null) {
            return;
        }

        var dispatcher = mc.getEntityRenderDispatcher();
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);

        for (Entity entity : batch) {
            try {
                // Render entity normally
                var bufferSource = mc.renderBuffers().bufferSource();
                dispatcher.render(entity, entity.getX(), entity.getY(), entity.getZ(),
                        entity.getYRot(), partialTick,
                        new com.mojang.blaze3d.vertex.PoseStack(),
                        bufferSource,
                        dispatcher.getPackedLightCoords(entity, partialTick));
            } catch (Exception e) {
                UHDFast.LOGGER.debug("Failed to render entity normally: {}", e.getMessage());
            }
        }
    }

    /**
     * Get base vertices for an entity type (simplified implementation)
     * In a full implementation, this would extract vertices from entity models
     */
    private float[] getBaseVerticesForEntityType(EntityType<?> type) {
        // Simplified: return a unit cube
        // Full implementation would cache and retrieve actual entity model vertices
        return new float[] {
                // Cube vertices (simplified)
                -0.5f, -0.5f, -0.5f,
                0.5f, -0.5f, -0.5f,
                0.5f, 0.5f, -0.5f,
                -0.5f, 0.5f, -0.5f,
                -0.5f, -0.5f, 0.5f,
                0.5f, -0.5f, 0.5f,
                0.5f, 0.5f, 0.5f,
                -0.5f, 0.5f, 0.5f
        };
    }

    /**
     * Get view-projection matrix from current camera
     * 优化：使用缓存 Matrix4f 和 float[] 避免每帧分配
     */
    private static final org.joml.Matrix4f cachedViewProjection = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f cachedViewMatrix = new org.joml.Matrix4f();
    private static final float[] cachedViewProjectionArray = new float[16];

    private float[] getViewProjectionMatrix() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
            return createIdentityMatrix();
        }

        try {
            // Get projection matrix from game renderer
            var projectionMatrix = mc.gameRenderer.getProjectionMatrix(
                    mc.getTimer().getGameTimeDeltaPartialTick(false));

            // Get view matrix from camera
            var camera = mc.gameRenderer.getMainCamera();
            cachedViewMatrix
                    .translation((float) -camera.getPosition().x,
                            (float) -camera.getPosition().y,
                            (float) -camera.getPosition().z)
                    .rotateX((float) Math.toRadians(camera.getXRot()))
                    .rotateY((float) Math.toRadians(camera.getYRot() + 180));

            // Combine view * projection - 复用缓存矩阵
            cachedViewProjection.set(projectionMatrix).mul(cachedViewMatrix);

            // Convert to float array - 复用缓存数组
            cachedViewProjection.get(cachedViewProjectionArray);
            return cachedViewProjectionArray;

        } catch (Exception e) {
            UHDFast.LOGGER.debug("Failed to get view-projection matrix: {}", e.getMessage());
            return createIdentityMatrix();
        }
    }

    /** 缓存单位矩阵，避免每帧分配 */
    private static final float[] cachedIdentityMatrix = new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
    };

    /**
     * Get model matrix (identity for instanced rendering)
     */
    private float[] getModelMatrix() {
        return cachedIdentityMatrix;
    }

    /**
     * Create a 4x4 identity matrix
     */
    private float[] createIdentityMatrix() {
        return cachedIdentityMatrix;
    }

    /**
     * Reset batch manager for next frame
     * 优化：保留所有 ArrayList 实例，只清空内容，避免每帧重新分配
     */
    public void reset() {
        // 复用 ArrayList 对象：清空内容而非重新创建
        for (List<Entity> batch : entityBatches.values()) {
            batch.clear();
        }
        passthroughEntities.clear();
    }

    /**
     * Reset frame statistics
     */
    private void resetFrameStats() {
        frameBatchCount = 0;
        frameInstancedEntityCount = 0;
        frameSavedDrawCalls = 0;
    }

    /**
     * Get passthrough entities (entities that should not be instanced)
     */
    public List<Entity> getPassthroughEntities() {
        return passthroughEntities;
    }

    // Statistics getters
    public static int getTotalBatches() {
        return totalBatches.get();
    }

    public static int getTotalInstancedEntities() {
        return totalInstancedEntities.get();
    }

    public static int getTotalSavedDrawCalls() {
        return totalSavedDrawCalls.get();
    }

    public int getFrameBatchCount() {
        return frameBatchCount;
    }

    public int getFrameInstancedEntityCount() {
        return frameInstancedEntityCount;
    }

    public int getFrameSavedDrawCalls() {
        return frameSavedDrawCalls;
    }

    /**
     * Reset all statistics
     */
    public static void resetStats() {
        totalBatches.set(0);
        totalInstancedEntities.set(0);
        totalSavedDrawCalls.set(0);
    }
}
