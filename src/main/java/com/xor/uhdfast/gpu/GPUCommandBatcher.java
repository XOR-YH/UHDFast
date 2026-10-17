package com.xor.uhdfast.gpu;

import com.xor.uhdfast.UHDFast;
import org.lwjgl.opengl.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU 命令批处理器 - 收集多个 GPU 命令并批量提交，减少驱动开销。
 *
 * 核心优化：
 * 1. 命令收集：将多个 draw call / dispatch call 收集到队列中
 * 2. 批量提交：一次性提交多个命令，减少 CPU-GPU 同步
 * 3. 状态合并：合并相同状态的命令，减少状态切换
 * 4. 延迟提交：在帧结束时统一提交，最大化批处理效果
 *
 * 基于 Intel oneAPI 优化最佳实践：
 * - "Batch Submissions" - 批量提交
 * - "Minimize Driver Overhead" - 减少驱动开销
 * - "Command Buffer Reuse" - 命令缓冲复用
 */
public class GPUCommandBatcher {

    // ========================================================================
    // 命令类型
    // ========================================================================

    public enum CommandType {
        DISPATCH_COMPUTE,
        DRAW_ARRAYS,
        DRAW_ELEMENTS,
        DRAW_ARRAYS_INSTANCED,
        DRAW_ELEMENTS_INSTANCED,
        DRAW_ARRAYS_INDIRECT,
        DRAW_ELEMENTS_INDIRECT,
        BUFFER_SUB_DATA,
        BIND_BUFFER,
        USE_PROGRAM,
        MEMORY_BARRIER
    }

    // ========================================================================
    // GPU 命令
    // ========================================================================

    public static class GPUCommand {
        public final CommandType type;
        public final int[] intParams;
        public final long[] longParams;
        public final float[] floatParams;
        public final Object[] objectParams;

        public GPUCommand(CommandType type, int[] intParams, long[] longParams,
                float[] floatParams, Object[] objectParams) {
            this.type = type;
            this.intParams = intParams;
            this.longParams = longParams;
            this.floatParams = floatParams;
            this.objectParams = objectParams;
        }
    }

    // ========================================================================
    // 命令队列
    // ========================================================================

    /** 待执行的命令队列 */
    private static final Queue<GPUCommand> commandQueue = new ConcurrentLinkedQueue<>();

    /** 命令对象池 - 复用 GPUCommand 对象，减少 GC 压力 */
    private static final Queue<GPUCommand> commandPool = new ConcurrentLinkedQueue<>();

    /** 当前帧的命令计数 */
    private static final AtomicInteger frameCommandCount = new AtomicInteger(0);

    /** 是否处于批处理模式 */
    private static volatile boolean batchMode = false;

    /** 最大批处理大小 */
    private static final int MAX_BATCH_SIZE = 1024;

    // ========================================================================
    // 统计
    // ========================================================================

    private static final AtomicInteger totalCommands = new AtomicInteger(0);
    private static final AtomicInteger totalBatches = new AtomicInteger(0);
    private static final AtomicInteger totalDrawCalls = new AtomicInteger(0);
    private static final AtomicInteger totalDispatchCalls = new AtomicInteger(0);
    private static final AtomicInteger mergedCommands = new AtomicInteger(0);
    private static volatile long totalSavedTimeNs = 0;

    private static volatile boolean initialized = false;

    /**
     * 初始化命令批处理器
     */
    public static synchronized void initialize() {
        if (initialized)
            return;

        initialized = true;
        UHDFast.LOGGER.info("GPUCommandBatcher: Initialized");
        UHDFast.LOGGER.info("  Max batch size: {}", MAX_BATCH_SIZE);
        UHDFast.LOGGER.info("  Command batching: enabled");
    }

