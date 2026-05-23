package com.memory.registry;

import com.memory.model.MetaModel;
import com.memory.model.decay.*;
import com.memory.model.globals.Globals;
import com.memory.model.search.*;
import com.memory.model.trigger.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ComponentRegistryTest {

    private MetaModel testModel;

    @BeforeEach
    void setUp() {
        testModel = buildMinimalModel();
    }

    private void assembleWithDefaults(ComponentRegistry registry) {
        registry.registerDefaultImplementations();
        registry.assemble(testModel);
    }

    @Test
    void registryAssemblesSuccessfully() {
        ComponentRegistry registry = new ComponentRegistry();
        assembleWithDefaults(registry);
        assertEquals(ComponentRegistry.State.STARTED, registry.getState());
    }

    @Test
    void cannotAssembleTwice() {
        ComponentRegistry registry = new ComponentRegistry();
        assembleWithDefaults(registry);
        assertThrows(RegistryException.class, () -> registry.assemble(testModel));
    }

    @Test
    void cannotRegisterAfterStarted() {
        ComponentRegistry registry = new ComponentRegistry();
        assembleWithDefaults(registry);
        assertThrows(RegistryException.class, () -> registry.register("test", "value"));
    }

    @Test
    void metaModelIsStored() {
        ComponentRegistry registry = new ComponentRegistry();
        assembleWithDefaults(registry);
        assertSame(testModel, registry.getMetaModel());
    }

    @Test
    void registeredKeysContainStoreAndSearch() {
        ComponentRegistry registry = new ComponentRegistry();
        assembleWithDefaults(registry);
        Set<String> keys = registry.getRegisteredKeys();
        assertTrue(keys.contains("store:json"));
        assertTrue(keys.contains("search:keyword"));
    }

    @Test
    void storeInstanceIsRetrievable() {
        ComponentRegistry registry = new ComponentRegistry();
        assembleWithDefaults(registry);
        com.memory.spi.MemoryStore store = registry.get("store:json");
        assertNotNull(store, "store:json should return a real instance, not placeholder");
        assertEquals("json", store.name());
    }

    // ── helpers ──────────────────────────────────────────

    private MetaModel buildMinimalModel() {
        MetaModel model = new MetaModel();
        model.setVersion("1.0");

        Globals globals = new Globals();
        globals.setDefaultType(com.memory.model.enums.MemoryTypeKind.FACT);
        globals.setMaxMemorySize(100);
        globals.setDefaultTtlDays(30);
        com.memory.model.globals.StorageConfig storage = new com.memory.model.globals.StorageConfig();
        storage.setEngine(com.memory.model.enums.StorageEngine.JSON);
        globals.setStorage(storage);
        model.setGlobals(globals);

        Map<String, com.memory.model.type.MemoryType> types = new HashMap<>();
        com.memory.model.type.MemoryType fact = new com.memory.model.type.MemoryType();
        fact.setKind(com.memory.model.enums.MemoryTypeKind.FACT);
        fact.setDescription("事实");
        fact.setFields(Map.of("content", new com.memory.model.constraint.FieldConstraint()));
        fact.setTags(new com.memory.model.constraint.TagConstraint());
        fact.setMeta(new com.memory.model.constraint.TypeMeta());
        types.put("fact", fact);
        model.setTypes(types);

        DecayConfig defaultDecay = new DecayConfig();
        defaultDecay.setDailyDecay(0.9);
        defaultDecay.setAccessGain(0.05);
        defaultDecay.setMinImportance(0.1);
        DecayPolicy decay = new DecayPolicy();
        decay.setDefaultConfig(defaultDecay);
        decay.setLifecycle(new LifecycleConfig());
        model.setDecay(decay);

        SearchConfig searchConfig = new SearchConfig();
        Map<String, EngineConfig> engines = new LinkedHashMap<>();
        EngineConfig keywordEngine = new EngineConfig();
        keywordEngine.setKind(com.memory.model.enums.SearchEngineKind.KEYWORD);
        keywordEngine.setEnabled(true);
        keywordEngine.setParams(Map.of());
        engines.put("keyword", keywordEngine);
        searchConfig.setEngines(engines);

        SearchStrategy defaultStrategy = new SearchStrategy();
        defaultStrategy.setName("default");
        SearchStep step = new SearchStep();
        step.setEngine(com.memory.model.enums.SearchEngineKind.KEYWORD);
        defaultStrategy.setSteps(List.of(step));
        searchConfig.setStrategies(Map.of("default", defaultStrategy));
        model.setSearch(searchConfig);

        model.setTriggers(new ArrayList<>());
        return model;
    }
}
