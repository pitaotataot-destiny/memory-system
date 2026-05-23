package com.memory.spi;

import java.util.function.Consumer;

/**
 * 事件总线 SPI 扩展点。
 *
 * 实现此接口可替换事件分发方案（如内存总线 → Kafka → RabbitMQ）。
 * 用于触发器中的 event 类型。
 *
 * 方法数：3
 */
public interface EventBus {

    /**
     * 事件总线标识。如 "local", "kafka"。
     */
    String name();

    /**
     * 订阅事件。
     * @param eventType  事件类型（如 "memory_created", "memory_updated"）
     * @param handler    事件处理回调
     */
    void subscribe(String eventType, Consumer<Event> handler);

    /**
     * 发布事件。
     * @param event 事件对象
     */
    void publish(Event event);

    /**
     * 事件数据。
     */
    record Event(String type, String memoryId, java.util.Map<String, Object> payload) {}
}
