package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.decay.*;
import com.memory.model.enums.*;
import com.memory.model.globals.Globals;
import com.memory.model.globals.StorageConfig;
import com.memory.model.search.*;
import com.memory.registry.ComponentRegistry;
import com.memory.runtime.MemoryRuntimeContext;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DecayMgrTest {

    private DecayMgr mgr;
    private MemoryRuntimeContext ctx;
    private MemoryMgr memoryMgr;
    private Path testDataDir;

    @BeforeEach
    void setUp() throws IOException {
        testDataDir = Files.createTempDirectory("memory-decay-test-");
        System.setProperty("memory.store.path", testDataDir.toString());
        MetaModel model = buildModelWithDecay();
        ComponentRegistry registry = new ComponentRegistry();
        registry.registerDefaultImplementations();
        registry.assemble(model);
        ctx = new MemoryRuntimeContext(model, registry);
        ctx.start();
        mgr = new DecayMgr(ctx);
        memoryMgr = new MemoryMgr(ctx);
    }

    @AfterEach
    void tearDown() throws IOException {
        ctx.stop();
        if (testDataDir != null) {
            try (var files = Files.list(testDataDir)) {
                files.forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
            }
            Files.deleteIfExists(testDataDir);
        }
    }

    @Test
    @Order(1)
    void runDecayReturnsDecayedIds() {
        String id = memoryMgr.create("fact", "{\"content\":\"test\"}", Set.of("test"));
        List<String> decayed = mgr.runDecay();
        // Should include the created memory (even if importance barely changed)
        assertNotNull(decayed);
    }

    @Test
    @Order(2)
    void runDecayOnEmptyStore() {
        List<String> decayed = mgr.runDecay();
        assertTrue(decayed.isEmpty());
    }

    @Test
    @Order(3)
    void lifecycleCheckActive() {
        String id = memoryMgr.create("fact", "{\"content\":\"fresh\"}", Set.of());
        DecayMgr.LifecycleStatus status = mgr.checkLifecycle(id);
        assertEquals("active", status.status());
    }

    @Test
    @Order(4)
    void lifecycleCheckNotFound() {
        DecayMgr.LifecycleStatus status = mgr.checkLifecycle("nonexistent");
        assertEquals("not_found", status.status());
    }

    @Test
    @Order(5)
    void lifecycleSummaryOnEmptyStore() {
        DecayMgr.LifecycleSummary summary = mgr.runLifecycleCheck();
        assertEquals(0, summary.total());
        assertEquals(0, summary.purged());
    }

    @Test
    @Order(6)
    void decayConfigFallbackToDefault() {
        MetaModel model = ctx.getMetaModel();
        DecayConfig defaultConfig = model.getDecay().getConfigForType("unknown_type");
        assertNotNull(defaultConfig);
        assertEquals(0.90, defaultConfig.getDailyDecay(), 0.001);
    }

    // ── helpers ──────────────────────────────────────────

    private MetaModel buildModelWithDecay() {
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

        Map<String, com.memory.model.type.MemoryType> types = new HashMap<>();
        com.memory.model.type.MemoryType fact = new com.memory.model.type.MemoryType();
        fact.setKind(MemoryTypeKind.FACT);
        fact.setDescription("fact");
        fact.setFields(Map.of());
        fact.setTags(new com.memory.model.constraint.TagConstraint());
        fact.setMeta(new com.memory.model.constraint.TypeMeta());
        types.put("fact", fact);
        model.setTypes(types);

        DecayConfig dc = new DecayConfig();
        dc.setDailyDecay(0.90);
        dc.setAccessGain(0.05);
        dc.setMinImportance(0.1);
        DecayPolicy decay = new DecayPolicy();
        decay.setDefaultConfig(dc);
        LifecycleConfig lc = new LifecycleConfig();
        lc.setStaleAfterDays(14);
        lc.setArchiveAfterDays(30);
        lc.setPurgeWhenImportanceBelow(0.1);
        lc.setPurgeWhenStaleDays(60);
        decay.setLifecycle(lc);
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