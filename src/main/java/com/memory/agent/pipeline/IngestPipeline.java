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
        // 检测是否 LLM+LLM：合并分类+提取为一次调用
        boolean intentIsLlm = isLlm(model.getAgent() != null
            ? model.getAgent().getIntent().getEngine() : "");
        boolean extractIsLlm = isLlm(model.getAgent() != null
            ? model.getAgent().getExtraction().getEngine() : "");

        if (intentIsLlm && extractIsLlm) {
            LOG.info("Both intent & extraction are LLM — using combined single-call step");
            PipelineStep fallbackClassify = new IntentClassificationStep(classifier, model);
            PipelineStep fallbackExtract = new InformationExtractionStep(extractor, model);
            syncSteps.add(new CombinedClassifyExtractStep(model, fallbackClassify, fallbackExtract));
        } else {
            syncSteps.add(new IntentClassificationStep(classifier, model));
            syncSteps.add(new InformationExtractionStep(extractor, model));
        }
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
            ctx.getRawText(), ctx.getTypeKind(), ctx.getConfidence(), memoryId,
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

    private static boolean isLlm(String engine) {
        return "llm".equals(engine);
    }

    /** 关闭异步执行器，等待 3 秒让正在执行的任务完成 */
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
