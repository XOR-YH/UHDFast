package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;

import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GPU时域可见性复用引擎 - Xe架构优化
 * 
 * 缓存上一帧的可见性结果，对稳定区块复用可见性，减少逐帧遮挡测试开销。
 * 使用相机运动预测将区块分类为稳定(Stable)、边界(Boundary)或新进入(New)。
 * 
 * 原理：
 * - 当相机移动很小时，大部分区块的可见性不会改变
 * - 稳定区块直接复用上一帧的可见性结果，无需重新测试
 * - 边界区块需要重新测试（可能刚进入或离开视锥）
 * - 新进入区块必须重新测试
 */
public class XeVisibilityReuse {

    // ========================================================================
    // 状态标志
    // ========================================================================

    private static boolean initialized = false;
    private static boolean active = false;

    // ========================================================================
    // GPU缓冲区（经PersistentBufferPool管理）
    // ========================================================================

    /** 上一帧可见性状态缓冲区（GPU写入→CPU读取） */
    private static PersistentBufferPool.PersistentBuffer visibilityStateBuffer;

    /** 区块分类输出缓冲区（CPU写入→GPU读取） */
    private static PersistentBufferPool.PersistentBuffer chunkClassificationBuffer;

    /** 当前最大区块数 */
    private static int maxChunks = 0;

    // ========================================================================
    // 相机运动追踪
    // ========================================================================

    private static float prevCameraX, prevCameraY, prevCameraZ;
    private static float prevPitch, prevYaw;
    private static float velocityX, velocityY, velocityZ;
    private static float angularVelocityPitch, angularVelocityYaw;

    /** 当前帧相机相对上一帧的位移量（用于分类阈值判断） */
    private static float currentCameraMovement = 0.0f;

    /** 是否为第一帧（无历史数据） */
    private static boolean firstFrame = true;

    // ========================================================================
    // 统计计数器
    // ========================================================================

    private static int stableCount = 0;
    private static int boundaryCount = 0;
    private static int newCount = 0;

    // ========================================================================
    // 常量
    // ========================================================================

    /** 稳定阈值（平方）：半区块移动量（8个方块），小于此值判定为稳定 */
    private static final float STABLE_THRESHOLD = 8.0f;
    /** STABLE_THRESHOLD的平方值，用于平方距离比较，避免sqrt */
    private static final float STABLE_THRESHOLD_SQ = STABLE_THRESHOLD * STABLE_THRESHOLD;

    /** 每个区块的可见性状态大小（字节） */
    private static final int STATE_SIZE = 4;

    // ========================================================================
    // 内部状态
    // ========================================================================

    /** CPU端区块可见性追踪（线程安全） */
    private static final ConcurrentHashMap<Long, Boolean> previousChunkVisibility = new ConcurrentHashMap<>();

    /** 可复用的分类结果数组 */
    private static int[] reusableClassification = new int[0];

    /** 最后一次classifyChunks的区块键数组（用于saveVisibilityState回写） */
    private static long[] lastChunkKeys = new long[0];

    /** 最后一次classifyChunks的区块数量 */
    private static int lastChunkCount = 0;

    // ========================================================================
    // 初始化与生命周期
    // ========================================================================

    /**
     * 初始化可见性复用引擎。
     * 从PersistentBufferPool分配GPU缓冲区。
     *
     * @param maxChunkCount 最大区块数量
     */
    public static void initialize(int maxChunkCount) {
        if (initialized) {
            // 如果已初始化但尺寸变化，重新分配
            if (maxChunkCount > maxChunks) {
                updateMaxChunks(maxChunkCount);
            }
            return;
        }

        try {
            maxChunks = Math.max(maxChunkCount, 64);

            // 分配上一帧可见性状态缓冲区（DOWNLOAD：GPU写入，CPU读取）
            visibilityStateBuffer = PersistentBufferPool.acquire(
                    (long) maxChunks * STATE_SIZE,
                    PersistentBufferPool.BufferUsage.DOWNLOAD);

            // 分配区块分类输出缓冲区（UPLOAD：CPU写入，GPU读取）
            chunkClassificationBuffer = PersistentBufferPool.acquire(
                    (long) maxChunks * STATE_SIZE,
                    PersistentBufferPool.BufferUsage.UPLOAD);

            // 预分配可复用数组
            reusableClassification = new int[maxChunks];
            lastChunkKeys = new long[maxChunks];

            // 初始化相机位置
            prevCameraX = 0.0f;
            prevCameraY = 0.0f;
            prevCameraZ = 0.0f;
            prevPitch = 0.0f;
            prevYaw = 0.0f;
            velocityX = 0.0f;
            velocityY = 0.0f;
            velocityZ = 0.0f;
            angularVelocityPitch = 0.0f;
            angularVelocityYaw = 0.0f;
            firstFrame = true;

            initialized = true;
            active = true;

            UHDFast.LOGGER.info("XeVisibilityReuse: Initialized (maxChunks={})", maxChunks);

        } catch (Exception e) {
            UHDFast.LOGGER.error("XeVisibilityReuse: Failed to initialize: {}", e.getMessage());
            active = false;
        }
    }

