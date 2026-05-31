package com.memory.agent.spi;

import com.memory.model.MetaModel;
import com.memory.spi.SPI;

import java.util.Map;
import java.util.Set;

/**
 * 重要性评估器 SPI 扩展点。
 *
 * 从内容特征计算记忆的初始重要性分数。
 */
@SPI(name = "importance-assigner", description = "重要性评估器")
public interface ImportanceAssigner {

    /** 评估器标识，对应 DSL agent.importance.engine */
    String name();

    /** 初始化（从 DSL 接收引擎参数） */
    void init(Map<String, Object> params);

    /**
     * 从内容和上下文计算初始重要性（0-1）。
     */
    double assign(String typeKind, Map<String, Object> fields, Set<String> tags,
                  MetaModel model);
}
