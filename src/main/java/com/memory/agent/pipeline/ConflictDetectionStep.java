package com.memory.agent.pipeline;

import com.memory.agent.spi.ConflictDetector;
import com.memory.agent.spi.ConflictDetector.ConflictResult;
import com.memory.model.IngestDecision;
import com.memory.model.MetaModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步步骤 6：冲突检测。
 */
class ConflictDetectionStep implements PipelineStep {

    private static final Logger LOG = LoggerFactory.getLogger(ConflictDetectionStep.class);

    // DSL 默认严重度阈值
    private static final double FALLBACK_SEVERITY_THRESHOLD = 0.5;

    private final ConflictDetector detector;
    private final MetaModel model;

    ConflictDetectionStep(ConflictDetector detector, MetaModel model) {
        this.detector = detector;
        this.model = model;
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

        // 根据 DSL 配置的 resolution 策略决定
        String resolution = model.getAgent() != null
            ? model.getAgent().getConflict().getResolution() : "ask";

        double threshold = model.getAgent() != null
            ? model.getAgent().getConflict().getSeverityThreshold()
            : FALLBACK_SEVERITY_THRESHOLD;

        switch (resolution) {
            case "auto-update" -> {
                String conflictId = !result.conflictingIds().isEmpty()
                    ? result.conflictingIds().get(0) : "";
                ctx.setMemoryId(conflictId);
                ctx.setDecision(IngestDecision.UPDATE);
                LOG.info("Conflict auto-updated: {} -> {}", conflictId, result.description());
            }
            case "ignore" -> ctx.setDecision(IngestDecision.IGNORE);
            case "merge" -> ctx.setDecision(IngestDecision.MERGE);
            default -> {
                // "ask" — 返回需要外部确认
                ctx.setDecision(IngestDecision.CONFIRM);
                ctx.setPendingDecisionId(ctx.getMemoryId());
                LOG.info("Conflict detected, waiting for decision: {}", result.description());
            }
        }
    }
}
