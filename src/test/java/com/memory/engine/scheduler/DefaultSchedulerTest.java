package com.memory.engine.scheduler;

import com.memory.spi.Scheduler;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DefaultSchedulerTest {

    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultScheduler();
        scheduler.init();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    @Order(1)
    void nameReturnsDefault() {
        assertEquals("default", scheduler.name());
    }

    @Test
    @Order(2)
    void scheduleRunsImmediately() throws InterruptedException {
        // */1 * * * * = every 1 minute, but initial delay is 0 so it runs immediately
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.schedule("*/1 * * * *", () -> {
            count.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS),
            "Task should run at least once");
        assertTrue(count.get() >= 1);
    }

    @Test
    @Order(3)
    void errorIsolation() throws InterruptedException {
        AtomicInteger goodCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        // Register a failing task
        scheduler.schedule("*/1 * * * *", () -> {
            throw new RuntimeException("task error");
        });

        // Register a good task
        scheduler.schedule("*/1 * * * *", () -> {
            goodCount.incrementAndGet();
            latch.countDown();
        });

        // Good task should still execute
        assertTrue(latch.await(5, TimeUnit.SECONDS),
            "Good task should still run despite bad task throwing exceptions");
        assertTrue(goodCount.get() >= 1);
    }

    @Test
    @Order(4)
    void shutdownStopsAllTasks() throws InterruptedException {
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.schedule("*/1 * * * *", () -> {
            count.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        int beforeShutdown = count.get();

        scheduler.shutdown();

        // Wait to confirm tasks no longer execute
        Thread.sleep(500);
        assertEquals(beforeShutdown, count.get(),
            "Task should not run after shutdown");
    }

    @Test
    @Order(5)
    void multipleTasksRunConcurrently() throws InterruptedException {
        AtomicInteger task1Count = new AtomicInteger(0);
        AtomicInteger task2Count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        scheduler.schedule("*/1 * * * *", () -> {
            task1Count.incrementAndGet();
            latch.countDown();
        });
        scheduler.schedule("*/1 * * * *", () -> {
            task2Count.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(task1Count.get() >= 1);
        assertTrue(task2Count.get() >= 1);
    }

    @Test
    @Order(6)
    void invalidCronThrowsException() {
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.schedule("invalid", () -> {}));
    }

    @Test
    @Order(7)
    void hourlyCronParsesCorrectly() throws InterruptedException {
        // 0 */2 * * * = every 2 hours
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.schedule("0 */2 * * *", () -> {
            count.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(count.get() >= 1);
    }
}
