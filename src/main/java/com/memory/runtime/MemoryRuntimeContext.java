package com.memory.runtime;

import com.memory.model.MetaModel;
import com.memory.registry.ComponentRegistry;
import com.memory.spi.EventBus;
import com.memory.spi.ExpressionEngine;
import com.memory.spi.MemoryStore;
import com.memory.spi.Scheduler;
import com.memory.spi.SearchProvider;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时上下文 — 持有系统运行时的完整状态。
 *
 * 职责：
 *   1. 持有当前 MetaModel 实例引用（支持热更新，原子替换）
 *   2. 持有已装配的组件实例引用（Store / SearchProvider / EventBus）
 *   3. L0 热记忆缓存（importance >= 0.7 且 24h 内有访问）
 *   4. 请求上下文 / 统计指标计数器
 *
 * 线程安全：所有状态通过 AtomicReference / AtomicLong 保证并发安全。
 */
public class MemoryRuntimeContext {

    // 当前生效的 MetaModel（原子引用，支持热更新替换）
    private final AtomicReference<MetaModel> metaModelRef;

    // 组件注册表（已装配完成的组件实例）
    private final ComponentRegistry registry;

    // ── 快捷组件引用（从 Registry 提取，Engine 直接通过 getter 使用）──

    // 存储层
    private final Map<String, MemoryStore> stores = new java.util.concurrent.ConcurrentHashMap<>();

    // 搜索引擎：按名称索引
    // 如 "keyword" → KeywordSearchProvider, "embedding" → EmbeddingSearchProvider
    private final Map<String, SearchProvider> searchProviders = new java.util.concurrent.ConcurrentHashMap<>();

    // 事件总线
    private EventBus eventBus;

    // 调度器
    private Scheduler scheduler;

    // 表达式引擎
    private ExpressionEngine expressionEngine;

    // L0 热记忆缓存（key=memoryId, value=importance）
    private final java.util.Map<String, Double> hotCache = new java.util.concurrent.ConcurrentHashMap<>();

    // 统计指标
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    // 运行状态
    private volatile boolean running = false;

    /**
     * 构造运行时上下文。
     * @param model     当前 MetaModel 实例
     * @param registry  已装配的组件注册表
     */
    public MemoryRuntimeContext(MetaModel model, ComponentRegistry registry) {
        this.metaModelRef = new AtomicReference<>(model);
        this.registry = registry;
        extractComponents();
    }

    /**
     * 从 Registry 提取快捷组件引用，Engine 无需通过 key 字符串查找。
     */
    private void extractComponents() {
        // 提取存储引擎
        for (String key : registry.getRegisteredKeys()) {
            if (key.startsWith("store:")) {
                MemoryStore store = registry.get(key);
                if (store != null) {
                    stores.put(store.name(), store);
                }
            } else if (key.startsWith("search:")) {
                SearchProvider provider = registry.get(key);
                if (provider != null) {
                    searchProviders.put(provider.name(), provider);
                }
            } else if (key.startsWith("eventbus:")) {
                this.eventBus = registry.get(key);
            } else if (key.startsWith("scheduler:")) {
                this.scheduler = registry.get(key);
            } else if (key.startsWith("expression:")) {
                this.expressionEngine = registry.get(key);
            }
        }
    }

    /**
     * 启动运行时。
     */
    public synchronized void start() {
        if (running) {
            throw new RuntimeStateException("Runtime context already running");
        }
        if (registry.getState() != ComponentRegistry.State.STARTED) {
            throw new RuntimeStateException(
                "Registry must be STARTED before runtime context can start (current: " + registry.getState() + ")"
            );
        }
        running = true;
    }