    /**
     * 更新最大区块数量，必要时重新分配缓冲区。
     *
     * @param maxChunkCount 新的最大区块数量
     */
    public static void updateMaxChunks(int maxChunkCount) {
        if (maxChunkCount <= maxChunks) {
            return;
        }

        try {
            int oldMaxChunks = maxChunks;
            maxChunks = Math.max(maxChunkCount, 64);

            // 重新分配可见性状态缓冲区
            if (visibilityStateBuffer != null) {
                PersistentBufferPool.release(visibilityStateBuffer);
            }
            visibilityStateBuffer = PersistentBufferPool.acquire(
                    (long) maxChunks * STATE_SIZE,
                    PersistentBufferPool.BufferUsage.DOWNLOAD);

            // 重新分配分类输出缓冲区
            if (chunkClassificationBuffer != null) {
                PersistentBufferPool.release(chunkClassificationBuffer);
            }
            chunkClassificationBuffer = PersistentBufferPool.acquire(
                    (long) maxChunks * STATE_SIZE,
                    PersistentBufferPool.BufferUsage.UPLOAD);

            // 重新分配可复用数组
            reusableClassification = new int[maxChunks];
            lastChunkKeys = new long[maxChunks];

            UHDFast.LOGGER.info("XeVisibilityReuse: Max chunks updated {} -> {}", oldMaxChunks, maxChunks);

        } catch (Exception e) {
            UHDFast.LOGGER.error("XeVisibilityReuse: Failed to update max chunks: {}", e.getMessage());
        }
    }

    // ========================================================================
    // 相机运动更新
    // ========================================================================

    /**
     * 更新相机位置，使用指数移动平均（EMA, alpha=0.3）计算速度。
     * 同时计算当前帧相对于上一帧的位移量。
     *
     * @param camX  相机X坐标
     * @param camY  相机Y坐标
     * @param camZ  相机Z坐标
     * @param pitch 俯仰角（度）
     * @param yaw   偏航角（度）
     */
    public static void updateCameraMotion(float camX, float camY, float camZ, float pitch, float yaw) {
        if (firstFrame) {
            // 第一帧：初始化位置，不计算速度
            prevCameraX = camX;
            prevCameraY = camY;
            prevCameraZ = camZ;
            prevPitch = pitch;
            prevYaw = yaw;
            velocityX = 0.0f;
            velocityY = 0.0f;
            velocityZ = 0.0f;
            angularVelocityPitch = 0.0f;
            angularVelocityYaw = 0.0f;
            currentCameraMovement = 0.0f;
            firstFrame = false;
            return;
        }

        // 计算位移（使用平方距离，避免sqrt）
        float deltaX = camX - prevCameraX;
        float deltaY = camY - prevCameraY;
        float deltaZ = camZ - prevCameraZ;
        currentCameraMovement = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;

        // 计算角度变化（处理角度环绕）
        float deltaPitch = pitch - prevPitch;
        float deltaYaw = yaw - prevYaw;
        // 标准化角度差到[-180, 180]
        if (deltaYaw > 180.0f)
            deltaYaw -= 360.0f;
        if (deltaYaw < -180.0f)
            deltaYaw += 360.0f;
        if (deltaPitch > 180.0f)
            deltaPitch -= 360.0f;
        if (deltaPitch < -180.0f)
            deltaPitch += 360.0f;

        // 指数移动平均（EMA）计算速度，alpha=0.3
        float alpha = 0.3f;
        velocityX = alpha * deltaX + (1.0f - alpha) * velocityX;
        velocityY = alpha * deltaY + (1.0f - alpha) * velocityY;
        velocityZ = alpha * deltaZ + (1.0f - alpha) * velocityZ;
        angularVelocityPitch = alpha * deltaPitch + (1.0f - alpha) * angularVelocityPitch;
        angularVelocityYaw = alpha * deltaYaw + (1.0f - alpha) * angularVelocityYaw;

        // 更新上一帧位置
        prevCameraX = camX;
        prevCameraY = camY;
        prevCameraZ = camZ;
        prevPitch = pitch;
        prevYaw = yaw;
    }

