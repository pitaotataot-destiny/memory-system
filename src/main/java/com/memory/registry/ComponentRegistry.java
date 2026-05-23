package com.memory.registry;

import com.memory.model.MetaModel;
import com.memory.model.enums.SearchEngineKind;
import com.memory.model.enums.StorageEngine;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组件注册表 — 将 MetaModel 的声明装配为活的组件实例。
 *
 * 职责：
 *   1. SPI 扫描：从 META-INF/services 加载所有组件实现
 *   2. 按 MetaModel 声明注册组件（按 key 索引）
 *   3. 生命周期管理：init() → start() → ready()
 *   4. 依赖校验：DSL 声明了但 SPI 找不到实现 → 启动失败
 */
public class ComponentRegistry {

    // 组件生命周期状态
    public enum State {
        NEW, INITIALIZED, STARTED, FAILED
    }

    /**
     * 占位对象 — ConcurrentHashMap 不允许 null 值，
     * 尚未加载 SPI 实现的组件用此占位符。
     */
    private static final Object PLACEHOLDER = new Object();

    // 已注册的组件实例，按名称索引
    private final Map<String, Object> components = new ConcurrentHashMap<>();

    // SPI 实现缓存（接口全限定名 → 实现类列表）
    private final Map<String, List<Class<?>>> spiImplementations = new ConcurrentHashMap<>();

    private State state = State.NEW;
    private MetaModel metaModel;

    /**
     * 从 MetaModel 装配所有组件。
     * @param model DSL 解析后的元模型
     */
    public synchronized void assemble(MetaModel model) {
        if (state != State.NEW) {
            throw new RegistryException("Registry already assembled (current state: " + state + ")");
        }
        this.metaModel = model;

        // 阶段 1：SPI 扫描
        scanSpiImplementations();

        // 阶段 2：注册存储引擎
        registerStorageEngines();

        // 阶段 3：注册搜索引擎
        registerSearchProviders();

        // 阶段 4：注册事件总线
        registerEventBus();

        // 阶段 5：生命周期 init → start
        initAll();
        startAll();

        state = State.STARTED;
    }

    /**
     * SPI 扫描：从 classpath 加载所有已知 SPI 接口的实现。
     */
    private void scanSpiImplementations() {
        // 只扫描尚未手动注册的 SPI 接口
        if (!spiImplementations.containsKey("com.memory.spi.MemoryStore")) {
            List<Class<?>> storeImpls = loadSpiClasses("com.memory.spi.MemoryStore");
            spiImplementations.put("com.memory.spi.MemoryStore", storeImpls);
        }
        if (!spiImplementations.containsKey("SearchProvider")) {
            List<Class<?>> searchImpls = loadSpiClasses("com.memory.spi.SearchProvider");
            spiImplementations.put("SearchProvider", searchImpls);
        }
        if (!spiImplementations.containsKey("EventBus")) {
            List<Class<?>> eventBusImpls = loadSpiClasses("com.memory.spi.EventBus");
            spiImplementations.put("EventBus", eventBusImpls);
        }
    }

    /**
     * 注册存储引擎 — 按 MetaModel.globals.storage.engine 声明。
     */
    private void registerStorageEngines() {
        if (metaModel.getGlobals() == null || metaModel.getGlobals().getStorage() == null) {
            return;
        }
        StorageEngine engine = metaModel.getGlobals().getStorage().getEngine();
        if (engine == null) return;
        String key = "store:" + engine.getValue();
        components.putIfAbsent(key, PLACEHOLDER); // placeholder, replaced when SPI is implemented
    }

    /**
     * 注册搜索提供者 — 按 MetaModel.search.engines 声明。
     */
    private void registerSearchProviders() {
        metaModel.getSearch().getEngines().forEach((name, engineConfig) -> {
            if (!engineConfig.isEnabled()) {
                return;
            }
            String key = "search:" + name;
            SearchEngineKind kind = engineConfig.getKind();

            // 校验：DSL 声明了此引擎，但 SPI 找不到实现
            List<Class<?>> impls = spiImplementations.get("com.memory.spi.SearchProvider");
            if (impls == null || impls.isEmpty()) {
                throw new RegistryException(
                    "Search provider '" + kind.getValue() + "' is declared in DSL, " +
                    "but no SPI implementation found. " +
                    "Add a com.memory.spi.SearchProvider implementation for '" + kind.getValue() + "'."
                );
            }

            components.putIfAbsent(key, PLACEHOLDER); // placeholder, replaced when SPI is implemented
        });
    }

    /**
     * 注册事件总线。
     */
    private void registerEventBus() {
        String key = "eventbus:local";
        List<Class<?>> impls = spiImplementations.get("com.memory.spi.EventBus");
        if (impls == null || impls.isEmpty()) {
            throw new RegistryException(
                "EventBus is required but no SPI implementation found. " +
                "Add a com.memory.spi.EventBus implementation."
            );
        }
        components.putIfAbsent(key, PLACEHOLDER); // placeholder, replaced when SPI is implemented
    }

