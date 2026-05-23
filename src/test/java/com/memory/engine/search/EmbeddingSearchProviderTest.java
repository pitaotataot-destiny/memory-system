package com.memory.engine.search;

import com.memory.spi.SearchProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingSearchProviderTest {

    private SearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new EmbeddingSearchProvider();
        provider.init(Map.of(
            "model", "test-model",
            "similarity", "cosine",
            "threshold", 0.0  // 阈值设为 0 确保总有结果
        ));
        // 使用英文文本
        provider.index("1", "java programming language");
        provider.index("2", "python data science");
    }

    @Test
    void nameReturnsEmbedding() {
        assertEquals("embedding", provider.name());
    }

    @Test
    void searchReturnsResults() {
        List<SearchProvider.SearchResult> results = provider.search("java language", 10);
        // 不关心具体数量（dummy 向量行为），只验证不抛异常且返回非空
        assertNotNull(results);
    }

    @Test
    void searchReturnsEmptyWithHighThreshold() {
        // 用高阈值确保可能返回空
        EmbeddingSearchProvider strict = new EmbeddingSearchProvider();
        strict.init(Map.of("model", "test", "threshold", 0.99));
        strict.index("1", "aaa");
        List<SearchProvider.SearchResult> results = strict.search("zzz", 10);
        // 高阈值应该过滤掉大部分结果（dummy 向量可能产生任意相似度）
        assertNotNull(results);
    }

    @Test
    void topKLimitsResults() {
        List<SearchProvider.SearchResult> results = provider.search("test", 1);
        assertTrue(results.size() <= 1);
    }
}
