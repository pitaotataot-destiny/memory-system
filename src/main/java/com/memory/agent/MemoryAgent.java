package com.memory.agent;

import com.memory.MemoryClient;
import com.memory.agent.pipeline.IngestPipeline;
import com.memory.agent.pipeline.IngestResult;
import com.memory.agent.pipeline.PipelineContext;
import com.memory.agent.spi.ConflictDetector;
import com.memory.agent.spi.ImportanceAssigner;
import com.memory.agent.spi.InformationExtractor;
import com.memory.agent.spi.IntentClassifier;
import com.memory.agent.spi.MemoryConsolidator;
import com.memory.engine.manager.DecayMgr;
import com.memory.engine.manager.SearchMgr;
import com.memory.model.MetaModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * MemoryAgent — Layer 3 顶层门面。
 *
 * 包裹 MemoryClient（Layer 2），新增 ingest() 能力：
 * 输入原始文本 → 自动分类 → 提取字段 → 分配重要性 → 存储
 * 冲突检测和记忆合并在后台异步执行。
 *
 * 现有 MemoryClient API（create/read/update/delete/search/decay）照常可用。
 */
public class MemoryAgent implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryAgent.class);
    private static final int LOG_TRUNCATE_LENGTH = 80;

    private final MemoryClient client;
    private final MetaModel model;
    private final IngestPipeline ingestPipeline;
    private final MemoryConsolidator consolidator;

    /**
     * 内部构造，由 MemoryAgentFactory 调用。
     */
    MemoryAgent(MemoryClient client, MetaModel model,
                IntentClassifier classifier, InformationExtractor extractor,
                ImportanceAssigner importanceAssigner, ConflictDetector conflictDetector,
                MemoryConsolidator consolidator) {
        this.client = client;
        this.model = model;
        this.consolidator = consolidator;
        this.ingestPipeline = new IngestPipeline(client, model,
            classifier, extractor, importanceAssigner, conflictDetector);
    }

    /**
     * 摄入原始文本 — 自主完成分类→提取→存储全链路。
     *
     * @param rawText 原始文本输入
     * @return IngestResult 包含类型、字段、记忆 ID 等结果
     */
    public IngestResult ingest(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return IngestResult.failed("rawText must not be blank");
        }
        if (model.getAgent() == null || !model.getAgent().isEnabled()) {
            return IngestResult.failed("Agent is not enabled in DSL");
        }

        LOG.debug("Ingesting: {}", rawText.length() > LOG_TRUNCATE_LENGTH
            ? rawText.substring(0, LOG_TRUNCATE_LENGTH) + "..." : rawText);
        PipelineContext ctx = new PipelineContext(rawText);
        return ingestPipeline.execute(ctx);
    }

    // ── 委托 MemoryClient 的 API（保持 Layer 2 全部能力） ──

    public String create(String typeKind, String data, Set<String> tags) {
        return client.create(typeKind, data, tags);
    }

    public String read(String id) {
        return client.read(id);
    }

    public void update(String id, String newData) {
        client.update(id, newData);
    }

    public boolean delete(String id) {
        return client.delete(id);
    }

    public List<SearchMgr.SearchResult> search(String query) {
        return client.search(query);
    }

    public List<SearchMgr.SearchResult> search(String query, String strategy) {
        return client.search(query, strategy);
    }

    public DecayMgr.LifecycleSummary runDecay() {
        return client.runDecay();
    }

    public DecayMgr.LifecycleStatus checkLifecycle(String id) {
        return client.checkLifecycle(id);
    }

    public Set<String> listAll() {
        return client.listAll();
    }

    public int count() {
        return client.count();
    }

    public MetaModel getModel() {
        return client.getModel();
    }

    public void updateModel(MetaModel newModel) {
        client.updateModel(newModel);
    }

    @Override
    public void close() {
        ingestPipeline.shutdown();
        client.close();
    }
}
