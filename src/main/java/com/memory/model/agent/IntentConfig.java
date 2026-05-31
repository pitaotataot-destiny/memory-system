package com.memory.model.agent;

import java.util.Collections;
import java.util.Map;

/**
 * 意图分类配置 — agent.intent DSL 节点。
 */
public class IntentConfig {

    private String engine = "keyword-match";
    private double confidenceThreshold = 0.6;
    private String fallbackType = "fact";
    private Map<String, Object> params = Collections.emptyMap();

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double confidenceThreshold) { this.confidenceThreshold = confidenceThreshold; }

    public String getFallbackType() { return fallbackType; }
    public void setFallbackType(String fallbackType) { this.fallbackType = fallbackType; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
