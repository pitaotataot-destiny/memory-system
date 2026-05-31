package com.memory.model.agent;

import java.util.Collections;
import java.util.Map;

/**
 * 冲突检测配置 — agent.conflict DSL 节点。
 */
public class ConflictConfig {

    private String engine = "field-compare";
    private double severityThreshold = 0.5;
    private String resolution = "ask";  // ask | auto-update | ignore | merge
    private Map<String, Object> params = Collections.emptyMap();

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public double getSeverityThreshold() { return severityThreshold; }
    public void setSeverityThreshold(double severityThreshold) { this.severityThreshold = severityThreshold; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