    /**
     * 停止运行时。
     */
    public synchronized void stop() {
        running = false;
        hotCache.clear();
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    // ── MetaModel 访问 ─────────────────────────────────

    /**
     * 获取当前 MetaModel 引用。
     * 热更新时通过 {@link #swapMetaModel(MetaModel)} 原子替换此引用。
     */
    public MetaModel getMetaModel() {
        return metaModelRef.get();
    }

    /**
     * 原子替换 MetaModel（热更新）。
     * 调用后，所有后续请求将使用新的规则配置。
     * @param newModel 新解析的 MetaModel
     */
    public void swapMetaModel(MetaModel newModel) {
        metaModelRef.set(newModel);
    }

    // ── 组件访问 ──────────────────────────────────────

    /**
     * 获取存储引擎。
     * @param name 引擎名称（与 DSL storage.engine 对应）
     */
    public MemoryStore getStore(String name) {
        MemoryStore store = stores.get(name);
        if (store == null) {
            throw new RuntimeStateException("No MemoryStore registered for: " + name);
        }
        return store;
    }

    /**
     * 获取搜索提供者。
     * @param name 引擎名称（如 "keyword", "tfidf", "embedding"）
     */
    public SearchProvider getSearchProvider(String name) {
        SearchProvider provider = searchProviders.get(name);
        if (provider == null) {
            throw new RuntimeStateException("No SearchProvider registered for: " + name);
        }
        return provider;
    }

    /**
     * 获取所有已注册的搜索提供者。
     */
    public Map<String, SearchProvider> getAllSearchProviders() {
        return Map.copyOf(searchProviders);
    }

    /**
     * 获取事件总线。
     */
    public EventBus getEventBus() {
        if (eventBus == null) {
            throw new RuntimeStateException("No EventBus registered");
        }
        return eventBus;
    }

    /**
     * 获取调度器。
     */
    public Scheduler getScheduler() {
        if (scheduler == null) {
            throw new RuntimeStateException("No Scheduler registered");
        }
        return scheduler;
    }

    /**
     * 获取表达式引擎。
     */
    public ExpressionEngine getExpressionEngine() {
        if (expressionEngine == null) {
            throw new RuntimeStateException("No ExpressionEngine registered");
        }
        return expressionEngine;
    }

    /**
     * 获取组件注册表（用于高级场景直接访问）。
     */
    public ComponentRegistry getRegistry() {
        return registry;
    }

    /**
     * 按 key 获取组件实例（代理到 Registry，推荐用类型安全的 getter）。
     * @param key 组件标识
     */
    public <T> T getComponent(String key) {
        return registry.get(key);
    }

    // ── L0 热记忆缓存 ─────────────────────────────────

    /**
     * 将记忆标记为热记忆（加入 L0 缓存）。
     */
    public void markHot(String memoryId, double importance) {
        hotCache.put(memoryId, importance);
    }

    /**
     * 从 L0 缓存移除。
     */
    public void evictHot(String memoryId) {
        hotCache.remove(memoryId);
    }

    /**
     * 判断是否为热记忆。
     */
    public boolean isHot(String memoryId) {
        return hotCache.containsKey(memoryId);
    }

    // ── 统计指标 ──────────────────────────────────────

    /**
     * 查询次数 +1。
     */
    public long incrementQueries() {
        return totalQueries.incrementAndGet();
    }

    /**
     * 写入次数 +1。
     */
    public long incrementWrites() {
        return totalWrites.incrementAndGet();
    }

    /**
     * 错误次数 +1。
     */
    public long incrementErrors() {
        return totalErrors.incrementAndGet();
    }

    /**
     * 获取查询总数。
     */
    public long getTotalQueries() {
        return totalQueries.get();
    }

    /**
     * 获取写入总数。
     */
    public long getTotalWrites() {
        return totalWrites.get();
    }

    /**
     * 获取错误总数。
     */
    public long getTotalErrors() {
        return totalErrors.get();
    }

    /**
     * 获取运行状态。
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取 L0 热记忆数量。
     */
    public int getHotCacheSize() {
        return hotCache.size();
    }
}