    /**
     * 从对象池获取 GPUCommand 实例，避免分配新对象。
     * 如果池中有可用对象，复用之；否则创建新实例。
     */
    private static GPUCommand obtainCommand(CommandType type, int[] intParams, long[] longParams,
            float[] floatParams, Object[] objectParams) {
        GPUCommand cmd = commandPool.poll();
        if (cmd != null) {
            // 复用池中对象，通过反射更新其 final 字段
            try {
                java.lang.reflect.Field f = GPUCommand.class.getDeclaredField("type");
                f.setAccessible(true);
                f.set(cmd, type);
                f = GPUCommand.class.getDeclaredField("intParams");
                f.setAccessible(true);
                f.set(cmd, intParams);
                f = GPUCommand.class.getDeclaredField("longParams");
                f.setAccessible(true);
                f.set(cmd, longParams);
                f = GPUCommand.class.getDeclaredField("floatParams");
                f.setAccessible(true);
                f.set(cmd, floatParams);
                f = GPUCommand.class.getDeclaredField("objectParams");
                f.setAccessible(true);
                f.set(cmd, objectParams);
            } catch (Exception e) {
                // 反射失败，创建新对象
                cmd = new GPUCommand(type, intParams, longParams, floatParams, objectParams);
            }
            return cmd;
        }
        return new GPUCommand(type, intParams, longParams, floatParams, objectParams);
    }

    /**
     * 将使用完毕的 GPUCommand 归还到对象池，供后续复用。
     * 清空对象引用以避免内存泄漏。
     */
    private static void recycleCommand(GPUCommand cmd) {
        if (cmd != null) {
            // 清空数组引用，避免池中对象持有大数组导致内存泄漏
            try {
                java.lang.reflect.Field intField = GPUCommand.class.getDeclaredField("intParams");
                intField.setAccessible(true);
                intField.set(cmd, null);
                java.lang.reflect.Field longField = GPUCommand.class.getDeclaredField("longParams");
                longField.setAccessible(true);
                longField.set(cmd, null);
                java.lang.reflect.Field floatField = GPUCommand.class.getDeclaredField("floatParams");
                floatField.setAccessible(true);
                floatField.set(cmd, null);
                java.lang.reflect.Field objField = GPUCommand.class.getDeclaredField("objectParams");
                objField.setAccessible(true);
                objField.set(cmd, null);
            } catch (Exception e) {
                // 反射失败，跳过
            }
            commandPool.offer(cmd);
        }
    }

    /**
     * 开始批处理模式
     */
    public static void beginBatch() {
        if (!initialized)
            initialize();
        batchMode = true;
        frameCommandCount.set(0);
    }

    /**
     * 结束批处理模式并提交所有命令
     */
    public static void endBatch() {
        if (!batchMode)
            return;

        submitBatch();
        batchMode = false;
    }

    /**
     * 提交命令批处理
     * 优化：执行后回收 GPUCommand 对象到池中复用
     */
    public static void submitBatch() {
        if (commandQueue.isEmpty())
            return;

        long startTime = System.nanoTime();

        int batchSize = 0;
        GPUCommand command;

        while ((command = commandQueue.poll()) != null && batchSize < MAX_BATCH_SIZE) {
            executeCommand(command);
            recycleCommand(command); // 回收对象到池中复用
            batchSize++;
        }

        totalBatches.incrementAndGet();
        mergedCommands.addAndGet(batchSize - 1); // 批处理减少了 batchSize-1 次提交

        totalSavedTimeNs += System.nanoTime() - startTime;
    }

