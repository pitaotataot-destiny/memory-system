package com.memory.model.search;

import com.memory.model.enums.MergeStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索策略 Meta Model
 */
public class SearchStrategy {
    private String name;
    private String description;
    private List<SearchStep> steps = new ArrayList<>();
    private MergeStrategy merge = MergeStrategy.WEIGHTED_SCORE;
    private int limit = 10;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<SearchStep> getSteps() { return steps; }
    public void setSteps(List<SearchStep> steps) { this.steps = steps; }

    public MergeStrategy getMerge() { return merge; }
    public void setMerge(MergeStrategy merge) { this.merge = merge; }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
}
