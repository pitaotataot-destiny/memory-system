package com.memory.agent.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.MemoryClient;
import com.memory.agent.spi.ConflictDetector;
import com.memory.agent.spi.ConflictDetector.ConflictResult;
import com.memory.model.IngestDecision;
import com.memory.model.MemoryRecord;
import com.memory.model.MetaModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步步骤 6：冲突检测 + 执行 auto-update / merge / ask。
 */
public class ConflictDetectionStep implements PipelineStep {

    private static final Logger LOG = LoggerFactory.getLogger(ConflictDetectionStep.class);
    private static final double FALLBACK_SEVERITY_THRESHOLD = 0.5;

    private final ConflictDetector detector;
    private final MetaModel model;
    private final MemoryClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    // 待确认的决策（供 MemoryAgentServer 查询）
    static final Map<String, PendingDecision> PENDING_DECISIONS = new ConcurrentHashMap<>();

    ConflictDetectionStep(ConflictDetector detector, MetaModel model, MemoryClient client) {
        this.detector = detector;
        this.model = model;
        this.client = client;
    }

    @Override
    public String name() { return "conflict-detect"; }

    @Override
    public void execute(PipelineContext ctx) {
        if (ctx.getExistingRecords() == null || ctx.getExistingRecords().isEmpty()) {
            ctx.setDecision(IngestDecision.STORE);
            return;
        }

        ConflictResult result = detector.detect(ctx.getExtractedFields(),
            ctx.getExistingRecords(), model);
        ctx.setConflictResult(result);

        if (!result.hasConflict()) {
            ctx.setDecision(IngestDecision.STORE);
            return;
        }

        String resolution = model.getAgent() != null
            ? model.getAgent().getConflict().getResolution() : "ask";

        switch (resolution) {
            case "auto-update" -> executeAutoUpdate(ctx, result);
            case "merge" -> executeMerge(ctx, result);
            case "ignore" -> ctx.setDecision(IngestDecision.IGNORE);
            default -> executeAsk(ctx, result);
        }
    }

    /** auto-update: 用新字段更新冲突记忆 */
    private void executeAutoUpdate(PipelineContext ctx, ConflictResult result) {
        String conflictId = result.conflictingIds().get(0);
        try {
            String newJson = mapper.writeValueAsString(ctx.getExtractedFields());
            client.update(conflictId, newJson);
            ctx.setDecision(IngestDecision.UPDATE);
            ctx.setMemoryId(conflictId);
            LOG.info("Conflict auto-updated: {} — {}", conflictId, result.description());
        } catch (Exception e) {
            LOG.error("Auto-update failed for {}: {}", conflictId, e.getMessage());
            ctx.setDecision(IngestDecision.STORE);
        }
    }

    /** merge: 合并新旧字段，写入新记忆 */
    private void executeMerge(PipelineContext ctx, ConflictResult result) {
        String conflictId = result.conflictingIds().get(0);
        try {
            // 获取旧数据的字段
            String oldRaw = client.read(conflictId);
            MemoryRecord oldRecord = MemoryRecord.fromJson(oldRaw);

            // 合并：新字段覆盖旧字段
            Map<String, Object> mergedFields = new java.util.LinkedHashMap<>();
            mergedFields.put("merged_from", conflictId);
            mergedFields.put("original_content",
                oldRecord.getDataField("content"));
            mergedFields.putAll(ctx.getExtractedFields());

            String mergedJson = mapper.writeValueAsString(mergedFields);
            String newId = client.create(ctx.getTypeKind(), mergedJson, ctx.getExtractedTags());
            ctx.setMemoryId(newId);
            ctx.setDecision(IngestDecision.MERGE);
            LOG.info("Conflict merged: {} + {} → {}", conflictId,
                ctx.getExtractedFields().get("content"), newId);
        } catch (Exception e) {
            LOG.error("Merge failed for {}: {}", conflictId, e.getMessage());
            ctx.setDecision(IngestDecision.STORE);
        }
    }

    /** ask: 存储待确认决策，等待外部 resolve */
    private void executeAsk(PipelineContext ctx, ConflictResult result) {
        String decisionId = java.util.UUID.randomUUID().toString();
        ctx.setPendingDecisionId(decisionId);
        ctx.setDecision(IngestDecision.CONFIRM);

        PendingDecision pd = new PendingDecision(
            decisionId,
            ctx.getTypeKind(),
            ctx.getExtractedFields(),
            ctx.getExtractedTags(),
            result.conflictingIds(),
            result.description()
        );
        PENDING_DECISIONS.put(decisionId, pd);
        LOG.info("Conflict pending decision: {} — {}", decisionId, result.description());
    }

    /** 获取所有待确认决策 */
    public static Map<String, PendingDecision> getPendingDecisions() {
        return Map.copyOf(PENDING_DECISIONS);
    }

    /** 解析决策：accept(用新数据更新) / keep-existing(忽略) / merge(合并) */
    public static IngestDecision resolveDecision(String decisionId, String choice,
                                             MemoryClient client) {
        PendingDecision pd = PENDING_DECISIONS.remove(decisionId);
        if (pd == null) return IngestDecision.IGNORE;

        return switch (choice) {
            case "accept" -> {
                try {
                    String json = new ObjectMapper().writeValueAsString(pd.fields());
                    client.update(pd.conflictingIds().get(0), json);
                    yield IngestDecision.UPDATE;
                } catch (Exception e) {
                    LOG.error("Resolve accept failed: {}", e.getMessage());
                    yield IngestDecision.IGNORE;
                }
            }
            case "merge" -> {
                try {
                    ObjectMapper m = new ObjectMapper();
                    String oldRaw = client.read(pd.conflictingIds().get(0));
                    MemoryRecord old = MemoryRecord.fromJson(oldRaw);
                    Map<String, Object> merged = new java.util.LinkedHashMap<>();
                    merged.put("merged_from", pd.conflictingIds().get(0));
                    merged.put("original_content", old.getDataField("content"));
                    merged.putAll(pd.fields());
                    client.create(pd.typeKind(), m.writeValueAsString(merged), pd.tags());
                    yield IngestDecision.MERGE;
                } catch (Exception e) {
                    LOG.error("Resolve merge failed: {}", e.getMessage());
                    yield IngestDecision.IGNORE;
                }
            }
            case "keep-existing" -> IngestDecision.IGNORE;
            default -> IngestDecision.IGNORE;
        };
    }

    public record PendingDecision(
        String id, String typeKind,
        Map<String, Object> fields, java.util.Set<String> tags,
        java.util.List<String> conflictingIds, String reason
    ) {}
}
