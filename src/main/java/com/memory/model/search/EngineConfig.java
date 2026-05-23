package com.memory.model.search;

import com.memory.model.enums.SearchEngineKind;

import java.util.HashMap;
import java.util.Map;

/**
 * 搜索引擎配置
 */
public class EngineConfig {
    private SearchEngineKind kind;
    private boolean enabled = true;
    private String description = "";
    private Map<String, Object> params = new HashMap<>();

    public EngineConfig() {}

    public SearchEngineKind getKind() { return kind; }
    public void setKind(SearchEngineKind kind) { this.kind = kind; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
