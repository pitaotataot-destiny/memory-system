package com.memory.engine.manager;

import com.memory.model.search.SearchStep;
import com.memory.model.search.SearchStrategy;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.SearchProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Search manager — orchestrates multi-step search strategies.
 * Executes search steps (keyword, tfidf, embedding), merges results
 * by weight, and applies type filters and result limits.
 */
public class SearchMgr {

    private final MemoryRuntimeContext ctx;

    public SearchMgr(MemoryRuntimeContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Execute the default search strategy.
     *
     * @param query the search query string
     * @return merged and ranked search results
     */
    public List<SearchResult> search(String query) {
        SearchStrategy strategy = ctx.getMetaModel().getSearch().getDefaultStrategy();
        return search(query, strategy);
    }

    /**
     * Execute a named search strategy.
     *
     * @param query         the search query string
     * @param strategyName  strategy name from DSL
     * @return merged and ranked search results
     */
    public List<SearchResult> search(String query, String strategyName) {
        SearchStrategy strategy = ctx.getMetaModel().getSearch().getStrategies().get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown search strategy: " + strategyName);
        }
        return search(query, strategy);
    }

    /**
     * Execute a specific search strategy with its steps.
     * Each step runs a SearchProvider, results are merged by weight.
     *
     * @param query    the search query string
     * @param strategy the search strategy to execute
     * @return merged and ranked search results
     */
    public List<SearchResult> search(String query, SearchStrategy strategy) {
        List<Map<String, SearchResult>> stepResults = new ArrayList<>();

        // Execute each step
        for (SearchStep step : strategy.getSteps()) {
            String engineName = step.getEngine().getValue();
            SearchProvider provider = ctx.getSearchProvider(engineName);
            if (provider == null) {
                continue; // Skip unavailable providers
            }

            int topK = step.getTopK();
            List<SearchProvider.SearchResult> rawResults = provider.search(query, topK);

            // 如果此步骤标记为 fallback 且之前步骤已有结果，则跳过
            if (step.isFallback() && hasAnyResults(stepResults)) {
                continue;
            }

            // Index results by memory ID
            Map<String, SearchResult> indexed = new LinkedHashMap<>();
            for (SearchProvider.SearchResult raw : rawResults) {
                indexed.put(raw.memoryId(), new SearchResult(
                    raw.memoryId(),
                    raw.rawScore() * step.getWeight(),
                    engineName
                ));
            }
            stepResults.add(indexed);
        }

        // Merge results
        return mergeResults(stepResults, strategy);
    }

    /**
     * Check if any previous step has results.
     */
    private boolean hasAnyResults(List<Map<String, SearchResult>> stepResults) {
        return stepResults.stream().anyMatch(m -> !m.isEmpty());
    }

    /**
     * Merge results from all steps according to the merge strategy.
     * Default: weighted_score — sum of weighted scores.
     */
    private List<SearchResult> mergeResults(List<Map<String, SearchResult>> stepResults,
                                            com.memory.model.search.SearchStrategy strategy) {
        // Combine all results by memory ID, summing scores
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (Map<String, SearchResult> stepResult : stepResults) {
            for (Map.Entry<String, SearchResult> entry : stepResult.entrySet()) {
                String id = entry.getKey();
                SearchResult newResult = entry.getValue();

                SearchResult existing = merged.get(id);
                if (existing != null) {
                    // Sum scores (weighted merge)
                    merged.put(id, new SearchResult(
                        id,
                        existing.rawScore() + newResult.rawScore(),
                        existing.source() + "," + newResult.source()
                    ));
                } else {
                    merged.put(id, newResult);
                }
            }
        }

        // Apply type filters from DSL configuration
        List<SearchResult> filtered = applyTypeFilters(merged.values());

        // Sort by score descending
        List<SearchResult> sorted = filtered.stream()
            .sorted(Comparator.comparingDouble(SearchResult::rawScore).reversed())
            .collect(Collectors.toList());

        // Apply limit
        int limit = strategy.getLimit();
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    /**
     * 应用 DSL type_filters 按记忆类型过滤搜索结果。
     * - include 非空：仅保留类型在 include 列表中的记忆
     * - exclude 非空：排除类型在 exclude 列表中的记忆
     */
    private List<SearchResult> applyTypeFilters(java.util.Collection<SearchResult> results) {
        var searchConfig = ctx.getMetaModel().getSearch();
        if (searchConfig == null || searchConfig.getTypeFiltersInclude() == null) {
            return new ArrayList<>(results);
        }

        List<String> include = searchConfig.getTypeFiltersInclude();
        List<String> exclude = searchConfig.getTypeFiltersExclude();

        // 两者都为空，不过滤
        boolean hasInclude = include != null && !include.isEmpty();
        boolean hasExclude = exclude != null && !exclude.isEmpty();
        if (!hasInclude && !hasExclude) {
            return new ArrayList<>(results);
        }

        var store = ctx.getStore(
            ctx.getMetaModel().getGlobals().getStorage().getEngine().getValue());

        return results.stream().filter(r -> {
            String json = store.load(r.memoryId());
            if (json == null) return false;
            String type = parseMetaType(json);
            if (type == null) return true; // 未标记类型的旧数据不过滤

            // exclude 优先
            if (hasExclude && exclude.contains(type)) return false;
            // include：空列表 = 全部通过
            if (hasInclude && !include.contains(type)) return false;
            return true;
        }).collect(Collectors.toList());
    }

    /**
     * 从记忆包装 JSON 中提取 _type 元数据字段。
     */
    private static String parseMetaType(String json) {
        String key = "\"_type\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        int end = json.indexOf('"', idx);
        if (end < 0) return null;
        return json.substring(idx, end);
    }

    /**
     * Result record with memory ID, score, and source engine.
     */
    public record SearchResult(String memoryId, double rawScore, String source) {}
}
