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
import com.memory.model.MemoryRecord;
import com.memory.model.MetaModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

        // 注册记忆合并定时任务
        scheduleConsolidation();
    }

    /** 按 DSL agent.consolidation.schedule 注册合并定时任务 */
    private void scheduleConsolidation() {
        if (model.getAgent() == null || consolidator == null) return;
        String cronExpr = model.getAgent().getConsolidation().getSchedule();
        if (cronExpr == null || cronExpr.isEmpty()) return;

        // 解析 cron 为间隔毫秒（复用 DefaultScheduler 逻辑）
        long intervalMs = parseCronToMs(cronExpr);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "consolidation-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::runConsolidation,
            intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        LOG.info("Consolidation scheduled: cron={}, interval={}s",
            cronExpr, intervalMs / 1000);
    }

    /** 执行一次合并周期 */
    private void runConsolidation() {
        try {
            Set<String> allIds = client.listAll();
            List<MemoryRecord> records = new ArrayList<>();
            for (String id : allIds) {
                String raw = client.read(id);
                if (raw != null) {
                    try { records.add(MemoryRecord.fromJson(raw)); }
                    catch (Exception e) { /* skip corrupt */ }
                }
            }

            var candidates = consolidator.findCandidates(records, model);
            if (candidates.isEmpty()) return;

            for (var c : candidates) {
                LOG.debug("Consolidation candidate: {} memories (reason: {})",
                    c.memoryIds().size(), c.mergeReason());
                // 合并策略：保留最新的，其余标记为 archived
                if (c.memoryIds().size() >= 2) {
                    String keepId = c.memoryIds().get(c.memoryIds().size() - 1);
                    for (int i = 0; i < c.memoryIds().size() - 1; i++) {
                        String oldId = c.memoryIds().get(i);
                        checkLifecycle(oldId);  // 标记为 archive 不会自动删除
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Consolidation error: {}", e.getMessage());
        }
    }

    /** 简易 cron 解析 → 毫秒 */
    private static long parseCronToMs(String cron) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 2) return 24 * 3_600_000L;
        String minute = parts[0];
        String hour = parts[1];
        if (minute.startsWith("*/")) {
            return Integer.parseInt(minute.substring(2)) * 60_000L;
        }
        if (hour.startsWith("*/")) {
            return Integer.parseInt(hour.substring(2)) * 3_600_000L;
        }
        if ("*".equals(minute)) return 60_000L;
        return 24 * 3_600_000L;  // 固定时间 → 每天
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

    /** 获取底层 MemoryClient（高级场景） */
    public MemoryClient getClient() {
        return client;
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
