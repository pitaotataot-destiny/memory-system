package com.memory.model.constraint;

/**
 * 标签约束
 */
public class TagConstraint {
    private int max = 10;
    private String allowedPattern;

    public TagConstraint() {}

    public int getMax() { return max; }
    public void setMax(int max) { this.max = max; }

    public String getAllowedPattern() { return allowedPattern; }
    public void setAllowedPattern(String allowedPattern) { this.allowedPattern = allowedPattern; }
}
