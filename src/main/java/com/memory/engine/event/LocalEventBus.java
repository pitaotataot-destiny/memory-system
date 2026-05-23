package com.memory.engine.event;

import com.memory.spi.EventBus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 内存事件总线 — 本地进程内事件分发。
 * 实现 EventBus SPI 接口，由 Registry 按 DSL 声明装配。
 *
 * 支持同步事件分发，适合单机部署场景。
 * 分布式场景可替换为 Kafka/RabbitMQ 实现。
 */
public class LocalEventBus implements EventBus {

    // 事件类型 → 处理器列表
    private final Map<String, List<Consumer<Event>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "local";
    }

    @Override
    public void subscribe(String eventType, Consumer<Event> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public void publish(Event event) {
        List<Consumer<Event>> handlers = subscribers.get(event.type());
        if (handlers == null) return;
        for (Consumer<Event> handler : handlers) {
            try {
                handler.accept(event);
            } catch (Exception e) {
                // 单个处理器失败不影响其他处理器
                System.err.println("EventBus handler error for event '" + event.type() + "': " + e.getMessage());
            }
        }
    }
}
