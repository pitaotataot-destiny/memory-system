package com.memory;

import com.memory.engine.manager.DecayMgr;
import com.memory.engine.manager.MemoryMgr;
import com.memory.engine.manager.SearchMgr;
import com.memory.engine.manager.TriggerMgr;
import com.memory.model.MetaModel;
import com.memory.registry.ComponentRegistry;
import com.memory.runtime.DSLWatcher;
import com.memory.runtime.MemoryRuntimeContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * MemoryClient — 外部调用的统一门面。
 * 封装了 DSL 解析、组件注册、运行时装配、引擎初始化的完整流程。
 * 外部只需一行初始化，即可调用 create/search/decay 等方法。
 */
public class MemoryClient implements AutoCloseable {

    private final MemoryRuntimeContext ctx;
    private final MemoryMgr memoryMgr;
    private final SearchMgr searchMgr;
    private final TriggerMgr triggerMgr;
    private final DecayMgr decayMgr;
    private final DSLWatcher watcher;
    private volatile boolean started;

    /**
     * 从文件创建（MemoryClient 自动监听 YAML 变更热重载）。
     * @see MemoryFactory#create(Path)
     */
    MemoryClient(MetaModel model, Path dslPath) {
        if (model == null) {
            throw new IllegalArgumentException("MetaModel must not be null");
        }
        // 将 DSL 中的 storage.path 传递给存储引擎
        if (model.getGlobals() != null && model.getGlobals().getStorage() != null) {
            String path = model.getGlobals().getStorage().getPath();
            if (path != null) {
                System.setProperty("memory.store.path", path);
            }
        }
        // 注册组件并装配
        ComponentRegistry registry = new ComponentRegistry();
        registry.registerDefaultImplementations();
        registry.assemble(model);

        // 启动运行时上下文
        ctx = new MemoryRuntimeContext(model, registry);
        ctx.start();

        // 初始化引擎（Manager 间依赖通过构造器注入，复用实例）
        memoryMgr = new MemoryMgr(ctx);
        searchMgr = new SearchMgr(ctx);
        decayMgr = new DecayMgr(ctx);
        triggerMgr = new TriggerMgr(ctx, memoryMgr, decayMgr);

        // 注册 DSL 中声明的触发器
        triggerMgr.registerAllTriggers();

        // 启动 DSL 文件热重载监听（仅当从文件创建时）
        // 热重载完成后重新注册触发器，确保新模型规则生效
        if (dslPath != null) {
            watcher = new DSLWatcher(dslPath, ctx, triggerMgr::reloadAllTriggers);
            watcher.start();
        } else {
            watcher = null;
        }

        started = true;
    }

    /**
     * 从 MetaModel 创建（不从文件加载，无热重载）。
     */
    MemoryClient(MetaModel model) {
        this(model, null);
    }

    /**
     * 创建一条记忆。
     *
     * @param typeKind 类型名称（如 "fact"），为 null 时使用 DSL 默认类型
     * @param data     JSON 格式的记忆数据，不能为 null 或空字符串
     * @param tags     标签集合，可以为 null（视为空集合）
     * @return 生成的记忆 ID
     * @throws IllegalArgumentException data 为 null 或空字符串
     */
    public String create(String typeKind, String data, Set<String> tags) {
        assertStarted();
        requireNonBlank(data, "data");
        return memoryMgr.create(typeKind, data, tags != null ? tags : Set.of());
    }

    /**
     * 读取一条记忆。
     *
     * @param id 记忆 ID，不能为 null 或空字符串
     * @return 记忆 JSON 数据，不存在时返回 null
     * @throws IllegalArgumentException id 为 null 或空字符串
     */
    public String read(String id) {
        assertStarted();
        requireNonBlank(id, "id");
        return memoryMgr.read(id);
    }

