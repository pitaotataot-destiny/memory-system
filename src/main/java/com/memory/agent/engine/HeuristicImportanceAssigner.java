package com.memory.agent.engine;

import com.memory.agent.spi.ImportanceAssigner;
import com.memory.model.MetaModel;
import com.memory.spi.SPI;

import java.util.Map;
import java.util.Set;

/**
 * 启发式重要性评估器 — 默认轻量实现。
 *
 * 评分规则：
 * - 基础分 = DSL 配置的 default_importance
 * - 内容越长、信息量越大 → 轻微加分
 * - 有标签 → 轻微加分（标签化的记忆通常更有用）
 */
@SPI(name = "heuristic", description = "启发式规则重要性评估")
public class HeuristicImportanceAssigner implements ImportanceAssigner {

    // 调节因子
    private static final double CONTENT_LENGTH_BOOST = 0.05;
    private static final double TAG_BOOST = 0.03;
    private static final double PREFERENCE_BOOST = 0.1;
    private static final double DEFAULT_SCORE = 0.8;
    private static final int MIN_CONTENT_LENGTH_FOR_BOOST = 20;

    @Override
    public String name() {
        return "heuristic";
    }

    @Override
    public void init(Map<String, Object> params) {
        // 启发式评估器无需额外初始化
    }

    @Override
    public double assign(String typeKind, Map<String, Object> fields, Set<String> tags,
                          MetaModel model) {
        double baseImportance = model.getAgent() != null
            ? model.getAgent().getImportance().getDefaultImportance()
            : DEFAULT_SCORE;

        double score = baseImportance;

        // preference 类型额外加分（用户偏好更值得记住）
        if ("preference".equals(typeKind)) {
            score += PREFERENCE_BOOST;
        }

        // 内容长度加分
        Object content = fields.get("content");
        if (content != null) {
            String text = content.toString();
            if (text.length() > MIN_CONTENT_LENGTH_FOR_BOOST) {
                score += CONTENT_LENGTH_BOOST;
            }
        }

        // 有标签加分
        if (tags != null && !tags.isEmpty()) {
            score += TAG_BOOST * Math.min(tags.size(), 5);
        }

        return Math.min(1.0, Math.max(0, score));
    }
}
