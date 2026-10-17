package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存屏障优化器 - 合并和精简 glMemoryBarrier 调用，减少 GPU 同步开销。
 *
 * 核心优化：
 * 1. 屏障合并：将多个连续的屏障调用合并为一个
 * 2. 精确屏障：使用具体的屏障位（如 GL_SHADER_STORAGE_BARRIER_BIT）而不是 GL_ALL_BARRIER_BITS
 * 3. 延迟屏障：延迟非关键屏障直到真正需要同步
 * 4. 屏障去重：避免重复的屏障调用
 *
 * 基于 Intel oneAPI 优化最佳实践：
 * - "Minimize Synchronization" - 减少同步点
 * - "Use Specific Barrier Bits" - 精确屏障
 * - "Batch Operations" - 批量操作
 */
public class MemoryBarrierOptimizer {

    // ========================================================================
    // 屏障位常量
    // ========================================================================

    public static final int BARRIER_NONE = 0;
    public static final int BARRIER_SSBO = GL43.GL_SHADER_STORAGE_BARRIER_BIT;
    public static final int BARRIER_UBO = GL43.GL_UNIFORM_BARRIER_BIT;
    public static final int BARRIER_TEXTURE = GL43.GL_TEXTURE_FETCH_BARRIER_BIT;
    public static final int BARRIER_IMAGE = GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
    public static final int BARRIER_VERTEX = GL43.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;
    public static final int BARRIER_INDEX = GL43.GL_ELEMENT_ARRAY_BARRIER_BIT;
    public static final int BARRIER_INDIRECT = 0x00000002; // GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT
    public static final int BARRIER_ALL = GL43.GL_ALL_BARRIER_BITS;

    // ========================================================================
    // 屏障累积器
    // ========================================================================

    /** 当前帧累积的屏障位 */
    private static volatile int accumulatedBarriers = BARRIER_NONE;

    /** 上一帧的屏障位（用于去重） */
    private static volatile int lastFrameBarriers = BARRIER_NONE;

    /** 是否处于批处理模式 */
    private static volatile boolean batchMode = false;

    // ========================================================================
    // 统计
    // ========================================================================

    private static final AtomicInteger totalBarriers = new AtomicInteger(0);
    private static final AtomicInteger mergedBarriers = new AtomicInteger(0);
    private static final AtomicInteger redundantBarriers = new AtomicInteger(0);
    private static final AtomicInteger preciseBarriers = new AtomicInteger(0);
    private static final AtomicInteger broadBarriers = new AtomicInteger(0);

    private static volatile boolean initialized = false;

    /**
     * 初始化屏障优化器
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        initialized = true;
        UHDFast.LOGGER.info("MemoryBarrierOptimizer: Initialized");
        UHDFast.LOGGER.info("  Barrier merging: enabled");
        UHDFast.LOGGER.info("  Precise barriers: enabled");
    }

    /**
     * 开始批处理模式 - 累积屏障而不是立即发出
     */
    public static void beginBatch() {
        if (!initialized)
            initialize();
        batchMode = true;
        accumulatedBarriers = BARRIER_NONE;
    }

    /**
     * 结束批处理模式 - 发出累积的屏障
     */
    public static void endBatch() {
        if (!batchMode)
            return;

        if (accumulatedBarriers != BARRIER_NONE) {
            issueBarrier(accumulatedBarriers);
            accumulatedBarriers = BARRIER_NONE;
        }

        batchMode = false;
    }

    /**
     * 发出内存屏障（优化版本）
     *
     * @param barrierBits 屏障位
     */
    public static void barrier(int barrierBits) {
        if (!initialized)
            initialize();

        if (barrierBits == BARRIER_NONE)
            return;

        // 统计
        totalBarriers.incrementAndGet();

        // 检查是否是过于宽泛的屏障
        if (barrierBits == BARRIER_ALL) {
            broadBarriers.incrementAndGet();
            UHDFast.LOGGER.debug("Warning: Using GL_ALL_BARRIER_BITS - consider using specific bits");
        } else {
            preciseBarriers.incrementAndGet();
        }

        if (batchMode) {
            // 批处理模式：累积屏障位
            accumulatedBarriers |= barrierBits;
            mergedBarriers.incrementAndGet();
        } else {
            // 非批处理模式：检查是否与上一帧重复
            if (barrierBits == lastFrameBarriers) {
                redundantBarriers.incrementAndGet();
                // 跳过重复的屏障
                return;
            }

            // 发出屏障
            issueBarrier(barrierBits);
            lastFrameBarriers = barrierBits;
        }
    }

