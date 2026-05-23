package com.memory.engine.scheduler;

import com.memory.spi.Scheduler;

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
public class DefaultScheduler implements Scheduler {

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
        long initialDelay = 0;
        long interval = parseCronToIntervalMs(cronExpression);

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

    // Parse cron expression to interval in milliseconds
    private long parseCronToIntervalMs(String cronExpression) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
        }

        String minute = parts[0];
        String hour = parts[1];

        // */N minutes
        if (minute.startsWith("*/")) {
            int n = Integer.parseInt(minute.substring(2));
            return n * 60_000L;
        }

        // Fixed minute + */N hours
        if (hour.startsWith("*/")) {
            int n = Integer.parseInt(hour.substring(2));
            return n * 3_600_000L;
        }

        // Fixed time -> once per day
        return 24 * 3_600_000L;
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
                System.err.println("[Scheduler] Task error (cron=" + cronExpression + "): " + e.getMessage());
            }
        };
    }
}