    /**
     * 更新一条记忆。
     *
     * @param id      记忆 ID，不能为 null 或空字符串
     * @param newData 新的 JSON 数据，不能为 null 或空字符串
     * @throws IllegalArgumentException id 或 newData 为 null 或空字符串
     */
    public void update(String id, String newData) {
        assertStarted();
        requireNonBlank(id, "id");
        requireNonBlank(newData, "newData");
        memoryMgr.update(id, newData);
    }

    /**
     * 删除一条记忆。
     *
     * @param id 记忆 ID，不能为 null 或空字符串
     * @return 是否删除成功
     * @throws IllegalArgumentException id 为 null 或空字符串
     */
    public boolean delete(String id) {
        assertStarted();
        requireNonBlank(id, "id");
        return memoryMgr.delete(id);
    }

    /**
     * 搜索记忆（使用默认策略）。
     *
     * @param query 搜索关键词，不能为 null 或空字符串
     * @return 搜索结果列表，按相关性排序
     * @throws IllegalArgumentException query 为 null 或空字符串
     */
    public List<SearchMgr.SearchResult> search(String query) {
        assertStarted();
        requireNonBlank(query, "query");
        return searchMgr.search(query);
    }

    /**
     * 使用指定策略搜索记忆。
     *
     * @param query    搜索关键词，不能为 null 或空字符串
     * @param strategy 策略名称（对应 DSL 中 search.strategies 的 key），为 null 时使用默认策略
     * @return 搜索结果列表，按相关性排序
     * @throws IllegalArgumentException query 为 null 或空字符串
     */
    public List<SearchMgr.SearchResult> search(String query, String strategy) {
        assertStarted();
        requireNonBlank(query, "query");
        return searchMgr.search(query, strategy);
    }

    /**
     * 列出所有记忆 ID。
     */
    public Set<String> listAll() {
        assertStarted();
        return memoryMgr.listAll();
    }

    /**
     * 获取记忆总数。
     */
    public int count() {
        assertStarted();
        return memoryMgr.count();
    }

    /**
     * 执行一次完整的衰减计算和生命周期检查。
     * 通常由定时触发器自动执行，也可手动调用。
     */
    public DecayMgr.LifecycleSummary runDecay() {
        assertStarted();
        List<String> decayed = decayMgr.runDecay();
        return decayMgr.runLifecycleCheck();
    }

    /**
     * 检查指定记忆的生命周期状态。
     *
     * @param id 记忆 ID，不能为 null 或空字符串
     * @return 生命周期状态（active/stale/archive/purged）
     * @throws IllegalArgumentException id 为 null 或空字符串
     */
    public DecayMgr.LifecycleStatus checkLifecycle(String id) {
        assertStarted();
        requireNonBlank(id, "id");
        return decayMgr.checkLifecycle(id);
    }

    /**
     * 获取当前的 DSL 模型。
     * 可用于热更新后查询最新规则。
     */
    public MetaModel getModel() {
        return ctx.getMetaModel();
    }

    /**
     * 热更新 DSL 模型。
     * 新模型会原子替换旧模型，无需重启服务。
     *
     * @param newModel 新的 MetaModel 实例，不能为 null
     * @throws IllegalArgumentException newModel 为 null
     */
    public void updateModel(MetaModel newModel) {
        assertStarted();
        if (newModel == null) {
            throw new IllegalArgumentException("newModel must not be null");
        }
        ctx.swapMetaModel(newModel);
        // 热更新后重新注册触发器：清除旧的 EventBus 订阅和定时任务，按新模型注册
        triggerMgr.reloadAllTriggers();
    }

    /**
     * 关闭客户端，释放资源。
     * 停止所有调度器和事件监听，执行优雅关闭。
     */
    @Override
    public void close() {
        if (!started) return;
        started = false;
        if (watcher != null) {
            watcher.stop();
        }
        ctx.stop();
    }

    /**
     * 校验字符串参数不能为 null 或空。
     */
    private static void requireNonBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(paramName + " must not be null or blank");
        }
    }

    private void assertStarted() {
        if (!started) {
            throw new IllegalStateException("MemoryClient has been shut down");
        }
    }
}