    /**
     * 使用当前速度预测未来相机位置。
     *
     * @param deltaTime 预测的时间偏移量（毫秒）
     * @return float[] {x, y, z} 预测后的相机位置
     */
    public static float[] predictMotion(float deltaTime) {
        float timeSeconds = deltaTime / 1000.0f;
        float predX = prevCameraX + velocityX * timeSeconds;
        float predY = prevCameraY + velocityY * timeSeconds;
        float predZ = prevCameraZ + velocityZ * timeSeconds;
        return new float[] { predX, predY, predZ };
    }

    // ========================================================================
    // 区块分类
    // ========================================================================

    /**
     * 从long键中提取区块X坐标（与sectionKey编码一致）。
     */
    private static int extractChunkX(long key) {
        return (int) (key << 38 >> 38);
    }

    /**
     * 从long键中提取区块Z坐标（与sectionKey编码一致）。
     */
    private static int extractChunkZ(long key) {
        return (int) (key >> 39);
    }

    /**
     * 对区块进行分类：稳定(Stable)、边界(Boundary)或新进入(New)。
     * 
     * 算法：
     * - 从每个chunkKey中提取chunkX, chunkZ
     * - 计算与相机区块位置的Chebyshev距离
     * - 如果上一帧可见且距离在安全范围内且相机移动量小于阈值 → STABLE
     * - 如果距离在渲染距离边缘或靠近中心 → BOUNDARY
     * - 否则 → NEW
     *
     * @param chunkKeys      区块键数组（与sectionKey编码一致）
     * @param chunkCount     区块数量
     * @param renderDistance 渲染距离（区块数）
     * @param camChunkX      相机所在区块X
     * @param camChunkZ      相机所在区块Z
     * @return int[] 分类数组（0=稳定, 1=边界, 2=新进入）
     */
    @SuppressWarnings("all")
    public static int[] classifyChunks(long[] chunkKeys, int chunkCount, int renderDistance,
            int camChunkX, int camChunkZ) {
        if (!initialized || chunkKeys == null || chunkCount <= 0) {
            stableCount = 0;
            boundaryCount = 0;
            newCount = 0;
            return new int[0];
        }

        int count = Math.min(chunkCount, maxChunks);

        // 确保可复用数组足够大
        if (reusableClassification.length < count) {
            reusableClassification = new int[count];
        }
        if (lastChunkKeys.length < count) {
            lastChunkKeys = new long[count];
        }

        // 保存chunkKeys供saveVisibilityState使用
        System.arraycopy(chunkKeys, 0, lastChunkKeys, 0, count);
        lastChunkCount = count;

        int stable = 0;
        int boundary = 0;
        int newChunks = 0;

        for (int i = 0; i < count; i++) {
            long key = chunkKeys[i];
            int chunkX = extractChunkX(key);
            int chunkZ = extractChunkZ(key);

            // Chebyshev距离（区块空间）
            int dx = chunkX - camChunkX;
            int dz = chunkZ - camChunkZ;
            int dist = Math.max(Math.abs(dx), Math.abs(dz));

            // 上一帧可见性
            boolean wasVisible = previousChunkVisibility.getOrDefault(key, false);

            // 分类逻辑
            if (wasVisible && dist < renderDistance - 2 && currentCameraMovement < STABLE_THRESHOLD_SQ) {
                // 稳定：上一帧可见，在安全距离内，相机移动小
                reusableClassification[i] = 0;
                stable++;
            } else if (dist > renderDistance - 1 || dist < 2) {
                // 边界：在渲染距离边缘或靠近中心（快速移动时可见性变化剧烈）
                reusableClassification[i] = 1;
                boundary++;
            } else {
                // 新进入：不在上一帧可见范围内
                reusableClassification[i] = 2;
                newChunks++;
            }
        }

        stableCount = stable;
        boundaryCount = boundary;
        newCount = newChunks;

        return reusableClassification;
    }

