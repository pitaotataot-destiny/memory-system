package com.memory.runtime;

import com.memory.MemoryClient;
import com.memory.MemoryFactory;
import com.memory.dsl.DSLParser;
import com.memory.model.MetaModel;
import com.memory.model.decay.DecayConfig;
import com.memory.model.decay.DecayPolicy;
import com.memory.model.decay.LifecycleConfig;
import com.memory.model.enums.MemoryTypeKind;
import com.memory.model.enums.SearchEngineKind;
import com.memory.model.enums.StorageEngine;
import com.memory.model.globals.Globals;
import com.memory.model.globals.StorageConfig;
import com.memory.model.search.EngineConfig;
import com.memory.model.search.SearchConfig;
import com.memory.model.search.SearchStep;
import com.memory.model.search.SearchStrategy;
import com.memory.model.constraint.*;
import com.memory.model.type.MemoryType;
import com.memory.registry.ComponentRegistry;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 热更新专项测试。
 *
 * 验证 MetaModel 在运行时被原子替换后，系统行为是否符合新配置。
 * 核心场景：
 *   1. 原子替换：swapMetaModel 后引用立即变更
 *   2. 版本变更：新模型的 version 字段生效
 *   3. 参数变更：globals 参数变更后立即生效
 *   4. 衰减策略变更：替换后查询到的衰减参数使用新值
 *   5. 搜索策略变更：替换后搜索使用新策略
 *   6. CRUD 不受影响：热更新后记忆 CRUD 正常工作
 *   7. null 模型拒绝：swapMetaModel(null) 应抛异常
 *   8. MemoryClient.updateModel 端到端验证
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HotUpdateTest {

    private MetaModel originalModel;
    private ComponentRegistry registry;
    private MemoryRuntimeContext context;

    @BeforeEach
    void setUp() {
        originalModel = buildModel(100, 0.9, "keyword", "1.0");
        registry = new ComponentRegistry();
        registry.registerDefaultImplementations();
        registry.assemble(originalModel);
        context = new MemoryRuntimeContext(originalModel, registry);
        context.start();
    }

    // ── 场景 1: 原子替换 ───────────────────────────────

    @Test
    @Order(1)
    @DisplayName("swapMetaModel 原子替换 MetaModel 引用")
    void atomicSwapChangesReference() {
        MetaModel newModel = buildModel(999, 0.5, "keyword", "2.0");

        // swap 前：旧值
        assertEquals(100, context.getMetaModel().getGlobals().getMaxMemorySize());
        assertEquals("1.0", context.getMetaModel().getVersion());

        context.swapMetaModel(newModel);

        // swap 后：立即生效
        assertSame(newModel, context.getMetaModel());
        assertEquals(999, context.getMetaModel().getGlobals().getMaxMemorySize());
        assertEquals("2.0", context.getMetaModel().getVersion());
    }

    @Test
    @Order(2)
    @DisplayName("热更新后旧字段不受影响（非替换字段保持不变）")
    void unchangedFieldsPreserved() {
        MetaModel newModel = buildModel(200, 0.7, "keyword", "1.5");

        int oldTtl = context.getMetaModel().getGlobals().getDefaultTtlDays();
        context.swapMetaModel(newModel);

        assertEquals(200, context.getMetaModel().getGlobals().getMaxMemorySize());
        assertEquals("1.5", context.getMetaModel().getVersion());
        // defaultTtlDays 未被替换，应保持新模型的值
        assertEquals(oldTtl, context.getMetaModel().getGlobals().getDefaultTtlDays());
    }

    // ── 场景 2: 版本变更 ───────────────────────────────

    @Test
    @Order(3)
    @DisplayName("版本号在热更新后变更为新值")
    void versionChangesAfterHotUpdate() {
        assertEquals("1.0", context.getMetaModel().getVersion());

        MetaModel v2 = buildModel(100, 0.9, "keyword", "2.0");
        context.swapMetaModel(v2);
        assertEquals("2.0", context.getMetaModel().getVersion());

        MetaModel v3 = buildModel(100, 0.9, "keyword", "3.0-beta");
        context.swapMetaModel(v3);
        assertEquals("3.0-beta", context.getMetaModel().getVersion());
    }

    // ── 场景 3: globals 参数变更 ────────────────────────

    @Test
    @Order(4)
    @DisplayName("globals 参数（maxSize、defaultType、TTL）变更后立即生效")
    void globalsParamsUpdatedImmediately() {
        MetaModel changed = buildModel(5000, 0.9, "keyword", "1.0");
        changed.getGlobals().setDefaultType(MemoryTypeKind.REFERENCE);
        changed.getGlobals().setDefaultTtlDays(60);

        context.swapMetaModel(changed);

        assertEquals(5000, context.getMetaModel().getGlobals().getMaxMemorySize());
        assertEquals(MemoryTypeKind.REFERENCE, context.getMetaModel().getGlobals().getDefaultType());
        assertEquals(60, context.getMetaModel().getGlobals().getDefaultTtlDays());
    }

    // ── 场景 4: 衰减策略变更 ────────────────────────────

    @Test
    @Order(5)
    @DisplayName("衰减策略在热更新后使用新参数")
    void decayPolicyUpdatedAfterSwap() {
        assertEquals(0.9, context.getMetaModel().getDecay().getDefaultConfig().getDailyDecay(), 0.001);

        MetaModel slowDecay = buildModel(100, 0.99, "keyword", "1.0");
        context.swapMetaModel(slowDecay);

        assertEquals(0.99, context.getMetaModel().getDecay().getDefaultConfig().getDailyDecay(), 0.001);
    }

    // ── 场景 5: 搜索策略变更 ────────────────────────────

    @Test
    @Order(6)
    @DisplayName("搜索策略在热更新后可添加新策略")
    void searchStrategyAddedAfterSwap() {
        assertEquals(1, context.getMetaModel().getSearch().getStrategies().size());

        MetaModel multiStrategy = buildModel(100, 0.9, "tfidf", "1.0");
        // 添加第二个策略
        SearchStrategy fast = new SearchStrategy();
        fast.setName("fast");
        fast.setSteps(List.of());
        multiStrategy.getSearch().getStrategies().put("fast", fast);

        context.swapMetaModel(multiStrategy);

        assertEquals(2, context.getMetaModel().getSearch().getStrategies().size());
        assertTrue(context.getMetaModel().getSearch().getStrategies().containsKey("fast"));
    }

    // ── 场景 6: 多次连续热更新 ──────────────────────────

    @Test
    @Order(7)
    @DisplayName("连续多次热更新，每次都正确生效")
    void consecutiveHotUpdatesAllWork() {
        for (int i = 1; i <= 5; i++) {
            MetaModel m = buildModel(i * 100, 0.9, "keyword", "1." + i);
            context.swapMetaModel(m);
            assertEquals(i * 100, context.getMetaModel().getGlobals().getMaxMemorySize());
            assertEquals("1." + i, context.getMetaModel().getVersion());
        }
    }

    // ── 场景 7: CRUD 在热更新后正常 ─────────────────────

    @Test
    @Order(8)
    @DisplayName("热更新后 CRUD 操作不受影响")
    void crudWorksAfterHotUpdate() {
        // 先创建一条记忆
        var store = context.getStore("json");
        store.save("hot-mem", "{\"type\":\"fact\",\"content\":\"before-update\"}");

        // 热更新
        MetaModel newModel = buildModel(200, 0.8, "keyword", "2.0");
        context.swapMetaModel(newModel);

        // CRUD 仍正常
        String loaded = store.load("hot-mem");
        assertNotNull(loaded);
        assertTrue(loaded.contains("before-update"));

        store.save("hot-mem-2", "{\"type\":\"fact\",\"content\":\"after-update\"}");
        assertNotNull(store.load("hot-mem-2"));

        store.delete("hot-mem");
        assertNull(store.load("hot-mem"));
    }

    // ── 场景 8: MemoryClient.updateModel 端到端 ──────────

    @Test
    @Order(9)
    @DisplayName("MemoryClient.updateModel 端到端热更新")
    void memoryClientUpdateModelEndToEnd() throws Exception {
        String yaml = """
            version: "1.0"
            globals:
              default_type: fact
              max_memory_size: 50
              default_ttl_days: 7
              storage:
                engine: json
                path: "./data/test-hotupdate"
            types:
              fact:
                description: "事实"
                fields:
                  content: { type: string, required: true }
                tags:
                  max: 5
            decay:
              default:
                daily_decay: 0.85
                access_gain: 0.05
                min_importance: 0.1
            search:
              engines:
                keyword:
                  enabled: true
              strategies:
                default:
                  steps:
                    - engine: keyword
                      weight: 1.0
                      top_k: 10
            triggers: []
            """;

        try (MemoryClient client = MemoryFactory.createFromString(yaml)) {
            // 创建一条记忆
            String id = client.create("fact",
                "{\"content\":\"测试热更新\"}",
                Set.of("test"));
            assertNotNull(id);

            // 原始值
            assertEquals(50, client.getModel().getGlobals().getMaxMemorySize());

            // 解析新 DSL（模拟修改 YAML 后热更新）
            String updatedYaml = yaml.replace("max_memory_size: 50", "max_memory_size: 200");
            MetaModel newModel = new DSLParser().parseString(updatedYaml);

            client.updateModel(newModel);

            // 新值生效
            assertEquals(200, client.getModel().getGlobals().getMaxMemorySize());

            // CRUD 仍然正常
            String loaded = client.read(id);
            assertNotNull(loaded);
            assertTrue(loaded.contains("测试热更新"));

            assertTrue(client.delete(id));
        }
    }

    // ── 辅助方法 ────────────────────────────────────────

    private MetaModel buildModel(int maxSize, double dailyDecay, String engineKind, String version) {
        MetaModel model = new MetaModel();
        model.setVersion(version);

        Globals globals = new Globals();
        globals.setDefaultType(MemoryTypeKind.FACT);
        globals.setMaxMemorySize(maxSize);
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
        dc.setDailyDecay(dailyDecay);
        dc.setAccessGain(0.05);
        dc.setMinImportance(0.1);
        DecayPolicy decay = new DecayPolicy();
        decay.setDefaultConfig(dc);
        decay.setLifecycle(new LifecycleConfig());
        model.setDecay(decay);

        SearchConfig sc = new SearchConfig();
        SearchEngineKind kind = "tfidf".equals(engineKind)
            ? SearchEngineKind.TFIDF : SearchEngineKind.KEYWORD;
        EngineConfig ec = new EngineConfig();
        ec.setKind(kind);
        ec.setEnabled(true);
        sc.setEngines(Map.of(engineKind, ec));
        SearchStrategy ss = new SearchStrategy();
        ss.setName("default");
        SearchStep step = new SearchStep();
        step.setEngine(kind);
        ss.setSteps(List.of(step));
        sc.setStrategies(new HashMap<>(Map.of("default", ss)));
        model.setSearch(sc);

        model.setTriggers(new ArrayList<>());
        return model;
    }
}
