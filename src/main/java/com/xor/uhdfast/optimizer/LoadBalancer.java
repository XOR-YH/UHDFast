package com.xor.uhdfast.optimizer;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadBalancer {

    private static final ExecutorService workerPool;
    private static final ExecutorService gpuOffloadPool;

    private static final AtomicInteger activeCpuTasks = new AtomicInteger(0);
    private static final AtomicInteger activeGpuTasks = new AtomicInteger(0);
    private static final AtomicInteger totalTasksOffloaded = new AtomicInteger(0);
    private static final AtomicInteger totalTasksCompleted = new AtomicInteger(0);

    private static long totalCpuTimeNs = 0;
    private static long totalGpuTimeNs = 0;
    private static long lastFrameTime = 0;

    private static final int MAX_CPU_THREADS;
    private static final int MAX_GPU_TASKS = 32;

    private static volatile boolean isOverloaded = false;
    private static volatile double currentLoad = 0.0;

    static {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        MAX_CPU_THREADS = Math.max(2, availableProcessors / 2);

        workerPool = new ThreadPoolExecutor(
                MAX_CPU_THREADS,
                MAX_CPU_THREADS,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1024),
                new ThreadFactory() {
                    private int count = 0;

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "UHDFast-Worker-" + count++);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        gpuOffloadPool = new ThreadPoolExecutor(
                2,
                4,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                new ThreadFactory() {
                    private int count = 0;

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "UHDFast-GPU-" + count++);
                        t.setPriority(Thread.NORM_PRIORITY + 1);
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    public static void submitCpuTask(Runnable task) {
        if (isOverloaded) {
            return;
        }

        activeCpuTasks.incrementAndGet();
        workerPool.submit(() -> {
            try {
                task.run();
            } finally {
                activeCpuTasks.decrementAndGet();
                totalTasksCompleted.incrementAndGet();
            }
        });
    }

    public static void submitGpuTask(Runnable task) {
        if (activeGpuTasks.get() >= MAX_GPU_TASKS) {
            return;
        }

        activeGpuTasks.incrementAndGet();
        totalTasksOffloaded.incrementAndGet();

        gpuOffloadPool.submit(() -> {
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                long elapsed = System.nanoTime() - start;
                totalGpuTimeNs += elapsed;
                activeGpuTasks.decrementAndGet();
                totalTasksCompleted.incrementAndGet();
            }
        });
    }

    public static void onFrameStart() {
        long currentTime = System.nanoTime();
        if (lastFrameTime > 0) {
            long frameTime = currentTime - lastFrameTime;
            updateLoadFactor(frameTime);
        }
        lastFrameTime = currentTime;
    }

    private static void updateLoadFactor(long frameTimeNs) {
        double targetFrameTime = 16_666_666.0;
        currentLoad = Math.min(1.0, (double) frameTimeNs / targetFrameTime);

        if (currentLoad > 0.9) {
            isOverloaded = true;
        } else if (currentLoad < 0.5) {
            isOverloaded = false;
        }
    }

    public static double getCurrentLoad() {
        return currentLoad;
    }

    public static boolean isOverloaded() {
        return isOverloaded;
    }

    public static int getActiveCpuTasks() {
        return activeCpuTasks.get();
    }

    public static int getActiveGpuTasks() {
        return activeGpuTasks.get();
    }

    public static int getTotalTasksOffloaded() {
        return totalTasksOffloaded.get();
    }

    public static int getTotalTasksCompleted() {
        return totalTasksCompleted.get();
    }

    public static long getTotalCpuTimeNs() {
        return totalCpuTimeNs;
    }

    public static long getTotalGpuTimeNs() {
        return totalGpuTimeNs;
    }

    public static double getOffloadRate() {
        int total = totalTasksOffloaded.get() + totalTasksCompleted.get();
        return total > 0 ? (double) totalTasksOffloaded.get() / total * 100.0 : 0.0;
    }

    public static void shutdown() {
        workerPool.shutdown();
        gpuOffloadPool.shutdown();
    }
}
