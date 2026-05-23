package com.memory.engine.search;

import com.memory.spi.SearchProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关键词匹配搜索提供者 — 精确字面匹配。
 * 实现 SearchProvider SPI 接口，由 Registry 按 DSL 声明装配。
 */
public class KeywordSearchProvider implements SearchProvider {

    // 简易倒排索引：term → [memoryId, ...]
    private final Map<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();

    // 记忆原文缓存：memoryId → text
    private final Map<String, String> contentCache = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public void init(Map<String, Object> engineParams) {
        // 关键词引擎无需外部模型，索引为空即可
    }

    @Override
    public void index(String id, String text) {
        contentCache.put(id, text);
        String[] tokens = text.toLowerCase().split("\\s+|[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            invertedIndex.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(id);
        }
    }

    @Override
    public List<SearchResult> search(String query, int topK) {
        String[] tokens = query.toLowerCase().split("\\s+|[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
        Map<String, Integer> matchCount = new HashMap<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            Set<String> matchedIds = invertedIndex.getOrDefault(token, Collections.emptySet());
            for (String id : matchedIds) {
                matchCount.merge(id, 1, Integer::sum);
            }
        }
        // 按命中词数降序排序
        return matchCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new SearchResult(e.getKey(), e.getValue()))
                .toList();
    }
}
