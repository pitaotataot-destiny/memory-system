package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.decay.*;
import com.memory.model.enums.*;
import com.memory.model.globals.Globals;
import com.memory.model.globals.StorageConfig;
import com.memory.model.search.*;
import com.memory.registry.ComponentRegistry;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.MemoryStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryMgrTest {

    private MemoryRuntimeContext ctx;
    private MemoryMgr mgr;
    private Path testDataDir;

    @BeforeEach
    void setUp() throws IOException {
        testDataDir = Files.createTempDirectory("memory-mgr-test-");
        System.setProperty("memory.store.path", testDataDir.toString());
        MetaModel model = buildMinimalModel();
        ComponentRegistry registry = new ComponentRegistry();
        registry.registerDefaultImplementations();
        registry.assemble(model);
        ctx = new MemoryRuntimeContext(model, registry);
        ctx.start();
        mgr = new MemoryMgr(ctx);
    }

    @AfterEach
    void tearDown() throws IOException {
        ctx.stop();
        // Clean up temp directory
        if (testDataDir != null) {
            try (var files = Files.list(testDataDir)) {
                files.forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
            }
            Files.deleteIfExists(testDataDir);
        }
    }

    @Test
    @Order(1)
    void createAndRead() {
        String id = mgr.create("fact", "{\"content\":\"test memory\"}", Set.of("test"));
        assertNotNull(id);

        String data = mgr.read(id);
        assertNotNull(data);
        assertTrue(data.contains("test memory"));
    }

    @Test
    @Order(2)
    void createValidatesRequiredFields() {
        // Create a type with required field
        MetaModel model = ctx.getMetaModel();
        assertThrows(IllegalArgumentException.class,
            () -> mgr.create("fact", "{\"missing_required\":true}", Set.of()));
    }

    @Test
    @Order(3)
    void updateMemory() {
        String id = mgr.create("fact", "{\"content\":\"original\"}", Set.of());
        assertNotNull(mgr.read(id));

        mgr.update(id, "{\"content\":\"updated\"}");
        String updated = mgr.read(id);
        assertNotNull(updated);
    }

    @Test
    @Order(4)
    void deleteMemory() {
        String id = mgr.create("fact", "{\"content\":\"to delete\"}", Set.of());
        assertTrue(mgr.delete(id));
        assertNull(mgr.read(id));
    }

    @Test
    @Order(5)
    void deleteNonexistentReturnsFalse() {
        assertFalse(mgr.delete("nonexistent-id"));
    }

    @Test
    @Order(6)
    void listAll() {
        mgr.create("fact", "{\"content\":\"a\"}", Set.of());
        mgr.create("fact", "{\"content\":\"b\"}", Set.of());
        mgr.create("fact", "{\"content\":\"c\"}", Set.of());

        assertEquals(3, mgr.count());
        assertEquals(3, mgr.listAll().size());
    }

    @Test
    @Order(7)
    void createWithTagsValidatesConstraints() {
        // Create with many tags — should hit the max constraint
        Set<String> tooManyTags = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            tooManyTags.add("tag" + i);
        }
        assertThrows(IllegalArgumentException.class,
            () -> mgr.create("fact", "{\"content\":\"many tags\"}", tooManyTags));
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

        Map<String, com.memory.model.type.MemoryType> types = new HashMap<>();
        com.memory.model.type.MemoryType fact = new com.memory.model.type.MemoryType();
        fact.setKind(MemoryTypeKind.FACT);
        fact.setDescription("fact");
        com.memory.model.constraint.FieldConstraint content = new com.memory.model.constraint.FieldConstraint();
        content.setRequired(true);
        fact.setFields(Map.of("content", content));
        com.memory.model.constraint.TagConstraint tagConstraint = new com.memory.model.constraint.TagConstraint();
        tagConstraint.setMax(10);
        fact.setTags(tagConstraint);
        fact.setMeta(new com.memory.model.constraint.TypeMeta());
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
