package com.memory.spi;

import com.memory.engine.scheduler.DefaultScheduler;

/**
 * 调度器 SPI 扩展点。
 *
 * 实现此接口可替换定时任务调度方案（如 cron → 固定间隔 → Quartz）。
 * 用于触发器中的 schedule 类型。
 *
 * 方法数：4
 */
@SPI(name = "scheduler", description = "调度器扩展点",
     defaultImpl = DefaultScheduler.class)
public interface Scheduler {

    /**
     * 调度器标识。如 "cron", "fixed"。
     */
    String name();

    /**
     * 初始化调度器。
     */
    void init();

    /**
     * 注册定时任务。
     * @param cronExpression cron 表达式（分钟 小时 日 月 周）
     * @param task           任务回调
     */
    void schedule(String cronExpression, Runnable task);

    /**
     * 关闭调度器，停止所有定时任务。
     */
    void shutdown();

    /**
     * 取消所有已注册的定时任务（不关闭线程池）。
     * 用于热更新时重新注册触发器。
     */
    void cancelAll();
}
