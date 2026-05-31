package com.memory.engine.manager;

import com.memory.model.MemoryRecord;
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
     *
     * 支持的合并策略：
     *   weighted_score — 按权重累加分数合并（默认）
     *   dedup          — 按 ID 去重，保留首个引擎的结果
     *   concat         — 按步骤顺序直接拼接
     *   direct         — 仅返回首个步骤的结果
     */
    private List<SearchResult> mergeResults(List<Map<String, SearchResult>> stepResults,
                                            com.memory.model.search.SearchStrategy strategy) {
        List<SearchResult> combined = combineByStrategy(stepResults, strategy.getMerge());
        List<SearchResult> filtered = applyTypeFilters(combined);
        return sortAndLimit(filtered, strategy.getLimit());
    }

    /** 按合并策略将多步骤结果合并为单一列表 */
    private List<SearchResult> combineByStrategy(List<Map<String, SearchResult>> stepResults,
                                                  com.memory.model.enums.MergeStrategy merge) {
        return switch (merge) {
            case DEDUP -> {
                Map<String, SearchResult> deduped = new LinkedHashMap<>();
                for (Map<String, SearchResult> stepResult : stepResults) {
                    for (Map.Entry<String, SearchResult> entry : stepResult.entrySet()) {
                        deduped.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
                yield new ArrayList<>(deduped.values());
            }
            case CONCAT -> {
                List<SearchResult> all = new ArrayList<>();
                for (Map<String, SearchResult> stepResult : stepResults) {
                    all.addAll(stepResult.values());
                }
                yield all;
            }
            case DIRECT -> {
                if (stepResults.isEmpty()) yield List.of();
                yield new ArrayList<>(stepResults.get(0).values());
            }
            default -> combineWeighted(stepResults);
        };
    }

    /** 加权合并：同 ID 累加分数 */
    private List<SearchResult> combineWeighted(List<Map<String, SearchResult>> stepResults) {
        Map<String, SearchResult> scored = new LinkedHashMap<>();
        for (Map<String, SearchResult> stepResult : stepResults) {
            for (Map.Entry<String, SearchResult> entry : stepResult.entrySet()) {
                String id = entry.getKey();
                SearchResult newResult = entry.getValue();
                SearchResult existing = scored.get(id);
                if (existing != null) {
                    scored.put(id, new SearchResult(
                        id,
                        existing.rawScore() + newResult.rawScore(),
                        existing.source() + "," + newResult.source()
                    ));
                } else {
                    scored.put(id, newResult);
                }
            }
        }
        return new ArrayList<>(scored.values());
    }

    /** 排序 + 截断 */
    private static List<SearchResult> sortAndLimit(List<SearchResult> results, int limit) {
        List<SearchResult> sorted = results.stream()
            .sorted(Comparator.comparingDouble(SearchResult::rawScore).reversed())
            .collect(Collectors.toList());
        if (limit <= 0 || limit >= sorted.size()) {
            return sorted;
        }
        return sorted.subList(0, limit);
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

        boolean hasInc = hasInclude;
        boolean hasExc = hasExclude;
        List<String> inc = include;
        List<String> exc = exclude;
        return results.stream()
            .filter(r -> matchesTypeFilter(r.memoryId(), hasInc, hasExc, inc, exc))
            .collect(Collectors.toList());
    }

    /** 检查单个记忆 ID 是否匹配类型过滤器 */
    private boolean matchesTypeFilter(String memoryId, boolean hasInclude,
                                       boolean hasExclude, List<String> include,
                                       List<String> exclude) {
        String type = resolveType(memoryId);
        if (type == null) return true;  // 无类型标记不过滤
        if (hasExclude && exclude.contains(type)) return false;
        if (hasInclude && !include.contains(type)) return false;
        return true;
    }

    /** 解析记忆类型：优先内存缓存，必要时回退磁盘 */
    private String resolveType(String memoryId) {
        String type = ctx.getCachedType(memoryId);
        if (type != null) return type;

        var store = ctx.getStore(
            ctx.getMetaModel().getGlobals().getStorage().getEngine().getValue());
        String json = store.load(memoryId);
        if (json == null) return null;
        try {
            MemoryRecord record = MemoryRecord.fromJson(json);
            type = record.getType();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (type != null) {
            ctx.cacheType(memoryId, type);
        }
        return type;
    }

    /**
     * Result record with memory ID, score, and source engine.
     */
    public record SearchResult(String memoryId, double rawScore, String source) {}
}
