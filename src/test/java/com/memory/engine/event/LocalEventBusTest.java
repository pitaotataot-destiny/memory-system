package com.memory.engine.event;

import com.memory.spi.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LocalEventBusTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new LocalEventBus();
    }

    @Test
    void nameReturnsLocal() {
        assertEquals("local", eventBus.name());
    }

    @Test
    void publishDeliversToSubscriber() {
        List<EventBus.Event> received = new CopyOnWriteArrayList<>();
        eventBus.subscribe("memory_created", received::add);

        EventBus.Event event = new EventBus.Event("memory_created", "mem-1", Collections.emptyMap());
        eventBus.publish(event);

        assertEquals(1, received.size());
        assertEquals("memory_created", received.get(0).type());
        assertEquals("mem-1", received.get(0).memoryId());
    }

    @Test
    void multipleSubscribersAllReceive() {
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);
        eventBus.subscribe("test_event", e -> count1.incrementAndGet());
        eventBus.subscribe("test_event", e -> count2.incrementAndGet());

        eventBus.publish(new EventBus.Event("test_event", "id", Collections.emptyMap()));

        assertEquals(1, count1.get());
        assertEquals(1, count2.get());
    }

    @Test
    void unSubscribedEventsAreIgnored() {
        // 没有订阅的事件不应抛异常
        assertDoesNotThrow(() ->
            eventBus.publish(new EventBus.Event("unknown_event", "id", Collections.emptyMap()))
        );
    }

    @Test
    void handlerErrorDoesNotStopOtherHandlers() {
        AtomicInteger success = new AtomicInteger(0);
        eventBus.subscribe("error_test", e -> { throw new RuntimeException("handler failed"); });
        eventBus.subscribe("error_test", e -> success.incrementAndGet());

        // 第一个处理器抛异常不应影响第二个
        eventBus.publish(new EventBus.Event("error_test", "id", Collections.emptyMap()));

        assertEquals(1, success.get());
    }

    @Test
    void payloadIsPreserved() {
        EventBus.Event[] received = new EventBus.Event[1];
        eventBus.subscribe("payload_test", e -> received[0] = e);

        EventBus.Event event = new EventBus.Event("payload_test", "id",
                java.util.Map.of("key", "value"));
        eventBus.publish(event);

        assertNotNull(received[0]);
        assertEquals("value", received[0].payload().get("key"));
    }
}