    /**
     * 发出 SSBO 屏障（最常用）
     */
    public static void barrierSSBO() {
        barrier(BARRIER_SSBO);
    }

    /**
     * 发出 UBO 屏障
     */
    public static void barrierUBO() {
        barrier(BARRIER_UBO);
    }

    /**
     * 发出纹理屏障
     */
    public static void barrierTexture() {
        barrier(BARRIER_TEXTURE);
    }

    /**
     * 发出图像访问屏障
     */
    public static void barrierImage() {
        barrier(BARRIER_IMAGE);
    }

    /**
     * 发出顶点属性屏障
     */
    public static void barrierVertex() {
        barrier(BARRIER_VERTEX);
    }

    /**
     * 发出索引屏障
     */
    public static void barrierIndex() {
        barrier(BARRIER_INDEX);
    }

    /**
     * 发出组合屏障（多个屏障位）
     */
    public static void barrierCombined(int... barrierBits) {
        int combined = BARRIER_NONE;
        for (int bits : barrierBits) {
            combined |= bits;
        }
        barrier(combined);
    }

    /**
     * 实际发出 OpenGL 屏障调用
     */
    private static void issueBarrier(int barrierBits) {
        GL43.glMemoryBarrier(barrierBits);
    }

    /**
     * 帧开始时重置状态
     */
    public static void onFrameStart() {
        lastFrameBarriers = BARRIER_NONE;
        accumulatedBarriers = BARRIER_NONE;
    }

    /**
     * 帧结束时发出所有累积的屏障
     */
    public static void onFrameEnd() {
        if (accumulatedBarriers != BARRIER_NONE) {
            issueBarrier(accumulatedBarriers);
            accumulatedBarriers = BARRIER_NONE;
        }
    }

    /**
     * 清理
     */
    public static synchronized void destroy() {
        initialized = false;
        accumulatedBarriers = BARRIER_NONE;
        lastFrameBarriers = BARRIER_NONE;
        UHDFast.LOGGER.info("MemoryBarrierOptimizer: Destroyed");
    }

    // ========================================================================
    // 统计接口
    // ========================================================================

    public static int getTotalBarriers() {
        return totalBarriers.get();
    }

    public static int getMergedBarriers() {
        return mergedBarriers.get();
    }

    public static int getRedundantBarriers() {
        return redundantBarriers.get();
    }

    public static int getPreciseBarriers() {
        return preciseBarriers.get();
    }

    public static int getBroadBarriers() {
        return broadBarriers.get();
    }

    /**
     * 获取屏障优化率
     */
    public static double getOptimizationRate() {
        int total = totalBarriers.get();
        int optimized = mergedBarriers.get() + redundantBarriers.get();
        return total > 0 ? (double) optimized / total * 100.0 : 0.0;
    }

    /**
     * 获取精确屏障使用率
     */
    public static double getPreciseRate() {
        int total = totalBarriers.get();
        return total > 0 ? (double) preciseBarriers.get() / total * 100.0 : 0.0;
    }

    public static String getBarrierStats() {
        return String.format("MemoryBarrier: total=%d merged=%d redundant=%d precise=%d broad=%d " +
                "optRate=%.1f%% preciseRate=%.1f%%",
                totalBarriers.get(), mergedBarriers.get(), redundantBarriers.get(),
                preciseBarriers.get(), broadBarriers.get(),
                getOptimizationRate(), getPreciseRate());
    }

    public static void resetStats() {
        totalBarriers.set(0);
        mergedBarriers.set(0);
        redundantBarriers.set(0);
        preciseBarriers.set(0);
        broadBarriers.set(0);
    }
}
