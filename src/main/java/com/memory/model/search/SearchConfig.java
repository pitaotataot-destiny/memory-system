package com.memory.model.search;

import java.util.*;

/**
 * 搜索配置 Meta Model
 */
public class SearchConfig {
    private Map<String, EngineConfig> engines = new HashMap<>();
    private Map<String, SearchStrategy> strategies = new HashMap<>();
    private List<String> typeFiltersInclude = Collections.emptyList();
    private List<String> typeFiltersExclude = Collections.emptyList();

    public Map<String, EngineConfig> getEngines() { return engines; }
    public void setEngines(Map<String, EngineConfig> engines) { this.engines = engines; }

    public Map<String, SearchStrategy> getStrategies() { return strategies; }
    public void setStrategies(Map<String, SearchStrategy> strategies) { this.strategies = strategies; }

    public List<String> getTypeFiltersInclude() { return typeFiltersInclude; }
    public void setTypeFiltersInclude(List<String> typeFiltersInclude) { this.typeFiltersInclude = typeFiltersInclude; }

    public List<String> getTypeFiltersExclude() { return typeFiltersExclude; }
    public void setTypeFiltersExclude(List<String> typeFiltersExclude) { this.typeFiltersExclude = typeFiltersExclude; }

    /**
     * 获取默认搜索策略，若无则返回 fallback
     */
    public SearchStrategy getDefaultStrategy() {
        SearchStrategy s = strategies.get("default");
        if (s != null) return s;
        SearchStrategy fallback = new SearchStrategy();
        fallback.setName("fallback");
        fallback.setDescription("Fallback strategy");
        SearchStep step = new SearchStep();
        step.setEngine(com.memory.model.enums.SearchEngineKind.KEYWORD);
        step.setWeight(1.0);
        fallback.setSteps(List.of(step));
        return fallback;
    }
}
