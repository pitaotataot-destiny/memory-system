package com.memory.dsl;

import com.memory.model.MetaModel;
import com.memory.model.decay.DecayConfig;
import com.memory.model.search.SearchStrategy;
import com.memory.model.type.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DSLParserTest {

    private DSLParser parser;
    private MetaModel model;

    @BeforeEach
    void setUp() {
        parser = new DSLParser();
        Path dslPath = Path.of("memory_dsl.yaml");
        if (!dslPath.toFile().exists()) {
            throw new IllegalStateException("memory_dsl.yaml not found. Run from project root.");
        }
        model = parser.parse(dslPath);
    }

    @Test
    void parsesVersion() {
        assertEquals("1.0", model.getVersion());
    }

    @Test
    void parsesGlobals() {
        assertEquals(5000, model.getGlobals().getMaxMemorySize());
        assertEquals(30, model.getGlobals().getDefaultTtlDays());
        assertEquals("json", model.getGlobals().getStorage().getEngine().getValue());
    }

    @Test
    void parsesTypes() {
        assertTrue(model.getTypes().containsKey("fact"));
        assertTrue(model.getTypes().containsKey("preference"));
        assertTrue(model.getTypes().containsKey("context"));
        assertTrue(model.getTypes().containsKey("reference"));

        MemoryType fact = model.getType("fact").orElseThrow();
        assertTrue(fact.getFields().containsKey("content"));
        assertTrue(fact.getMeta().getUniqueBy().contains("content"));
    }

    @Test
    void parsesDecayPolicy() {
        assertEquals(0.92, model.getDecay().getDefaultConfig().getDailyDecay(), 0.001);
        assertEquals(0.95, model.getDecay().getConfigForType("fact").getDailyDecay(), 0.001);
        assertEquals(0.85, model.getDecay().getConfigForType("context").getDailyDecay(), 0.001);
    }

    @Test
    void parsesSearchStrategies() {
        SearchStrategy defaultStrategy = model.getSearch().getDefaultStrategy();
        assertNotNull(defaultStrategy);
        assertEquals("default", defaultStrategy.getName());
        assertEquals(2, defaultStrategy.getSteps().size());
        assertEquals(10, defaultStrategy.getLimit());
    }

    @Test
    void parsesTriggers() {
        assertEquals(4, model.getTriggers().size());
        assertEquals("auto_decay_check", model.getTriggers().get(0).getName());
    }

    @Test
    void typeOverrideFallback() {
        DecayConfig referenceConfig = model.getDecay().getConfigForType("reference");
        assertEquals(0.90, referenceConfig.getDailyDecay(), 0.001);

        DecayConfig unknownConfig = model.getDecay().getConfigForType("unknown_type");
        assertEquals(0.92, unknownConfig.getDailyDecay(), 0.001); // fallback to default
    }
}
