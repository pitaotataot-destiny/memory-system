package com.memory.agent.spi;

import com.memory.model.MetaModel;
import com.memory.spi.SPI;

import java.util.Map;
import java.util.Set;

/**
 * 结构化信息提取器 SPI 扩展点。
 *
 * 输入原始文本和已确定的类型，抽取结构化字段和标签。
 */
@SPI(name = "info-extractor", description = "结构化信息提取器")
public interface InformationExtractor {

    /** 提取器标识，对应 DSL agent.extraction.engine */
    String name();

    /** 初始化（从 DSL 接收引擎参数） */
    void init(Map<String, Object> params);

    /**
     * 给定原始文本和已确定的 typeKind，提取结构化字段和标签。
     */
    ExtractedInfo extract(String rawText, String typeKind, MetaModel model);

    record ExtractedInfo(Map<String, Object> fields, Set<String> tags) {}
}
