package com.memory.registry;

import com.memory.model.MetaModel;
import com.memory.model.enums.SearchEngineKind;
import com.memory.model.enums.StorageEngine;
import com.memory.spi.EventBus;
import com.memory.spi.ExpressionEngine;
import com.memory.spi.MemoryStore;
import com.memory.spi.Scheduler;
import com.memory.spi.SearchProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
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

    private static final Logger LOG = LoggerFactory.getLogger(ComponentRegistry.class);

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

        // 阶段 1：SPI 扫描 — 从 META-INF/services 加载所有实现类
        scanSpiImplementations();

        // 阶段 2：注册存储引擎
        registerStorageEngines();

        // 阶段 3：注册搜索引擎
        registerSearchProviders();

        // 阶段 4：注册事件总线 / 调度器 / 表达式引擎
        registerEventBus();
        registerScheduler();
        registerExpressionEngine();

        // 阶段 5：生命周期 init → start
        initAll();
        startAll();

        state = State.STARTED;
        LOG.info("ComponentRegistry assembled successfully: {} components", components.size());
    }

    /**
     * SPI 扫描：从 classpath META-INF/services 加载所有已知 SPI 接口的实现。
     */
    private void scanSpiImplementations() {
        // 扫描所有 5 个 SPI 接口
        loadSpiClasses("com.memory.spi.MemoryStore");
        loadSpiClasses("com.memory.spi.SearchProvider");
        loadSpiClasses("com.memory.spi.EventBus");
        loadSpiClasses("com.memory.spi.Scheduler");
        loadSpiClasses("com.memory.spi.ExpressionEngine");
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
        components.putIfAbsent(key, PLACEHOLDER);
    }

    /**
     * 注册搜索提供者 — 按 MetaModel.search.engines 声明。
     */
    private void registerSearchProviders() {
        if (metaModel.getSearch() == null || metaModel.getSearch().getEngines() == null) {
            return;
        }
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

            components.putIfAbsent(key, PLACEHOLDER);
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
        components.putIfAbsent(key, PLACEHOLDER);
    }

    /**
     * 注册调度器。
     */
    private void registerScheduler() {
        String key = "scheduler:default";
        List<Class<?>> impls = spiImplementations.get("com.memory.spi.Scheduler");
        if (impls == null || impls.isEmpty()) {
            throw new RegistryException(
                "Scheduler is required but no SPI implementation found."
            );
        }
        components.putIfAbsent(key, PLACEHOLDER);
    }

    /**
     * 注册表达式引擎。
     */
    private void registerExpressionEngine() {
        String key = "expression:default";
        List<Class<?>> impls = spiImplementations.get("com.memory.spi.ExpressionEngine");
        if (impls == null || impls.isEmpty()) {
            throw new RegistryException(
                "ExpressionEngine is required but no SPI implementation found."
            );
        }
        components.putIfAbsent(key, PLACEHOLDER);
    }

    /**
     * 生命周期：初始化所有占位组件。
     * 遍历 PLACEHOLDER 标记的 key，用 SPI 实现类实例化并调用 init()。
     * 如果 registerDefaultImplementations() 已注册了实例（非 PLACEHOLDER），则跳过。
     */
    private void initAll() {
        for (Map.Entry<String, Object> entry : components.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() != PLACEHOLDER) continue;

            Object instance = null;
            try {
                if (key.startsWith("store:")) {
                    instance = initStore(key);
                } else if (key.startsWith("search:")) {
                    instance = initSearchProvider(key);
                } else if (key.startsWith("eventbus:")) {
                    instance = initEventBus();
                } else if (key.startsWith("scheduler:")) {
                    instance = initScheduler();
                } else if (key.startsWith("expression:")) {
                    instance = initExpressionEngine();
                }

                if (instance != null) {
                    components.put(key, instance);
                    LOG.debug("Initialized component: {}", key);
                } else {
                    throw new RegistryException(
                        "No matching SPI implementation for key: " + key);
                }
            } catch (ReflectiveOperationException e) {
                throw new RegistryException(
                    "Failed to instantiate SPI implementation for: " + key, e);
            }
        }
    }

    /**
     * 生命周期：启动所有组件（预留，当前无组件需要 start 操作）。
     */
    private void startAll() {
        // 预留：后续可在此处理 start 逻辑（如连接池预热、后台线程启动等）
        LOG.debug("ComponentRegistry: startAll complete");
    }

    // ── 组件实例化辅助方法 ──────────────────────────────────

    /**
     * 实例化并初始化存储引擎。
     */
    private Object initStore(String key) throws ReflectiveOperationException {
        String storeName = key.substring("store:".length());
        List<Class<?>> impls = spiImplementations.get("com.memory.spi.MemoryStore");
        if (impls == null) return null;

        for (Class<?> implClass : impls) {
            MemoryStore store = (MemoryStore) implClass.getDeclaredConstructor().newInstance();
            if (store.name().equals(storeName)) {
                store.init();
                return store;
            }
        }
        return null;
    }

    /**
     * 实例化并初始化搜索提供者。
     * 从 DSL search.engines.<name>.params 获取配置参数，传入 init(Map)。
     */
    private Object initSearchProvider(String key) throws ReflectiveOperationException {
        String engineName = key.substring("search:".length());
        List<Class<?>> impls = spiImplementations.get("com.memory.spi.SearchProvider");
        if (impls == null) return null;

        for (Class<?> implClass : impls) {
            SearchProvider provider = (SearchProvider) implClass.getDeclaredConstructor().newInstance();
            if (provider.name().equals(engineName)) {
                // 从 DSL 配置中获取引擎专属参数
                Map<String, Object> params = Collections.emptyMap();
                if (metaModel.getSearch() != null && metaModel.getSearch().getEngines() != null) {
                    var engineConfig = metaModel.getSearch().getEngines().get(engineName);
                    if (engineConfig != null && engineConfig.getParams() != null) {
                        params = engineConfig.getParams();
                    }
                }
                provider.init(params);
                return provider;
            }
        }
        return null;
    }

    /**
     * 实例化事件总线（默认使用第一个可用实现）。
     */
    private Object initEventBus() throws ReflectiveOperationException {
        List<Class<?>> impls = spiImplementations.get("com.memory.spi.EventBus");
        if (impls == null || impls.isEmpty()) return null;
        return impls.get(0).getDeclaredConstructor().newInstance();
    }

    /**
     * 实例化调度器。
     */
    private Object initScheduler() throws ReflectiveOperationException {
        List<Class<?>> impls = spiImplementations.get("com.memory.spi.Scheduler");
        if (impls == null || impls.isEmpty()) return null;
        Scheduler scheduler = (Scheduler) impls.get(0).getDeclaredConstructor().newInstance();
        scheduler.init();
        return scheduler;
    }

    /**
     * 实例化表达式引擎。
     */
    private Object initExpressionEngine() throws ReflectiveOperationException {
        List<Class<?>> impls = spiImplementations.get("com.memory.spi.ExpressionEngine");
        if (impls == null || impls.isEmpty()) return null;
        return impls.get(0).getDeclaredConstructor().newInstance();
    }

    // ── 公共 API ────────────────────────────────────────────

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
     * 批量注册默认 SPI 实现。
     * 直接实例化组件并注册到 components，用于无需 ServiceLoader 的快速启动。
     * 调用此方法后 assemble() 中的 initAll 会跳过这些已注册的组件。
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
        registerSpiImplementation("com.memory.spi.MemoryStore",
            com.memory.engine.store.JsonMemoryStore.class);
        registerSpiImplementation("com.memory.spi.SearchProvider",
            com.memory.engine.search.KeywordSearchProvider.class);
        registerSpiImplementation("com.memory.spi.SearchProvider",
            com.memory.engine.search.TfidfSearchProvider.class);
        registerSpiImplementation("com.memory.spi.SearchProvider",
            com.memory.engine.search.EmbeddingSearchProvider.class);
        registerSpiImplementation("com.memory.spi.EventBus",
            com.memory.engine.event.LocalEventBus.class);
        registerSpiImplementation("com.memory.spi.Scheduler",
            com.memory.engine.scheduler.DefaultScheduler.class);
        registerSpiImplementation("com.memory.spi.ExpressionEngine",
            com.memory.engine.expression.DefaultExpressionEngine.class);
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
     * 使用 ServiceLoader 扫描 META-INF/services 目录。
     */
    private void loadSpiClasses(String interfaceName) {
        try {
            Class<?> spiClass = Class.forName(interfaceName);
            List<Class<?>> impls = new ArrayList<>();
            for (Object impl : ServiceLoader.load(spiClass)) {
                impls.add(impl.getClass());
            }
            if (!impls.isEmpty()) {
                spiImplementations.put(interfaceName, impls);
                LOG.debug("ServiceLoader found {} implementations for {}", impls.size(), interfaceName);
            }
        } catch (ClassNotFoundException e) {
            LOG.warn("SPI interface not found on classpath: {}", interfaceName);
        }
    }
}
