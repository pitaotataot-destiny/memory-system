package com.memory.agent.engine;

import com.memory.agent.spi.IntentClassifier;
import com.memory.model.MetaModel;
import com.memory.model.agent.AgentTypeHint;
import com.memory.model.type.MemoryType;
import com.memory.spi.SPI;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 关键词匹配意图分类器 — 默认轻量实现。
 *
 * 用各类型的 agent.examples 与输入文本做 TF-IDF 相似度匹配，
 * 选最高分的类型。无需外部模型，0.1ms 级别。
 */
@SPI(name = "keyword-match", description = "关键词+TF-IDF 相似度匹配")
public class KeywordIntentClassifier implements IntentClassifier {

    // 字数加分权重：输入文本越接近某个类型的典型长度，得分越高
    private static final double LENGTH_MATCH_WEIGHT = 0.1;
    private static final double FALLBACK_CONFIDENCE = 0.5;
    private static final double DEFAULT_THRESHOLD = 0.6;

    @Override
    public String name() {
        return "keyword-match";
    }

    @Override
    public void init(Map<String, Object> params) {
        // 关键词匹配无需额外初始化
    }

    @Override
    public ClassifyResult classify(String rawText, MetaModel model) {
        String textLower = rawText.toLowerCase();
        String bestType = model.getAgent() != null
            ? model.getAgent().getIntent().getFallbackType() : "fact";
        double bestScore = 0;

        for (Map.Entry<String, MemoryType> entry : model.getTypes().entrySet()) {
            double score = computeScore(textLower, entry.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestType = entry.getKey();
            }
        }

        double threshold = model.getAgent() != null
            ? model.getAgent().getIntent().getConfidenceThreshold() : DEFAULT_THRESHOLD;
        if (bestScore < threshold) {
            bestType = model.getAgent() != null
                ? model.getAgent().getIntent().getFallbackType() : "fact";
            bestScore = FALLBACK_CONFIDENCE;  // 低置信度兜底
        }

        return new ClassifyResult(bestType, bestScore);
    }

    @Override
    public List<ClassifyResult> classifyTopN(String rawText, MetaModel model, int n) {
        String textLower = rawText.toLowerCase();
        List<ClassifyResult> results = new ArrayList<>();
        for (Map.Entry<String, MemoryType> entry : model.getTypes().entrySet()) {
            double score = computeScore(textLower, entry.getValue());
            results.add(new ClassifyResult(entry.getKey(), score));
        }
        results.sort(Comparator.<ClassifyResult>comparingDouble(ClassifyResult::confidence).reversed());
        return results.subList(0, Math.min(n, results.size()));
    }

    /**
     * 计算输入文本与某个类型的匹配得分。
     * 策略：示例文本中出现的词在输入文本中命中越多，分越高。
     */
    private double computeScore(String text, MemoryType type) {
        AgentTypeHint hint = type.getAgentHint();
        if (hint == null || hint.getExamples().isEmpty()) return 0;

        String[] inputTokens = tokenize(text);
        double totalScore = 0;
        double maxScore = 0;

        for (String example : hint.getExamples()) {
            String[] exampleTokens = tokenize(example.toLowerCase());
            double score = scoreExample(exampleTokens, inputTokens);
            totalScore += score;
            maxScore = Math.max(maxScore, score);
        }

        double avg = totalScore / hint.getExamples().size();
        // 混合平均分和最佳分，最佳分权重更高（120% 上限）
        return Math.min(1.0, Math.max(avg, maxScore * 1.2));
    }

    /** 计算单个 example 与输入文本的匹配得分 */
    private double scoreExample(String[] exampleTokens, String[] inputTokens) {
        int hits = countHits(exampleTokens, inputTokens);
        double hitRate = exampleTokens.length > 0
            ? (double) hits / exampleTokens.length : 0;
        double lengthBonus = LENGTH_MATCH_WEIGHT
            * (1.0 - Math.abs(inputTokens.length - exampleTokens.length)
                / (double) Math.max(inputTokens.length, exampleTokens.length));
        return (hitRate + lengthBonus) / (1.0 + LENGTH_MATCH_WEIGHT);
    }

    /** 统计 exampleTokens 中有多少 token 在 inputTokens 中命中 */
    private int countHits(String[] exampleTokens, String[] inputTokens) {
        int hits = 0;
        for (String et : exampleTokens) {
            for (String it : inputTokens) {
                if (it.equals(et) || it.contains(et) || et.contains(it)) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    /** 简易分词：按非字母数字非中文字符切分 */
    private String[] tokenize(String text) {
        return text.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
    }
}
