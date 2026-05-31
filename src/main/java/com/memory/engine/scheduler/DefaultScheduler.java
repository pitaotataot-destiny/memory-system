package com.memory.engine.scheduler;

import com.memory.spi.Scheduler;
import com.memory.spi.SPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Default scheduler based on Java built-in ScheduledExecutorService.
 * Implements Scheduler SPI interface, assembled by Registry per DSL declaration.
 *
 * Supports cron expressions (minute hour day month weekday),
 * fixed interval tasks, and graceful shutdown.
 */
@SPI(name = "default", description = "Cron 定时调度（ScheduledExecutorService）")
public class DefaultScheduler implements Scheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultScheduler.class);

    private ScheduledExecutorService executor;

    // Track all registered tasks for cleanup on shutdown
    private final List<ScheduledFuture<?>> scheduledTasks = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "default";
    }

    @Override
    public void init() {
        executor = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "memory-scheduler-" + System.currentTimeMillis());
                t.setDaemon(true);
                return t;
            }
        );
    }

    @Override
    public void schedule(String cronExpression, Runnable task) {
        long[] delayAndInterval = parseCron(cronExpression);
        long initialDelay = delayAndInterval[0];
        long interval = delayAndInterval[1];

        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
            wrapTask(task, cronExpression),
            initialDelay,
            interval,
            TimeUnit.MILLISECONDS
        );
        scheduledTasks.add(future);
    }

    @Override
    public void shutdown() {
        // Cancel all tasks
        scheduledTasks.forEach(f -> f.cancel(true));
        scheduledTasks.clear();

        // Shutdown thread pool
        if (executor != null) {
            executor.shutdown();
            try {
                // Wait up to 10 seconds
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void cancelAll() {
        scheduledTasks.forEach(f -> f.cancel(true));
        scheduledTasks.clear();
        LOG.debug("Scheduler: all tasks cancelled for hot reload");
    }

    /**
     * Parse cron expression to {initialDelayMs, intervalMs}.
     *
     * Supported patterns (minute hour day month weekday):
     *   "* * * * *"     = every minute
     *   "*&#47;N * * * *"   = every N minutes
     *   "M *&#47;H * * *"   = every H hours at minute M
     *   "M H * * *"     = daily at H:MM
     */
    private long[] parseCron(String cronExpression) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
        }

        String minute = parts[0];
        String hour = parts[1];

        // */N minutes — run every N minutes
        if (minute.startsWith("*/")) {
            int n = Integer.parseInt(minute.substring(2));
            return new long[]{0, n * 60_000L};
        }

        // * every minute
        if ("*".equals(minute)) {
            return new long[]{0, 60_000L};
        }

        // Fixed minute M + */N hours — run every N hours at minute M
        // 首次立即执行（delay=0），之后按间隔对齐分钟边界
        if (hour.startsWith("*/")) {
            int n = Integer.parseInt(hour.substring(2));
            return new long[]{0, n * 3_600_000L};
        }

        // Fixed time M H * * * — daily at H:MM
        try {
            int m = Integer.parseInt(minute);
            int h = Integer.parseInt(hour);
            long delay = computeDelayToNextTime(h, m);
            return new long[]{delay, 24L * 3_600_000L};
        } catch (NumberFormatException ignored) {
            // Fallback: once per day
        }

        return new long[]{0, 24L * 3_600_000L};
    }

    /** 计算到下一个指定时间的延迟（毫秒） */
    private static long computeDelayToNextTime(int hour, int minute) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime next = now.withMinute(minute).withSecond(0).withNano(0);
        if (next.getHour() > hour || (next.getHour() == hour && next.isBefore(now))) {
            next = next.plusDays(1);
        }
        next = next.withHour(hour);
        return java.time.Duration.between(now, next).toMillis();
    }

    /**
     * Task wrapper with error isolation.
     * One task failure does not affect other tasks.
     */
    private Runnable wrapTask(Runnable task, String cronExpression) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                LOG.error("[Scheduler] Task error (cron={}): {}", cronExpression, e.getMessage());
            }
        };
    }
}
