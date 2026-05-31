package com.memory.model.agent;

import java.util.Collections;
import java.util.Map;

/**
 * 记忆合并配置 — agent.consolidation DSL 节点。
 */
public class ConsolidationConfig {

    private String engine = "simple-merge";
    private String schedule = "0 2 * * *";
    private String strategy = "latest-wins";  // latest-wins | most-confident | weave
    private Map<String, Object> params = Collections.emptyMap();

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
