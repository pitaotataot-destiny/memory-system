package com.memory.agent.pipeline;

import com.memory.model.IngestDecision;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Ingest 操作的结果。
 */
public record IngestResult(
    String rawText,
    String typeKind,
    double confidence,
    String memoryId,
    Map<String, Object> fields,
    Set<String> tags,
    IngestDecision decision,
    Instant completedAt,
    String error
) {
    /** 创建成功结果 */
    public static IngestResult success(String rawText, String typeKind, double confidence,
                                        String memoryId, Map<String, Object> fields,
                                        Set<String> tags) {
        return new IngestResult(rawText, typeKind, confidence, memoryId, fields, tags,
            IngestDecision.STORE, Instant.now(), null);
    }

    /** 创建失败结果 */
    public static IngestResult failed(String error) {
        return new IngestResult(null, null, 0, null, null, null,
            IngestDecision.IGNORE, Instant.now(), error);
    }

    public boolean isSuccess() {
        return error == null && memoryId != null;
    }
}
