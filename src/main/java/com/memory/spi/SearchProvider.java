package com.memory.spi;

import java.util.List;
import java.util.Map;

/**
 * 搜索提供者 SPI 扩展点。
 *
 * 实现此接口可添加新的搜索方式（如 BM25、向量数据库等）。
 * 每个实现对应 DSL 中 search.engines 下的一个引擎标识。
 *
 * 方法数：4
 */
@SPI(name = "search-provider", description = "搜索提供者扩展点")
public interface SearchProvider {

    /**
     * 搜索引擎标识，必须与 DSL 中 search.engines 的 key 对应。
     * 如 "keyword", "tfidf", "embedding"。
     */
    String name();

    /**
     * 初始化搜索引擎（加载模型、构建索引等）。
     * 由 Registry 在 assemble 阶段调用。
     * @param engineParams DSL 中 engines.*.params 的配置
     */
    void init(Map<String, Object> engineParams);

    /**
     * 为记忆建立索引。
     * @param id   记忆 ID
     * @param text 用于索引的文本内容
     */
    void index(String id, String text);

    /**
     * 执行搜索。
     * @param query 查询文本
     * @param topK  返回结果数量上限
     * @return 结果列表，每项包含 memoryId 和 rawScore
     */
    List<SearchResult> search(String query, int topK);

    /**
     * 搜索结果。
     */
    record SearchResult(String memoryId, double rawScore) {}
}
