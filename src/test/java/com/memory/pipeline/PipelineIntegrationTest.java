package com.memory.pipeline;

import com.memory.dsl.DSLParser;
import com.memory.model.MetaModel;
import com.memory.model.enums.MemoryTypeKind;
import com.memory.registry.ComponentRegistry;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.EventBus;
import com.memory.spi.ExpressionEngine;
import com.memory.spi.MemoryStore;
import com.memory.spi.Scheduler;
import com.memory.spi.SearchProvider;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试 — 一个用例走完全部核心流程。
 *
 * 流程：
 *   Phase 1: DSL 加载 → MetaModel
 *   Phase 2: Registry 装配
 *   Phase 3: Runtime Context 启动
 *   Phase 4: DSL 规则验证 + 搜索
 *   Phase 5: 存储引擎
 *   Phase 6: 调度器
 *   Phase 7: 事件总线
 *   Phase 8: 统计 + 热缓存 + 热更新
 *   Phase 9: 优雅关闭
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipelineIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PipelineIntegrationTest.class);

    private static MetaModel model;
    private static ComponentRegistry registry;
    private static MemoryRuntimeContext context;

    @BeforeAll
    static void globalSetup() {
        // Phase 1
        log.info("[Pipeline] Phase 1: Loading DSL...");
        DSLParser parser = new DSLParser();
        Path dslPath = Path.of("memory_dsl.yaml");
        if (!dslPath.toFile().exists()) {
            throw new IllegalStateException("memory_dsl.yaml not found. Run from project root.");
        }
        model = parser.parse(dslPath);
        assertNotNull(model);
        assertEquals("1.0", model.getVersion());
        log.info("[Pipeline]   ✓ DSL parsed (version={})", model.getVersion());
        log.info("[Pipeline]   ✓ Types: {}", model.getTypes().keySet());
        log.info("[Pipeline]   ✓ Search strategies: {}", model.getSearch().getStrategies().keySet());
        log.info("[Pipeline]   ✓ Triggers: {}", model.getTriggers().size());

        // Phase 2
        log.info("[Pipeline] Phase 2: Assembling Registry...");
        registry = new ComponentRegistry();
        registry.registerDefaultImplementations();
        registry.assemble(model);
        assertEquals(ComponentRegistry.State.STARTED, registry.getState());
        log.info("[Pipeline]   ✓ Registry assembled (state={})", registry.getState());
        log.info("[Pipeline]   ✓ Registered components: {}", registry.getRegisteredKeys());

        // Phase 3
        log.info("[Pipeline] Phase 3: Starting Runtime Context...");
        context = new MemoryRuntimeContext(model, registry);
        context.start();
        assertTrue(context.isRunning());
        log.info("[Pipeline]   ✓ Runtime Context started");
    }

    @AfterAll
    static void globalTeardown() {
        log.info("[Pipeline] Phase 9: Shutting down...");
        if (context != null && context.isRunning()) {
            context.stop();
            assertFalse(context.isRunning());
        }
        log.info("[Pipeline]   ✓ Runtime Context stopped");
    }

    @Test
    @Order(1)
    @DisplayName("Phase 4: DSL 规则验证")
    void phase4_dslRulesAccessible() {
        log.info("[Pipeline] Phase 4: Verifying DSL rules...");
        MetaModel m = context.getMetaModel();
        assertTrue(m.getType("fact").isPresent());
        assertTrue(m.getType("preference").isPresent());
        assertTrue(m.getType("context").isPresent());
        assertTrue(m.getType("reference").isPresent());
        assertNotNull(m.getDecay());
        assertEquals(0.92, m.getDecay().getDefaultConfig().getDailyDecay(), 0.001);
        assertNotNull(m.getSearch().getDefaultStrategy());
        assertFalse(m.getSearch().getDefaultStrategy().getSteps().isEmpty());
        log.info("[Pipeline]   ✓ DSL rules accessible");
    }

    @Test
    @Order(2)
    @DisplayName("Phase 4: 搜索提供者工作正常")
    void phase4_searchProviderWorks() {
        log.info("[Pipeline] Phase 4: Testing SearchProvider...");
        SearchProvider keyword = registry.get("search:keyword");
        assertNotNull(keyword);

        keyword.init(Map.of());
        keyword.index("mem-1", "java programming language");
        keyword.index("mem-2", "python data science");
        keyword.index("mem-3", "java python programming");

        var results = keyword.search("java", 10);
        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertTrue(results.size() >= 2);
        List<String> ids = results.stream().map(SearchProvider.SearchResult::memoryId).toList();
        assertTrue(ids.contains("mem-1"));
        assertTrue(ids.contains("mem-3"));

        log.info("[Pipeline]   ✓ SearchProvider returned {} results", results.size());
    }

    @Test
    @Order(3)
    @DisplayName("Phase 5: 存储引擎 — 保存/加载/删除")
    void phase5_storeWorks() {
        log.info("[Pipeline] Phase 5: Testing MemoryStore...");
        MemoryStore store = context.getStore("json");
        assertNotNull(store);
        assertEquals("json", store.name());

        store.save("mem-1", "{\"type\":\"fact\",\"content\":\"java编程\"}");
        String loaded = store.load("mem-1");
        assertNotNull(loaded);
        assertTrue(loaded.contains("java编程"));

        Set<String> ids = store.listAll();
        assertTrue(ids.contains("mem-1"));

        assertTrue(store.delete("mem-1"));
        assertNull(store.load("mem-1"));

        log.info("[Pipeline]   ✓ MemoryStore save/load/delete work");
    }

    @Test
    @Order(4)
    @DisplayName("Phase 6: 调度器 — 注册/执行")
    void phase6_schedulerWorks() {
        log.info("[Pipeline] Phase 6: Testing Scheduler...");
        Scheduler scheduler = context.getScheduler();
        assertNotNull(scheduler);
        assertEquals("default", scheduler.name());

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);

        scheduler.schedule("*/1 * * * *", () -> {
            count.incrementAndGet();
            latch.countDown();
        });

        try {
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while waiting for scheduled task");
        }

        assertTrue(count.get() >= 1);
        log.info("[Pipeline]   ✓ Scheduler executed task (count={})", count.get());
    }

    @Test
    @Order(5)
    @DisplayName("Phase 7: 表达式引擎 — 条件求值")
    void phase7_expressionWorks() {
        log.info("[Pipeline] Phase 7: Testing ExpressionEngine...");
        ExpressionEngine engine = context.getExpressionEngine();
        assertNotNull(engine);
        assertEquals("default", engine.name());

        Map<String, Object> globals = Map.of("max_memory_size", 5000);
        Map<String, Object> vars = Map.of(
            "globals", globals,
            "memory_count", 5100
        );

        assertTrue(engine.evaluate("memory_count > globals.max_memory_size", vars));
        assertFalse(engine.evaluate("memory_count < 1000", vars));

        log.info("[Pipeline]   ✓ ExpressionEngine evaluated conditions");
    }

    @Test
    @Order(6)
    @DisplayName("Phase 8: 事件总线 — 发布/订阅")
    void phase7_eventBusWorks() {
        log.info("[Pipeline] Phase 7: Testing EventBus...");
        EventBus eventBus = registry.get("eventbus:local");
        assertNotNull(eventBus);
        assertEquals("local", eventBus.name());

        java.util.List<EventBus.Event> received = new java.util.ArrayList<>();
        eventBus.subscribe("memory_created", received::add);

        EventBus.Event evt = new EventBus.Event(
            "memory_created",
            "mem-1",
            Map.of("type", "fact", "content", "test")
        );
        eventBus.publish(evt);

        assertEquals(1, received.size());
        assertEquals("memory_created", received.get(0).type());
        assertEquals("mem-1", received.get(0).memoryId());

        log.info("[Pipeline]   ✓ EventBus delivered event: type={}", received.get(0).type());
    }

    @Test
    @Order(6)
    @DisplayName("Phase 8: 统计指标")
    void phase8_metricsTrackable() {
        log.info("[Pipeline] Phase 8: Testing Metrics...");
        assertEquals(0, context.getTotalQueries());
        assertEquals(0, context.getTotalWrites());
        assertEquals(0, context.getTotalErrors());

        context.incrementQueries();
        context.incrementQueries();
        context.incrementWrites();

        assertEquals(2, context.getTotalQueries());
        assertEquals(1, context.getTotalWrites());

        log.info("[Pipeline]   ✓ Metrics: queries={}, writes={}, errors={}",
            context.getTotalQueries(), context.getTotalWrites(), context.getTotalErrors());
    }

    @Test
    @Order(7)
    @DisplayName("Phase 8: 热缓存")
    void phase8_hotCacheWorks() {
        log.info("[Pipeline] Phase 8: Testing Hot Cache...");
        String memId = "hot-mem-1";
        assertFalse(context.isHot(memId));
        context.markHot(memId, 0.85);
        assertTrue(context.isHot(memId));
        context.evictHot(memId);
        assertFalse(context.isHot(memId));
        log.info("[Pipeline]   ✓ Hot Cache operations work");
    }

    @Test
    @Order(8)
    @DisplayName("Phase 8: MetaModel 热更新")
    void phase8_hotUpdateWorks() {
        log.info("[Pipeline] Phase 8: Testing Hot Update...");
        int originalMaxSize = context.getMetaModel().getGlobals().getMaxMemorySize();

        MetaModel newModel = new MetaModel();
        newModel.setVersion("1.1");
        com.memory.model.globals.Globals globals = new com.memory.model.globals.Globals();
        globals.setMaxMemorySize(99999);
        globals.setDefaultType(MemoryTypeKind.FACT);
        globals.setDefaultTtlDays(30);
        com.memory.model.globals.StorageConfig storage = new com.memory.model.globals.StorageConfig();
        storage.setEngine(com.memory.model.enums.StorageEngine.JSON);
        globals.setStorage(storage);
        newModel.setGlobals(globals);

        context.swapMetaModel(newModel);

        assertEquals(99999, context.getMetaModel().getGlobals().getMaxMemorySize());
        assertEquals("1.1", context.getMetaModel().getVersion());

        log.info("[Pipeline]   ✓ Hot Update: maxSize {} → 99999", originalMaxSize);
    }
}