    // ========================================================================
    // 可见性复用
    // ========================================================================

    /**
     * 对稳定区块复用上一帧可见性结果。
     * 稳定区块（classification[i] == 0）直接标记为可见，
     * 其他区块保持原样（将由遮挡测试处理）。
     *
     * @param classification    分类数组（来自classifyChunks）
     * @param currentVisibility 当前帧可见性数组（会被修改）
     */
    public static void reuseStableVisibility(int[] classification, boolean[] currentVisibility) {
        if (classification == null || currentVisibility == null) {
            return;
        }

        int len = Math.min(classification.length, currentVisibility.length);
        for (int i = 0; i < len; i++) {
            if (classification[i] == 0) {
                // 稳定区块：复用上一帧可见性（标记为可见）
                currentVisibility[i] = true;
            }
            // 其他区块保持原样，将由遮挡测试处理
        }
    }

    /**
     * 保存当前帧可见性到GPU状态缓冲区，并更新CPU端可见性追踪。
     * 保存的数据将成为下一帧的"上一帧可见性"参考。
     *
     * @param visibility 当前帧可见性数组
     * @param startIndex 起始索引
     * @param count      保存数量
     */
    @SuppressWarnings("all")
    public static void saveVisibilityState(boolean[] visibility, int startIndex, int count) {
        if (!initialized || visibility == null || count <= 0) {
            return;
        }

        int end = Math.min(startIndex + count, Math.min(visibility.length, maxChunks));

        try {
            // 写入GPU缓冲区
            IntBuffer stateView = visibilityStateBuffer.getIntView();
            int writeCount = end - startIndex;
            for (int i = 0; i < writeCount; i++) {
                int idx = startIndex + i;
                stateView.put(i, visibility[idx] ? 1 : 0);
            }

            // 更新CPU端可见性追踪
            int updateCount = Math.min(writeCount, lastChunkCount);
            for (int i = 0; i < updateCount; i++) {
                if (i < lastChunkKeys.length) {
                    long key = lastChunkKeys[i];
                    boolean vis = visibility[startIndex + i];
                    previousChunkVisibility.put(key, vis);
                }
            }

        } catch (Exception e) {
            UHDFast.LOGGER.warn("XeVisibilityReuse: Failed to save visibility state: {}", e.getMessage());
        }
    }

    // ========================================================================
    // 统计访问器
    // ========================================================================

    /**
     * @return 稳定区块数量
     */
    public static int getStableCount() {
        return stableCount;
    }

    /**
     * @return 边界区块数量
     */
    public static int getBoundaryCount() {
        return boundaryCount;
    }

    /**
     * @return 新进入区块数量
     */
    public static int getNewCount() {
        return newCount;
    }

    /**
     * @return 可见性复用引擎是否已激活且初始化完成
     */
    public static boolean isActive() {
        return active && initialized;
    }

    /**
     * 获取上一帧可见性追踪表（只读，线程安全）。
     * 
     * @return 区块键→可见性映射
     */
    public static ConcurrentHashMap<Long, Boolean> getPreviousChunkVisibility() {
        return previousChunkVisibility;
    }

    // ========================================================================
    // 清理
    // ========================================================================

    /**
     * 重置所有统计计数器。
     */
    public static void resetStats() {
        stableCount = 0;
        boundaryCount = 0;
        newCount = 0;
        previousChunkVisibility.clear();
        firstFrame = true;
    }

    /**
     * 销毁可见性复用引擎，释放GPU缓冲区回池。
     */
    public static void destroy() {
        // 释放可见性状态缓冲区
        if (visibilityStateBuffer != null) {
            PersistentBufferPool.release(visibilityStateBuffer);
            visibilityStateBuffer = null;
        }

        // 释放分类输出缓冲区
        if (chunkClassificationBuffer != null) {
            PersistentBufferPool.release(chunkClassificationBuffer);
            chunkClassificationBuffer = null;
        }

        // 清理CPU端状态
        previousChunkVisibility.clear();
        reusableClassification = new int[0];
        lastChunkKeys = new long[0];
        lastChunkCount = 0;

        maxChunks = 0;
        initialized = false;
        active = false;
        firstFrame = true;

        stableCount = 0;
        boundaryCount = 0;
        newCount = 0;

        UHDFast.LOGGER.info("XeVisibilityReuse: Destroyed");
    }
}