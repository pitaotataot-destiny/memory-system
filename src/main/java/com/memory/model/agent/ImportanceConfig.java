package com.memory.model.agent;

import java.util.Collections;
import java.util.Map;

/**
 * 重要性分配配置 — agent.importance DSL 节点。
 */
public class ImportanceConfig {

    private String engine = "heuristic";
    private double defaultImportance = 0.8;
    private Map<String, Object> params = Collections.emptyMap();

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public double getDefaultImportance() { return defaultImportance; }
    public void setDefaultImportance(double defaultImportance) { this.defaultImportance = defaultImportance; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
