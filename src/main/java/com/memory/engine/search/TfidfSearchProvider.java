package com.memory.engine.search;

import com.memory.spi.SearchProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TF-IDF 统计搜索提供者 — 词频-逆文档频率排序。
 * 实现 SearchProvider SPI 接口，由 Registry 按 DSL 声明装配。
 */
public class TfidfSearchProvider implements SearchProvider {

    // 文档集合：memoryId → 词频映射
    private final Map<String, Map<String, Integer>> docTermFreq = new ConcurrentHashMap<>();

    // 文档频率：term → 包含该词的文档数
    private final Map<String, Integer> docFreq = new ConcurrentHashMap<>();

    // 总文档数
    private volatile int totalDocs = 0;

    @Override
    public String name() {
        return "tfidf";
    }

    @Override
    public void init(Map<String, Object> engineParams) {
        // TF-IDF 引擎无需外部模型
    }

    @Override
    public void index(String id, String text) {
        Map<String, Integer> termFreq = new HashMap<>();
        String[] tokens = tokenize(text);
        for (String token : tokens) {
            termFreq.merge(token, 1, Integer::sum);
        }
        docTermFreq.put(id, termFreq);
        for (String token : termFreq.keySet()) {
            docFreq.merge(token, 1, Integer::sum);
        }
        totalDocs++;
    }

    @Override
    public List<SearchResult> search(String query, int topK) {
        String[] queryTokens = tokenize(query);
        Map<String, Double> scores = new HashMap<>();

        for (String qToken : queryTokens) {
            int df = docFreq.getOrDefault(qToken, 0);
            if (df == 0) continue;
            // IDF = log(N / df)
            double idf = Math.log((double) totalDocs / df);

            for (Map.Entry<String, Map<String, Integer>> docEntry : docTermFreq.entrySet()) {
                Integer tf = docEntry.getValue().get(qToken);
                if (tf == null) continue;
                // TF-IDF score
                double score = tf * idf;
                scores.merge(docEntry.getKey(), score, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new SearchResult(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * 简易分词：按空白和非字母数字字符切分，保留中文。
     */
    private String[] tokenize(String text) {
        return text.toLowerCase().split("\\s+|[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
    }
}
