package com.memory.agent.pipeline;

import com.memory.model.IngestDecision;
import com.memory.model.MemoryRecord;
import com.memory.agent.spi.ConflictDetector.ConflictResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管道流转上下文 — 各步骤间共享的状态对象。
 */
public class PipelineContext {

    // ── 输入 ──
    private final String rawText;
    private final Instant startedAt;

    // ── Step 1 (同步): 意图分类 ──
    private String typeKind;
    private double confidence;

    // ── Step 2 (同步): 信息提取 ──
    private Map<String, Object> extractedFields;
    private Set<String> extractedTags;

    // ── Step 3 (同步): 重要性分配 ──
    private double importance;

    // ── Step 4 (同步): 存储 ──
    private String memoryId;

    // ── Step 5 (异步): 搜索已有 ──
    private List<MemoryRecord> existingRecords;

    // ── Step 6 (异步): 冲突检测 ──
    private ConflictResult conflictResult;
    private IngestDecision decision;
    private String pendingDecisionId;

    // ── 结束 ──
    private Instant completedAt;

    public PipelineContext(String rawText) {
        this.rawText = rawText;
        this.startedAt = Instant.now();
    }

    // ── getters / setters ──

    public String getRawText() { return rawText; }
    public Instant getStartedAt() { return startedAt; }

    public String getTypeKind() { return typeKind; }
    public void setTypeKind(String typeKind) { this.typeKind = typeKind; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public Map<String, Object> getExtractedFields() { return extractedFields; }
    public void setExtractedFields(Map<String, Object> extractedFields) { this.extractedFields = extractedFields; }

    public Set<String> getExtractedTags() { return extractedTags; }
    public void setExtractedTags(Set<String> extractedTags) { this.extractedTags = extractedTags; }

    public double getImportance() { return importance; }
    public void setImportance(double importance) { this.importance = importance; }

    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }

    public List<MemoryRecord> getExistingRecords() { return existingRecords; }
    public void setExistingRecords(List<MemoryRecord> records) { this.existingRecords = records; }

    public ConflictResult getConflictResult() { return conflictResult; }
    public void setConflictResult(ConflictResult conflictResult) { this.conflictResult = conflictResult; }

    public IngestDecision getDecision() { return decision; }
    public void setDecision(IngestDecision decision) { this.decision = decision; }

    public String getPendingDecisionId() { return pendingDecisionId; }
    public void setPendingDecisionId(String pendingDecisionId) { this.pendingDecisionId = pendingDecisionId; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
