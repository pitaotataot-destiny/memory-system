package com.memory.engine.search;

import com.memory.spi.SearchProvider;
import com.memory.spi.SPI;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 向量语义搜索提供者 — 基于向量相似度检索。
 * 实现 SearchProvider SPI 接口，由 Registry 按 DSL 声明装配。
 *
 * 当前为框架预留（向量计算未实现），待引入 sentence-transformers Java 绑定后补全。
 */
@SPI(name = "embedding", description = "向量语义搜索（余弦相似度）")
public class EmbeddingSearchProvider implements SearchProvider {

    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.65;
    private static final int EMBEDDING_DIMENSION = 768;
    private static final int DUMMY_VEC_HASH_MOD = 1000;
    private static final double DUMMY_VEC_HASH_DIVISOR = 1000.0;
    private static final double DUMMY_VEC_HASH_OFFSET = 0.5;

    // 记忆 ID → 向量（占位，后续替换为 float[]）
    private final Map<String, float[]> vectorStore = new ConcurrentHashMap<>();

    // DSL 配置参数
    private String model = "";
    private String similarity = "cosine";
    private double threshold = DEFAULT_SIMILARITY_THRESHOLD;

    @Override
    public String name() {
        return "embedding";
    }

    @Override
    public void init(Map<String, Object> engineParams) {
        this.model = (String) engineParams.getOrDefault("model", "");
        this.similarity = (String) engineParams.getOrDefault("similarity", "cosine");
        this.threshold = asDouble(engineParams.get("threshold"), DEFAULT_SIMILARITY_THRESHOLD);
        // 后续：加载 embedding 模型到 cache_dir
    }

    @Override
    public void index(String id, String text) {
        // 后续：text → embedding model → float[] → vectorStore.put(id, vector)
        float[] vector = dummyEmbed(text); // placeholder
        vectorStore.put(id, vector);
    }

    @Override
    public List<SearchResult> search(String query, int topK) {
        // 后续：query → embedding → cosine similarity against all vectors
        float[] queryVector = dummyEmbed(query); // placeholder

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : vectorStore.entrySet()) {
            double score = cosineSimilarity(queryVector, entry.getValue());
            if (score >= threshold) {
                results.add(new SearchResult(entry.getKey(), score));
            }
        }

        return results.stream()
                .sorted(Comparator.<SearchResult>comparingDouble(SearchResult::rawScore).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 余弦相似度计算。
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    /**
     * 占位向量 — 返回基于字符串哈希的固定长度向量。
     * 后续替换为真实 embedding 模型。
     */
    private float[] dummyEmbed(String text) {
        float[] vec = new float[EMBEDDING_DIMENSION];
        int hash = text.hashCode();
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            vec[i] = (float) (((hash * (i + 1)) % DUMMY_VEC_HASH_MOD)
                / DUMMY_VEC_HASH_DIVISOR - DUMMY_VEC_HASH_OFFSET);
        }
        return vec;
    }

    private double asDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }
}
