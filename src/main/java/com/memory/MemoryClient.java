package com.memory;

import com.memory.dsl.DSLParser;
import com.memory.engine.manager.DecayMgr;
import com.memory.engine.manager.MemoryMgr;
import com.memory.engine.manager.SearchMgr;
import com.memory.engine.manager.TriggerMgr;
import com.memory.model.MetaModel;
import com.memory.registry.ComponentRegistry;
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
    private volatile boolean started;

    /**
     * 通过工厂方法创建实例，不直接调用构造器。
     * @see MemoryFactory#create(Path)
     */
    MemoryClient(MetaModel model) {
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

        // 初始化引擎
        memoryMgr = new MemoryMgr(ctx);
        searchMgr = new SearchMgr(ctx);
        decayMgr = new DecayMgr(ctx);
        triggerMgr = new TriggerMgr(ctx, decayMgr);

        // 注册 DSL 中声明的触发器
        triggerMgr.registerAllTriggers();

        started = true;
    }

    /**
     * 创建一条记忆。
     *
     * @param typeKind 类型名称（如 "fact"），为 null 时使用 DSL 默认类型
     * @param data     JSON 格式的记忆数据
     * @param tags     标签集合
     * @return 生成的记忆 ID
     */
    public String create(String typeKind, String data, Set<String> tags) {
        assertStarted();
        return memoryMgr.create(typeKind, data, tags);
    }

    /**
     * 读取一条记忆。
     *
     * @param id 记忆 ID
     * @return 记忆 JSON 数据，不存在时返回 null
     */
    public String read(String id) {
        assertStarted();
        return memoryMgr.read(id);
    }

    /**
     * 更新一条记忆。
     *
     * @param id      记忆 ID
     * @param newData 新的 JSON 数据
     */
    public void update(String id, String newData) {
        assertStarted();
        memoryMgr.update(id, newData);
    }

    /**
     * 删除一条记忆。
     *
     * @param id 记忆 ID
     * @return 是否删除成功
     */
    public boolean delete(String id) {
        assertStarted();
        return memoryMgr.delete(id);
    }

    /**
     * 搜索记忆。
     *
     * @param query 搜索关键词
     * @return 搜索结果列表，按相关性排序
     */
    public List<SearchMgr.SearchResult> search(String query) {
        assertStarted();
        return searchMgr.search(query);
    }

    /**
     * 使用指定策略搜索记忆。
     *
     * @param query    搜索关键词
     * @param strategy 策略名称（对应 DSL 中 search.strategies 的 key）
     * @return 搜索结果列表，按相关性排序
     */
    public List<SearchMgr.SearchResult> search(String query, String strategy) {
        assertStarted();
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
     * @param id 记忆 ID
     * @return 生命周期状态（active/stale/archive/purged）
     */
    public DecayMgr.LifecycleStatus checkLifecycle(String id) {
        assertStarted();
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
     * @param newModel 新的 MetaModel 实例
     */
    public void updateModel(MetaModel newModel) {
        assertStarted();
        ctx.swapMetaModel(newModel);
    }

    /**
     * 关闭客户端，释放资源。
     * 停止所有调度器和事件监听，执行优雅关闭。
     */
    @Override
    public void close() {
        if (!started) return;
        started = false;
        ctx.stop();
    }

    private void assertStarted() {
        if (!started) {
            throw new IllegalStateException("MemoryClient has been shut down");
        }
    }
}
