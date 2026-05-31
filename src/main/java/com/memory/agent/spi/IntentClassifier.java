package com.memory.agent.spi;

import com.memory.model.MetaModel;
import com.memory.spi.SPI;

import java.util.List;
import java.util.Map;

/**
 * 记忆意图分类器 SPI 扩展点。
 *
 * 输入原始文本，输出最可能的记忆类型及置信度。
 * 可替换为规则匹配 / TF-IDF 相似度 / LLM 调用等实现。
 */
@SPI(name = "intent-classifier", description = "记忆意图分类器")
public interface IntentClassifier {

    /** 分类器标识，对应 DSL agent.intent.engine */
    String name();

    /** 初始化（从 DSL 接收引擎参数） */
    void init(Map<String, Object> params);

    /**
     * 给定原始文本，返回最可能的类型 + 置信度。
     * 所有类型置信度低于 threshold 时返回 fallbackType。
     */
    ClassifyResult classify(String rawText, MetaModel model);

    /** 获取类型排名列表（top N） */
    List<ClassifyResult> classifyTopN(String rawText, MetaModel model, int n);

    record ClassifyResult(String typeKind, double confidence) {}
}
