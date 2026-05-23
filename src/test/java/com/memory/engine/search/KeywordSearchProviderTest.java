package com.memory.engine.search;

import com.memory.spi.SearchProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordSearchProviderTest {

    private SearchProvider provider;

    @BeforeEach
    void setUp() {
        provider = new KeywordSearchProvider();
        provider.init(null);
        // 使用英文测试数据，分词行为可控
        provider.index("1", "java programming language");
        provider.index("2", "python data science machine learning");
        provider.index("3", "java python programming language");
        provider.index("4", "rust systems programming");
    }

    @Test
    void nameReturnsKeyword() {
        assertEquals("keyword", provider.name());
    }

    @Test
    void exactMatchReturnsResult() {
        List<SearchProvider.SearchResult> results = provider.search("java", 10);
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.memoryId().equals("1")));
        assertTrue(results.stream().anyMatch(r -> r.memoryId().equals("3")));
    }

    @Test
    void multiTokenSearchMatchesAll() {
        List<SearchProvider.SearchResult> results = provider.search("programming language", 10);
        // "programming" matches 1,3,4 and "language" matches 1,3
        assertTrue(results.size() >= 2);
    }

    @Test
    void topKLimitsResults() {
        List<SearchProvider.SearchResult> results = provider.search("programming", 2);
        assertEquals(2, results.size());
    }

    @Test
    void noMatchReturnsEmpty() {
        List<SearchProvider.SearchResult> results = provider.search("nonexistent_keyword_xyz123", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void rankingByMatchCount() {
        // "java" in 1,3 and "python" in 2,3
        // "3" matches both terms, should rank highest
        List<SearchProvider.SearchResult> results = provider.search("java python", 10);
        assertFalse(results.isEmpty());
        assertEquals("3", results.get(0).memoryId());
    }
}