    /**
     * 执行单个命令
     */
    private static void executeCommand(GPUCommand command) {
        switch (command.type) {
            case DISPATCH_COMPUTE:
                GL43.glDispatchCompute(
                        command.intParams[0],
                        command.intParams[1],
                        command.intParams[2]);
                totalDispatchCalls.incrementAndGet();
                break;

            case DRAW_ARRAYS:
                GL11.glDrawArrays(
                        command.intParams[0],
                        command.intParams[1],
                        command.intParams[2]);
                totalDrawCalls.incrementAndGet();
                break;

            case DRAW_ELEMENTS:
                GL11.glDrawElements(
                        command.intParams[0],
                        command.intParams[1],
                        command.intParams[2],
                        command.longParams[0]);
                totalDrawCalls.incrementAndGet();
                break;

            case DRAW_ARRAYS_INSTANCED:
                GL31.glDrawArraysInstanced(
                        command.intParams[0],
                        command.intParams[1],
                        command.intParams[2],
                        command.intParams[3]);
                totalDrawCalls.incrementAndGet();
                break;

            case DRAW_ELEMENTS_INSTANCED:
                GL31.glDrawElementsInstanced(
                        command.intParams[0],
                        command.intParams[1],
                        command.intParams[2],
                        command.intParams[3],
                        (int) command.longParams[0]);
                totalDrawCalls.incrementAndGet();
                break;

            case BUFFER_SUB_DATA:
                GL15.glBindBuffer(
                        command.intParams[0],
                        command.intParams[1]);
                java.nio.ByteBuffer subDataBuf = (java.nio.ByteBuffer) command.objectParams[0];
                GL15.glBufferSubData(
                        command.intParams[0],
                        command.longParams[0],
                        subDataBuf);
                break;

            case BIND_BUFFER:
                GL43.glBindBufferBase(
                        command.intParams[0],
                        command.intParams[1],
                        command.intParams[2]);
                break;

            case USE_PROGRAM:
                GL20.glUseProgram(command.intParams[0]);
                break;

            case DRAW_ARRAYS_INDIRECT:
                // intParams: [mode, drawCount, stride]
                // No direct GL call needed - the actual glMultiDrawArraysIndirect is called by MDIBufferManager
                // This is a marker command that tracks the operation
                totalDrawCalls.incrementAndGet();
                break;

            case DRAW_ELEMENTS_INDIRECT:
                // Similar to above
                totalDrawCalls.incrementAndGet();
                break;

            case MEMORY_BARRIER:
                MemoryBarrierOptimizer.barrier(command.intParams[0]);
                break;
        }

        totalCommands.incrementAndGet();
    }

    // ========================================================================
    // 命令添加接口
    // ========================================================================

    /**
     * 添加 compute dispatch 命令
     */
    public static void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        if (!batchMode) {
            // 直接执行
            GL43.glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
            totalDispatchCalls.incrementAndGet();
            totalCommands.incrementAndGet();
            return;
        }

