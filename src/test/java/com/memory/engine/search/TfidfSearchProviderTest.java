package com.memory.engine.search;

import com.memory.spi.SearchProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TfidfSearchProviderTest {

    private SearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TfidfSearchProvider();
        provider.init(Map.of("max_features", 10000));
        // 使用英文测试数据，分词行为可控
        provider.index("1", "java programming language object oriented");
        provider.index("2", "python data science machine learning");
        provider.index("3", "java python programming language popular");
    }

    @Test
    void nameReturnsTfidf() {
        assertEquals("tfidf", provider.name());
    }

    @Test
    void searchReturnsResults() {
        List<SearchProvider.SearchResult> results = provider.search("java", 10);
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.memoryId().equals("1")));
        assertTrue(results.stream().anyMatch(r -> r.memoryId().equals("3")));
    }

    @Test
    void rankingByTfidfScore() {
        // "science" only appears in doc 2, so IDF is highest
        List<SearchProvider.SearchResult> results = provider.search("science", 5);
        assertFalse(results.isEmpty());
        assertEquals("2", results.get(0).memoryId());
    }

    @Test
    void uniqueTermReturnsHigherScore() {
        // "learning" appears only in doc 2 (unique → higher IDF)
        // "java" appears in doc 1 and 3 (common → lower IDF)
        List<SearchProvider.SearchResult> unique = provider.search("learning", 5);
        List<SearchProvider.SearchResult> common = provider.search("java", 5);
        assertFalse(unique.isEmpty());
        assertFalse(common.isEmpty());
        // Unique term should have higher TF-IDF score
        assertTrue(unique.get(0).rawScore() > common.get(0).rawScore());
    }

    @Test
    void topKLimitsResults() {
        List<SearchProvider.SearchResult> results = provider.search("programming", 1);
        assertEquals(1, results.size());
    }
}
