package com.memory.model.agent;

import java.util.Collections;
import java.util.Map;

/**
 * 信息提取配置 — agent.extraction DSL 节点。
 */
public class ExtractionConfig {

    private String engine = "template";
    private Map<String, Object> params = Collections.emptyMap();

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
