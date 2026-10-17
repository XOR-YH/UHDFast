package com.xor.uhdfast.optimizer;

import com.xor.uhdfast.UHDFast;
import com.xor.uhdfast.gpu.IntelXeDetector;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class XeLoadOptimizer {

    private static ExecutorService workerPool;

    private static final AtomicInteger totalTasksSubmitted = new AtomicInteger(0);
    private static final AtomicInteger totalTasksCompleted = new AtomicInteger(0);
    private static final AtomicInteger totalTasksFailed = new AtomicInteger(0);
    private static final AtomicLong totalLoadTimeNs = new AtomicLong(0);

    private static volatile boolean initialized = false;

    private static int workerThreadCount;

    public static void initialize() {
        if (initialized)
            return;

        long start = System.nanoTime();

        int cpuCores = Runtime.getRuntime().availableProcessors();

        // 大幅减少线程数：避免过多线程导致上下文切换开销
        // 16核CPU + Sodium 10线程 + DH多线程 = 30+线程争抢CPU
        // 进一步降低到 cpuCores/4 以减轻线程争用
        workerThreadCount = Math.max(2, cpuCores / 4);

        workerPool = new ThreadPoolExecutor(
                workerThreadCount,
                workerThreadCount,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new XeThreadFactory("XeLoadWorker"),
                new ThreadPoolExecutor.CallerRunsPolicy());

        initialized = true;

        UHDFast.LOGGER.info("--- Xe Load Optimizer ---");
        UHDFast.LOGGER.info("  CPU Cores: {}", cpuCores);
        UHDFast.LOGGER.info("  Worker Threads: {}", workerThreadCount);
        UHDFast.LOGGER.info("  Initialized in {} ms", String.format("%.2f", (System.nanoTime() - start) / 1_000_000.0));
    }

    /**
     * 直接提交区块加载任务到工作线程池。
     * 移除了冗余的 PriorityBlockingQueue + 消费线程模式，
     * 直接使用线程池的工作队列，减少线程争用和延迟。
     */
    public static void submitChunkLoadTask(int chunkX, int chunkZ, double playerX, double playerZ) {
        if (!initialized)
            return;

        double dx = (chunkX * 16 + 8) - playerX;
        double dz = (chunkZ * 16 + 8) - playerZ;
        double distanceSq = dx * dx + dz * dz;

        int priority = calculatePriority(distanceSq);

        workerPool.submit(() -> {
            try {
                long taskStart = System.nanoTime();
                totalTasksSubmitted.incrementAndGet();
                // 处理区块加载（实际实现将调用GPU或CPU加载区块）
                UHDFast.LOGGER.debug("XeLoadOptimizer: Processing chunk load task ({}, {}), priority={}",
                        chunkX, chunkZ, priority);
                totalTasksCompleted.incrementAndGet();
                totalLoadTimeNs.addAndGet(System.nanoTime() - taskStart);
            } catch (Exception e) {
                totalTasksFailed.incrementAndGet();
            }
        });
    }

    public static void submitParallelLoadTask(Runnable task) {
        if (!initialized)
            return;
        workerPool.submit(task);
    }

    public static void shutdown() {
        if (workerPool != null) {
            workerPool.shutdown();
        }
    }

    private static int calculatePriority(double distanceSq) {
        // 用平方距离替代 sqrt：32^2=1024, 64^2=4096, 128^2=16384, 256^2=65536
        if (distanceSq < 1024.0)
            return 0;
        if (distanceSq < 4096.0)
            return 1;
        if (distanceSq < 16384.0)
            return 2;
        if (distanceSq < 65536.0)
            return 3;
        return 4;
    }

    public static int getWorkerThreadCount() {
        return workerThreadCount;
    }

    public static int getTotalTasksSubmitted() {
        return totalTasksSubmitted.get();
    }

    public static int getTotalTasksCompleted() {
        return totalTasksCompleted.get();
    }

    public static int getTotalTasksFailed() {
        return totalTasksFailed.get();
    }

    public static double getAverageLoadTimeMs() {
        int completed = totalTasksCompleted.get();
        return completed > 0 ? (totalLoadTimeNs.get() / (double) completed) / 1_000_000.0 : 0.0;
    }

    public static double getCompletionRate() {
        int total = totalTasksSubmitted.get();
        return total > 0 ? (double) totalTasksCompleted.get() / total * 100.0 : 0.0;
    }

    public static void logStats() {
        UHDFast.LOGGER.info("--- Xe Load Optimizer Stats ---");
        UHDFast.LOGGER.info("  Tasks Submitted: {}", totalTasksSubmitted.get());
        UHDFast.LOGGER.info("  Tasks Completed: {}", totalTasksCompleted.get());
        UHDFast.LOGGER.info("  Tasks Failed: {}", totalTasksFailed.get());
        UHDFast.LOGGER.info("  Avg Load Time: {:.2f} ms", getAverageLoadTimeMs());
        UHDFast.LOGGER.info("  Completion Rate: {:.1f}%", getCompletionRate());
        UHDFast.LOGGER.info("---------------------------------");
    }

    public static void resetStats() {
        totalTasksSubmitted.set(0);
        totalTasksCompleted.set(0);
        totalTasksFailed.set(0);
        totalLoadTimeNs.set(0);
    }

    private static class XeThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        XeThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
            t.setDaemon(true);
            // 适度低优先级，避免完全饿死加载线程
            t.setPriority(Thread.MIN_PRIORITY + 2);
            return t;
        }
    }
}