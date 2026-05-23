package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.decay.*;
import com.memory.model.enums.*;
import com.memory.model.globals.Globals;
import com.memory.model.globals.StorageConfig;
import com.memory.model.search.*;
import com.memory.registry.ComponentRegistry;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.SearchProvider;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchMgrTest {

    private SearchMgr mgr;
    private MemoryRuntimeContext ctx;
    private Path testDataDir;

    @BeforeEach
    void setUp() throws IOException {
        testDataDir = Files.createTempDirectory("memory-search-test-");
        System.setProperty("memory.store.path", testDataDir.toString());
        MetaModel model = buildModelWithSearch();
        ComponentRegistry registry = new ComponentRegistry();
        registry.registerDefaultImplementations();
        registry.assemble(model);
        ctx = new MemoryRuntimeContext(model, registry);
        ctx.start();
        mgr = new SearchMgr(ctx);
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
    void searchWithDefaultStrategy() {
        // Index some data via the search providers
        SearchProvider keyword = ctx.getSearchProvider("keyword");
        keyword.index("1", "java programming");
        keyword.index("2", "python data");
        keyword.index("3", "java development");

        List<SearchMgr.SearchResult> results = mgr.search("java");
        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    @Order(2)
    void searchReturnsMultipleResults() {
        SearchProvider keyword = ctx.getSearchProvider("keyword");
        keyword.index("a", "hello world");
        keyword.index("b", "hello java");
        keyword.index("c", "python code");

        List<SearchMgr.SearchResult> results = mgr.search("hello");
        assertTrue(results.size() >= 2);
    }

    @Test
    @Order(3)
    void searchWithNamedStrategy() {
        // Index data
        SearchProvider keyword = ctx.getSearchProvider("keyword");
        keyword.index("1", "spring boot framework");
        keyword.index("2", "django python web");

        List<SearchMgr.SearchResult> results = mgr.search("python", "default");
        assertNotNull(results);
    }

    @Test
    @Order(4)
    void searchNonexistentStrategyThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> mgr.search("query", "nonexistent"));
    }

    @Test
    @Order(5)
    void searchEmptyQueryReturnsResults() {
        SearchProvider keyword = ctx.getSearchProvider("keyword");
        keyword.index("1", "test data");

        // Empty query should not throw
        List<SearchMgr.SearchResult> results = mgr.search("");
        assertNotNull(results);
    }

    @Test
    @Order(6)
    void resultsAreSortedByScore() {
        SearchProvider keyword = ctx.getSearchProvider("keyword");
        keyword.index("1", "java java java"); // more matches
        keyword.index("2", "java once");

        List<SearchMgr.SearchResult> results = mgr.search("java");
        if (results.size() >= 2) {
            // First result should have higher or equal score
            assertTrue(results.get(0).rawScore() >= results.get(1).rawScore());
        }
    }

    // ── helpers ──────────────────────────────────────────

    private MetaModel buildModelWithSearch() {
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
        step.setWeight(1.0);
        step.setTopK(10);
        ss.setSteps(List.of(step));
        sc.setStrategies(Map.of("default", ss));
        model.setSearch(sc);

        model.setTriggers(new ArrayList<>());
        return model;
    }
}
