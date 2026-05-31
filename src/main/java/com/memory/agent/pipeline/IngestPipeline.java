package com.memory.agent.pipeline;

import com.memory.MemoryClient;
import com.memory.agent.spi.ConflictDetector;
import com.memory.agent.spi.ImportanceAssigner;
import com.memory.agent.spi.InformationExtractor;
import com.memory.agent.spi.IntentClassifier;
import com.memory.model.IngestDecision;
import com.memory.model.MetaModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ingest 管道编排器 — 组合同步+异步步骤执行 ingest 流程。
 *
 * 同步段（阻塞返回）：分类 → 提取 → 重要性 → 存储
 * 异步段（后台线程）：搜索已有 → 冲突检测
 */
public class IngestPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(IngestPipeline.class);

    private final List<PipelineStep> syncSteps = new ArrayList<>();
    private final List<PipelineStep> asyncSteps = new ArrayList<>();
    private final ExecutorService asyncExecutor;

    public IngestPipeline(MemoryClient client, MetaModel model,
                           IntentClassifier classifier, InformationExtractor extractor,
                           ImportanceAssigner importanceAssigner,
                           ConflictDetector conflictDetector) {
        // 同步步骤
        syncSteps.add(new IntentClassificationStep(classifier, model));
        syncSteps.add(new InformationExtractionStep(extractor, model));
        syncSteps.add(new ImportanceAssignmentStep(importanceAssigner, model));
        syncSteps.add(new StoreDecisionStep(client, model));

        // 异步步骤
        asyncSteps.add(new SearchExistingStep(client));
        asyncSteps.add(new ConflictDetectionStep(conflictDetector, model));

        // 单线程执行器（异步任务是串行的）
        asyncExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ingest-async");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行 ingest 管道。
     * 同步段执行完立即返回 IngestResult，异步段在后台继续。
     */
    public IngestResult execute(PipelineContext ctx) {
        // ── 同步段 ──
        for (PipelineStep step : syncSteps) {
            try {
                step.execute(ctx);
            } catch (Exception e) {
                LOG.error("Ingest pipeline sync step [{}] failed: {}", step.name(), e.getMessage());
                return IngestResult.failed("Step '" + step.name() + "' failed: " + e.getMessage());
            }
        }

        String memoryId = ctx.getMemoryId();
        Instant completedAt = Instant.now();
        ctx.setCompletedAt(completedAt);

        IngestResult result = new IngestResult(
            ctx.getTypeKind(), ctx.getConfidence(), memoryId,
            ctx.getExtractedFields(), ctx.getExtractedTags(),
            IngestDecision.STORE, completedAt, null
        );

        // ── 异步段（后台线程） ──
        asyncExecutor.submit(() -> {
            for (PipelineStep step : asyncSteps) {
                try {
                    step.execute(ctx);
                } catch (Exception e) {
                    LOG.error("Ingest pipeline async step [{}] failed: {}", step.name(), e.getMessage());
                    break;
                }
            }
            LOG.debug("Ingest async pipeline complete for memory {}", memoryId);
        });

        return result;
    }

    /** 关闭异步执行器 */
    public void shutdown() {
        asyncExecutor.shutdown();
    }
}
