package com.memory.spi;

/**
 * 调度器 SPI 扩展点。
 *
 * 实现此接口可替换定时任务调度方案（如 cron → 固定间隔 → Quartz）。
 * 用于触发器中的 schedule 类型。
 *
 * 方法数：4
 */
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
}
