package com.memory.runtime;

import com.memory.model.MetaModel;
import com.memory.model.decay.*;
import com.memory.model.enums.*;
import com.memory.model.globals.Globals;
import com.memory.model.globals.StorageConfig;
import com.memory.model.search.*;
import com.memory.model.type.MemoryType;
import com.memory.spi.ExpressionEngine;
import com.memory.spi.MemoryStore;
import com.memory.spi.Scheduler;
import com.memory.model.constraint.*;
import com.memory.registry.ComponentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRuntimeContextTest {

    private MetaModel testModel;
    private ComponentRegistry registry;
    private MemoryRuntimeContext context;

    @BeforeEach
    void setUp() {
        testModel = buildMinimalModel();
        registry = new ComponentRegistry();
        registry.registerDefaultImplementations();
        registry.assemble(testModel);
        context = new MemoryRuntimeContext(testModel, registry);
        context.start();
    }

    @Test
    void metaModelIsAccessible() {
        assertSame(testModel, context.getMetaModel());
    }

    @Test
    void swapMetaModelWorks() {
        MetaModel newModel = buildMinimalModel();
        newModel.setGlobals(new Globals());
        newModel.getGlobals().setMaxMemorySize(9999);
        context.swapMetaModel(newModel);
        assertEquals(9999, context.getMetaModel().getGlobals().getMaxMemorySize());
    }

    @Test
    void statisticsIncrement() {
        assertEquals(0, context.getTotalQueries());
        context.incrementQueries();
        assertEquals(1, context.getTotalQueries());
        context.incrementWrites();
        assertEquals(1, context.getTotalWrites());
        context.incrementErrors();
        assertEquals(1, context.getTotalErrors());
    }

    @Test
    void hotCacheOperations() {
        assertFalse(context.isHot("mem-1"));
        context.markHot("mem-1", 0.8);
        assertTrue(context.isHot("mem-1"));
        context.evictHot("mem-1");
        assertFalse(context.isHot("mem-1"));
        assertEquals(0, context.getHotCacheSize());
    }

    @Test
    void runningState() {
        assertTrue(context.isRunning());
        context.stop();
        assertFalse(context.isRunning());
    }

    @Test
    void cannotStartTwice() {
        assertThrows(RuntimeStateException.class, () -> context.start());
    }

    @Test
    void registryIsAccessible() {
        assertSame(registry, context.getRegistry());
    }

    @Test
    void jsonStoreIsAccessible() {
        MemoryStore store = context.getStore("json");
        assertNotNull(store);
        assertEquals("json", store.name());

        store.save("ctx-test", "{\"data\":\"test\"}");
        assertEquals("{\"data\":\"test\"}", store.load("ctx-test"));
        assertTrue(store.listAll().contains("ctx-test"));
        store.delete("ctx-test");
    }

    @Test
    void schedulerIsAccessible() {
        Scheduler scheduler = context.getScheduler();
        assertNotNull(scheduler);
        assertEquals("default", scheduler.name());

        // 验证能注册任务
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        scheduler.schedule("*/1 * * * *", latch::countDown);
        try {
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted");
        }
    }

    @Test
    void expressionEngineIsAccessible() {
        ExpressionEngine engine = context.getExpressionEngine();
        assertNotNull(engine);
        assertEquals("default", engine.name());

        assertTrue(engine.evaluate("5 > 3", Map.of()));
        assertFalse(engine.evaluate("1 > 100", Map.of()));
    }

    // ── helpers ──────────────────────────────────────────

    private MetaModel buildMinimalModel() {
        MetaModel model = new MetaModel();
        model.setVersion("1.0");

        Globals globals = new Globals();
        globals.setDefaultType(MemoryTypeKind.FACT);
        globals.setMaxMemorySize(100);
        globals.setDefaultTtlDays(30);
        StorageConfig storage = new StorageConfig();
        storage.setEngine(StorageEngine.JSON);
        globals.setStorage(storage);
        model.setGlobals(globals);

        Map<String, MemoryType> types = new HashMap<>();
        MemoryType fact = new MemoryType();
        fact.setKind(MemoryTypeKind.FACT);
        fact.setDescription("事实");
        fact.setFields(Map.of("content", new FieldConstraint()));
        fact.setTags(new TagConstraint());
        fact.setMeta(new TypeMeta());
        types.put("fact", fact);
        model.setTypes(types);

        DecayConfig dc = new DecayConfig();
        dc.setDailyDecay(0.9);
        dc.setAccessGain(0.05);
        dc.setMinImportance(0.1);
        DecayPolicy decay = new DecayPolicy();
        decay.setDefaultConfig(dc);
        decay.setLifecycle(new LifecycleConfig());
        model.setDecay(decay);

        SearchConfig sc = new SearchConfig();
        EngineConfig ec = new EngineConfig();
        ec.setKind(SearchEngineKind.KEYWORD);
        ec.setEnabled(true);
        sc.setEngines(Map.of("keyword", ec));
        SearchStrategy ss = new SearchStrategy();
        ss.setName("default");
        SearchStep step = new SearchStep();
        step.setEngine(SearchEngineKind.KEYWORD);
        ss.setSteps(List.of(step));
        sc.setStrategies(Map.of("default", ss));
        model.setSearch(sc);

        model.setTriggers(new ArrayList<>());
        return model;
    }
}
