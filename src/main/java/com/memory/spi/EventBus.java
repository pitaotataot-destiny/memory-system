package com.memory.spi;

import com.memory.engine.event.LocalEventBus;

import java.util.function.Consumer;

/**
 * 事件总线 SPI 扩展点。
 *
 * 实现此接口可替换事件分发方案（如内存总线 → Kafka → RabbitMQ）。
 * 用于触发器中的 event 类型。
 *
 * 方法数：3
 */
@SPI(name = "event-bus", description = "事件总线扩展点",
     defaultImpl = LocalEventBus.class)
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
     * 清除所有订阅，用于热更新时重置触发器绑定。
     */
    void clearSubscriptions();

    /**
     * 事件数据。
     */
    record Event(String type, String memoryId, java.util.Map<String, Object> payload) {}
}
