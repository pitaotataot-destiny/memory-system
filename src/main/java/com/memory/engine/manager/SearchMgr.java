package com.memory.engine.manager;

import com.memory.model.search.EngineConfig;
import com.memory.model.search.SearchStep;
import com.memory.model.search.SearchStrategy;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.MemoryStore;
import com.memory.spi.SearchProvider;

import java.util.*;
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

            // If previous steps already have results and this step has fallback, skip
            if (step.isFallback() && !hasAnyResults(stepResults)) {
                // This step is a fallback, only run if previous steps had no results
                // Actually: fallback means skip this step if previous steps HAVE results
            }
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

        // Sort by score descending
        List<SearchResult> sorted = merged.values().stream()
            .sorted(Comparator.comparingDouble(SearchResult::rawScore).reversed())
            .collect(Collectors.toList());

        // Apply limit
        int limit = strategy.getLimit();
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    /**
     * Result record with memory ID, score, and source engine.
     */
    public record SearchResult(String memoryId, double rawScore, String source) {}
}
