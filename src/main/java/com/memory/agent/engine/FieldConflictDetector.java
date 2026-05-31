package com.memory.agent.engine;

import com.memory.agent.spi.ConflictDetector;
import com.memory.model.MemoryRecord;
import com.memory.model.MetaModel;
import com.memory.model.agent.AgentTypeHint;
import com.memory.model.type.MemoryType;
import com.memory.spi.SPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 逐字段冲突检测器 — 默认轻量实现。
 *
 * 对比新信息与已有记忆的逐字段：
 * - 内容完全相同 → 重复
 * - 核心字段差异大 → 冲突
 * - 字段互补 → 不冲突
 */
@SPI(name = "field-compare", description = "逐字段对比冲突检测")
public class FieldConflictDetector implements ConflictDetector {

    // 核心字段（content 类字段差异大时权重高）
    private static final double CORE_FIELD_WEIGHT = 0.6;
    private static final double THRESHOLD_FACTOR = 0.5;

    @Override
    public String name() {
        return "field-compare";
    }

    @Override
    public void init(Map<String, Object> params) {
        // 字段对比检测器无需额外初始化
    }

    @Override
    public ConflictResult detect(Map<String, Object> newFields, List<MemoryRecord> existing,
                                  MetaModel model) {
        if (existing == null || existing.isEmpty()) {
            return new ConflictResult(false, "", List.of(), 0);
        }

        List<String> conflictIds = new ArrayList<>();
        double maxSeverity = 0;
        String bestDesc = "";

        for (MemoryRecord record : existing) {
            double severity = computeConflictSeverity(newFields, record, model);
            if (severity > maxSeverity) {
                maxSeverity = severity;
            }
            if (severity > THRESHOLD_FACTOR) {
                conflictIds.add(record.getId());
                if (bestDesc.isEmpty()) {
                    bestDesc = describeConflict(newFields, record);
                }
            }
        }

        boolean hasConflict = !conflictIds.isEmpty();
        return new ConflictResult(hasConflict, bestDesc, conflictIds, maxSeverity);
    }

    /**
     * 计算新字段与已有记忆的冲突严重度（0-1）。
     */
    private double computeConflictSeverity(Map<String, Object> newFields,
                                            MemoryRecord existing, MetaModel model) {
        String typeKind = existing.getType();
        if (typeKind == null) return 0;

        MemoryType type = model.getType(typeKind).orElse(null);

        // 对比 content 类核心字段
        Object newContent = newFields.get("content");
        String oldContent = existing.getDataField("content");

        if (newContent == null && oldContent == null) return 0;

        String newStr = newContent != null ? newContent.toString() : "";
        String oldStr = oldContent != null ? oldContent : "";

        if (newStr.equals(oldStr)) {
            // 内容完全相同 → 重复，严重度为 0.9
            return 0.9;
        }

        // 计算字符串相似度（简易 Jaccard 距离）
        double similarity = computeTextSimilarity(newStr, oldStr);

        // 高相似度 + 不同内容 → 可能是更新
        if (similarity > 0.7) {
            return 1.0 - similarity;  // 0.3 以下，不太冲突
        }

        // 低相似度，可能是矛盾
        return CORE_FIELD_WEIGHT * (1.0 - similarity);
    }

    /** 简易文本相似度（基于词集 Jaccard） */
    private double computeTextSimilarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0;

        String[] tokensA = a.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
        String[] tokensB = b.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");

        java.util.Set<String> setA = new java.util.HashSet<>(java.util.Arrays.asList(tokensA));
        java.util.Set<String> setB = new java.util.HashSet<>(java.util.Arrays.asList(tokensB));

        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        java.util.Set<String> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);

        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    /** 描述冲突原因 */
    private String describeConflict(Map<String, Object> newFields, MemoryRecord existing) {
        Object newContent = newFields.get("content");
        String oldContent = existing.getDataField("content");
        if (newContent != null && oldContent != null) {
            return "content 字段不同: '" + truncate(oldContent, 50)
                + "' vs '" + truncate(newContent.toString(), 50) + "'";
        }
        return "字段存在差异";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
