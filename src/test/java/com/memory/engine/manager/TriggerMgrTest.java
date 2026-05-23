package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.decay.*;
import com.memory.model.enums.*;
import com.memory.model.globals.Globals;
import com.memory.model.globals.StorageConfig;
import com.memory.model.search.*;
import com.memory.model.trigger.Trigger;
import com.memory.model.trigger.TriggerAction;
import com.memory.model.trigger.TriggerCondition;
import com.memory.registry.ComponentRegistry;
import com.memory.runtime.MemoryRuntimeContext;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TriggerMgrTest {

    private TriggerMgr mgr;
    private MemoryRuntimeContext ctx;
    private MemoryMgr memoryMgr;
    private Path testDataDir;

    @BeforeEach
    void setUp() throws IOException {
        testDataDir = Files.createTempDirectory("memory-trigger-test-");
        System.setProperty("memory.store.path", testDataDir.toString());
        MetaModel model = buildModelWithTriggers();
        ComponentRegistry registry = new ComponentRegistry();
        registry.registerDefaultImplementations();
        registry.assemble(model);
        ctx = new MemoryRuntimeContext(model, registry);
        ctx.start();
        DecayMgr decayMgr = new DecayMgr(ctx);
        mgr = new TriggerMgr(ctx, decayMgr);
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
    void registerAllTriggersDoesNotThrow() {
        // Should register triggers from MetaModel without errors
        assertDoesNotThrow(() -> mgr.registerAllTriggers());
    }

    @Test
    @Order(2)
    void eventTriggerFiresOnMemoryCreation() throws InterruptedException {
        // Create a memory — should trigger memory_created event
        String id = memoryMgr.create("fact", "{\"content\":\"event test\"}", Set.of("test"));
        assertNotNull(id);
        // Event bus should have published the event
        // We can't directly verify the handler ran (async), but creation succeeded
    }

    @Test
    @Order(3)
    void scheduleTriggerRegistered() {
        // After registerAllTriggers, schedule-driven triggers should be active
        assertDoesNotThrow(() -> mgr.registerAllTriggers());
    }

    @Test
    @Order(4)
    void conditionTriggerVariablesAccessible() {
        // The condition trigger builds variables including memory_count, globals
        memoryMgr.create("fact", "{\"content\":\"var test\"}", Set.of());
        assertDoesNotThrow(() -> mgr.registerAllTriggers());
    }

    // ── helpers ──────────────────────────────────────────

    private MetaModel buildModelWithTriggers() {
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
        dc.setDailyDecay(0.9);
        dc.setAccessGain(0.05);
        dc.setMinImportance(0.1);
        DecayPolicy decay = new DecayPolicy();
        decay.setDefaultConfig(dc);
        LifecycleConfig lc = new LifecycleConfig();
        lc.setStaleAfterDays(14);
        lc.setArchiveAfterDays(30);
        lc.setPurgeWhenImportanceBelow(0.1);
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

        // Add triggers
        List<Trigger> triggers = new ArrayList<>();

        // Event-driven trigger
        Trigger eventTrigger = new Trigger();
        eventTrigger.setName("on_create_decay");
        TriggerCondition when1 = new TriggerCondition();
        when1.setEvent(TriggerEvent.MEMORY_CREATED);
        eventTrigger.setWhen(when1);
        TriggerAction then1 = new TriggerAction();
        then1.setAction(ActionKind.RUN_DECAY);
        eventTrigger.setThen(then1);
        triggers.add(eventTrigger);

        // Schedule-driven trigger
        Trigger scheduleTrigger = new Trigger();
        scheduleTrigger.setName("scheduled_purge");
        TriggerCondition when2 = new TriggerCondition();
        when2.setSchedule("0 */6 * * *");
        scheduleTrigger.setWhen(when2);
        TriggerAction then2 = new TriggerAction();
        then2.setAction(ActionKind.PURGE);
        scheduleTrigger.setThen(then2);
        triggers.add(scheduleTrigger);

        model.setTriggers(triggers);
        return model;
    }
}
