package com.memory.agent.engine;

import com.memory.agent.spi.MemoryConsolidator;
import com.memory.model.MemoryRecord;
import com.memory.model.MetaModel;
import com.memory.spi.SPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基础记忆合并器 — 默认轻量实现。
 *
 * 基于内容相似度和同类型分组发现可合并的记忆组。
 * 合并策略由 DSL agent.consolidation.strategy 控制。
 */
@SPI(name = "simple-merge", description = "基础文本相似度合并")
public class SimpleConsolidator implements MemoryConsolidator {

    private static final double SIMILARITY_THRESHOLD = 0.75;
    private static final double DEFAULT_SIMILARITY_SCORE = 0.8;
    private static final int MAX_GROUP_SIZE = 5;

    @Override
    public String name() {
        return "simple-merge";
    }

    @Override
    public void init(Map<String, Object> params) {
        // 基础合并器无需额外初始化
    }

    @Override
    public List<ConsolidationCandidate> findCandidates(List<MemoryRecord> memories,
                                                        MetaModel model) {
        List<ConsolidationCandidate> candidates = new ArrayList<>();
        if (memories == null || memories.size() < 2) return candidates;

        // 按类型分组
        Map<String, List<MemoryRecord>> byType = new java.util.LinkedHashMap<>();
        for (MemoryRecord m : memories) {
            String type = m.getType() != null ? m.getType() : "unknown";
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(m);
        }

        // 在同类型内查找相似记忆
        for (Map.Entry<String, List<MemoryRecord>> entry : byType.entrySet()) {
            List<MemoryRecord> sameType = entry.getValue();
            if (sameType.size() < 2) continue;

            for (int i = 0; i < sameType.size(); i++) {
                List<String> groupIds = new ArrayList<>();
                groupIds.add(sameType.get(i).getId());

                for (int j = i + 1; j < sameType.size(); j++) {
                    double sim = computeContentSimilarity(sameType.get(i), sameType.get(j));
                    if (sim > SIMILARITY_THRESHOLD) {
                        groupIds.add(sameType.get(j).getId());
                        if (groupIds.size() >= MAX_GROUP_SIZE) break;
                    }
                }

                if (groupIds.size() >= 2) {
                    candidates.add(new ConsolidationCandidate(
                        groupIds, DEFAULT_SIMILARITY_SCORE,
                        "同类型 '" + entry.getKey() + "' 内容相似"
                    ));
                }
            }
        }

        return candidates;
    }

    /** 计算两条记忆的内容相似度（Jaccard） */
    private double computeContentSimilarity(MemoryRecord a, MemoryRecord b) {
        String contentA = a.getDataField("content");
        String contentB = b.getDataField("content");
        if (contentA == null || contentB == null) return 0;

        String[] tokensA = contentA.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
        String[] tokensB = contentB.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");

        java.util.Set<String> setA = new java.util.HashSet<>(java.util.Arrays.asList(tokensA));
        java.util.Set<String> setB = new java.util.HashSet<>(java.util.Arrays.asList(tokensB));

        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        java.util.Set<String> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);

        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }
}