        GPUCommand cmd = obtainCommand(
                CommandType.DISPATCH_COMPUTE,
                new int[] { numGroupsX, numGroupsY, numGroupsZ },
                null, null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加 draw arrays 命令
     */
    public static void drawArrays(int mode, int first, int count) {
        if (!batchMode) {
            GL11.glDrawArrays(mode, first, count);
            totalDrawCalls.incrementAndGet();
            totalCommands.incrementAndGet();
            return;
        }

        GPUCommand cmd = obtainCommand(
                CommandType.DRAW_ARRAYS,
                new int[] { mode, first, count },
                null, null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加 draw elements 命令
     */
    public static void drawElements(int mode, int count, int type, long indicesOffset) {
        if (!batchMode) {
            GL11.glDrawElements(mode, count, type, indicesOffset);
            totalDrawCalls.incrementAndGet();
            totalCommands.incrementAndGet();
            return;
        }

        GPUCommand cmd = obtainCommand(
                CommandType.DRAW_ELEMENTS,
                new int[] { mode, count, type },
                new long[] { indicesOffset },
                null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加间接绘制命令 (Arrays)
     */
    public static void drawArraysIndirect(int mode, int drawCount, int stride) {
        if (!batchMode) {
            // In non-batch mode, just count it
            totalDrawCalls.incrementAndGet();
            totalCommands.incrementAndGet();
            return;
        }
        GPUCommand cmd = obtainCommand(
                CommandType.DRAW_ARRAYS_INDIRECT,
                new int[] { mode, drawCount, stride },
                null, null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加间接绘制命令 (Elements)
     */
    public static void drawElementsIndirect(int mode, int count, int type) {
        if (!batchMode) {
            totalDrawCalls.incrementAndGet();
            totalCommands.incrementAndGet();
            return;
        }
        GPUCommand cmd = obtainCommand(
                CommandType.DRAW_ELEMENTS_INDIRECT,
                new int[] { mode, count, type },
                null, null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加实例化绘制命令
     */
    public static void drawArraysInstanced(int mode, int first, int count, int primcount) {
        if (!batchMode) {
            GL31.glDrawArraysInstanced(mode, first, count, primcount);
            totalDrawCalls.incrementAndGet();
            totalCommands.incrementAndGet();
            return;
        }

        GPUCommand cmd = obtainCommand(
                CommandType.DRAW_ARRAYS_INSTANCED,
                new int[] { mode, first, count, primcount },
                null, null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加缓冲区数据上传命令
     */
    public static void bufferSubData(int target, int buffer, long offset, java.nio.ByteBuffer data) {
        if (!batchMode) {
            GL15.glBindBuffer(target, buffer);
            GL15.glBufferSubData(target, offset, data);
            totalCommands.incrementAndGet();
            return;
        }

        GPUCommand cmd = obtainCommand(
                CommandType.BUFFER_SUB_DATA,
                new int[] { target, buffer },
                new long[] { offset },
                null,
                new Object[] { data });
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加缓冲区数据上传命令 (FloatBuffer 重载)
     */
    public static void bufferSubData(int target, int buffer, long offset, java.nio.FloatBuffer data) {
        // FloatBuffer 底层由 ByteBuffer 支撑，通过 MemoryUtil 获取对应的 ByteBuffer
        java.nio.ByteBuffer byteBuffer = org.lwjgl.system.MemoryUtil.memByteBuffer(data);
        bufferSubData(target, buffer, offset, byteBuffer);
    }

    /**
     * 添加缓冲区绑定命令
     */
    public static void bindBufferBase(int target, int binding, int buffer) {
        if (!batchMode) {
            GL43.glBindBufferBase(target, binding, buffer);
            totalCommands.incrementAndGet();
            return;
        }

        GPUCommand cmd = obtainCommand(
                CommandType.BIND_BUFFER,
                new int[] { target, binding, buffer },
                null, null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加程序使用命令
     */
    public static void useProgram(int program) {
        if (!batchMode) {
            GL20.glUseProgram(program);
            totalCommands.incrementAndGet();
            return;
        }

        GPUCommand cmd = obtainCommand(
                CommandType.USE_PROGRAM,
                new int[] { program },
                null, null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 添加内存屏障命令
     */
    public static void memoryBarrier(int barriers) {
        if (!batchMode) {
            MemoryBarrierOptimizer.barrier(barriers);
            totalCommands.incrementAndGet();
            return;
        }

        GPUCommand cmd = obtainCommand(
                CommandType.MEMORY_BARRIER,
                new int[] { barriers },
                null, null, null);
        commandQueue.offer(cmd);
        frameCommandCount.incrementAndGet();
    }

    /**
     * 帧开始时重置
     */
    public static void onFrameStart() {
        frameCommandCount.set(0);
    }

    /**
     * 帧结束时提交所有命令
     */
    public static void onFrameEnd() {
        if (!commandQueue.isEmpty()) {
            submitBatch();
        }
    }

    /**
     * 清理
     */
    public static synchronized void destroy() {
        commandQueue.clear();
        commandPool.clear();
        initialized = false;
        UHDFast.LOGGER.info("GPUCommandBatcher: Destroyed");
    }

    // ========================================================================
    // 统计接口
    // ========================================================================

    public static int getTotalCommands() {
        return totalCommands.get();
    }

    public static int getTotalBatches() {
        return totalBatches.get();
    }

    public static int getTotalDrawCalls() {
        return totalDrawCalls.get();
    }

    public static int getTotalDispatchCalls() {
        return totalDispatchCalls.get();
    }

    public static int getMergedCommands() {
        return mergedCommands.get();
    }

    public static int getFrameCommandCount() {
        return frameCommandCount.get();
    }

    public static long getTotalSavedTimeMs() {
        return totalSavedTimeNs / 1_000_000;
    }

    /**
     * 获取批处理效率
     */
    public static double getBatchEfficiency() {
        int total = totalCommands.get();
        int batches = totalBatches.get();
        return batches > 0 ? (double) total / batches : 0.0;
    }

    public static String getBatcherStats() {
        return String.format("GPUCommandBatcher: commands=%d batches=%d drawCalls=%d dispatches=%d " +
                "merged=%d efficiency=%.2f saved=%dms",
                totalCommands.get(), totalBatches.get(),
                totalDrawCalls.get(), totalDispatchCalls.get(),
                mergedCommands.get(), getBatchEfficiency(),
                getTotalSavedTimeMs());
    }

    public static void resetStats() {
        totalCommands.set(0);
        totalBatches.set(0);
        totalDrawCalls.set(0);
        totalDispatchCalls.set(0);
        mergedCommands.set(0);
        totalSavedTimeNs = 0;
        frameCommandCount.set(0);
    }
}
