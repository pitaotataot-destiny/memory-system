package com.memory.model.search;

import com.memory.model.enums.SearchEngineKind;

/**
 * 搜索步骤（多引擎组合中的一步）
 */
public class SearchStep {
    private SearchEngineKind engine;
    private double weight = 1.0;
    private int topK = 20;
    private boolean fallback = false;

    public SearchStep() {}

    public SearchEngineKind getEngine() { return engine; }
    public void setEngine(SearchEngineKind engine) { this.engine = engine; }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
}