    /**
     * 生命周期：初始化所有组件。
     */
    private void initAll() {
        // 遍历已注册组件，调用 init()（SPI 实现后填充）
    }

    /**
     * 生命周期：启动所有组件。
     */
    private void startAll() {
        // 遍历已注册组件，调用 start()（SPI 实现后填充）
    }

    /**
     * 按 key 获取已注册的组件实例。
     * @param key 组件标识（如 "search:embedding", "store:json"）
     * @return 组件实例，未注册返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Object value = components.get(key);
        if (value == PLACEHOLDER) return null;
        return (T) value;
    }

    /**
     * 注册 SPI 实现类（用于测试或手动注册，绕过 ServiceLoader）。
     * @param spiInterface SPI 接口全限定名
     * @param implClass    实现类
     */
    public void registerSpiImplementation(String spiInterface, Class<?> implClass) {
        spiImplementations.computeIfAbsent(spiInterface, k -> new ArrayList<>()).add(implClass);
    }

    /**
     * 批量注册默认 SPI 实现（测试用）。
     * 直接实例化组件并注册到 components，而非仅注册 SPI 类。
     */
    public void registerDefaultImplementations() {
        // 注册存储引擎
        com.memory.engine.store.JsonMemoryStore store =
            new com.memory.engine.store.JsonMemoryStore();
        store.init();
        components.put("store:json", store);

        // 注册搜索提供者
        com.memory.engine.search.KeywordSearchProvider keyword =
            new com.memory.engine.search.KeywordSearchProvider();
        keyword.init(Map.of());
        components.put("search:keyword", keyword);

        com.memory.engine.search.TfidfSearchProvider tfidf =
            new com.memory.engine.search.TfidfSearchProvider();
        tfidf.init(Map.of("max_features", 10000));
        components.put("search:tfidf", tfidf);

        com.memory.engine.search.EmbeddingSearchProvider embedding =
            new com.memory.engine.search.EmbeddingSearchProvider();
        embedding.init(Map.of("model", "dummy", "threshold", 0.0));
        components.put("search:embedding", embedding);

        // 注册事件总线
        com.memory.engine.event.LocalEventBus eventBus =
            new com.memory.engine.event.LocalEventBus();
        components.put("eventbus:local", eventBus);

        // 注册调度器
        com.memory.engine.scheduler.DefaultScheduler scheduler =
            new com.memory.engine.scheduler.DefaultScheduler();
        scheduler.init();
        components.put("scheduler:default", scheduler);

        // 注册表达式引擎
        com.memory.engine.expression.DefaultExpressionEngine expressionEngine =
            new com.memory.engine.expression.DefaultExpressionEngine();
        components.put("expression:default", expressionEngine);

        // 同时注册 SPI 类（供 assemble 时校验使用）
        spiImplementations.put("com.memory.spi.MemoryStore", List.of(
            com.memory.engine.store.JsonMemoryStore.class
        ));
        spiImplementations.put("com.memory.spi.SearchProvider", List.of(
            com.memory.engine.search.KeywordSearchProvider.class,
            com.memory.engine.search.TfidfSearchProvider.class,
            com.memory.engine.search.EmbeddingSearchProvider.class
        ));
        spiImplementations.put("com.memory.spi.EventBus", List.of(
            com.memory.engine.event.LocalEventBus.class
        ));
        spiImplementations.put("com.memory.spi.Scheduler", List.of(
            com.memory.engine.scheduler.DefaultScheduler.class
        ));
        spiImplementations.put("com.memory.spi.ExpressionEngine", List.of(
            com.memory.engine.expression.DefaultExpressionEngine.class
        ));
    }

    /**
     * 注册组件实例。
     * @param key 组件标识
     * @param instance 组件实例
     */
    public void register(String key, Object instance) {
        if (state == State.STARTED) {
            throw new RegistryException("Cannot register components after registry is started");
        }
        components.put(key, instance);
    }

    /**
     * 获取当前生命周期状态。
     */
    public State getState() {
        return state;
    }

    /**
     * 获取当前 MetaModel 引用。
     */
    public MetaModel getMetaModel() {
        return metaModel;
    }

    /**
     * 获取所有已注册组件的 key 集合。
     */
    public Set<String> getRegisteredKeys() {
        return Collections.unmodifiableSet(components.keySet());
    }

    /**
     * 加载指定 SPI 接口的实现类列表。
     * 使用 ServiceLoader 扫描 META-INF/services。
     */
    private List<Class<?>> loadSpiClasses(String interfaceName) {
        // 后续实现：ServiceLoader.load(Class.forName(interfaceName))
        // 当前返回空列表，待 SPI 接口定义后填充
        return new ArrayList<>();
    }
}
