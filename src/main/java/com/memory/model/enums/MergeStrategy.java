package com.memory.model.enums;

public enum MergeStrategy {
    WEIGHTED_SCORE("weighted_score"),
    DEDUP("dedup"),
    CONCAT("concat"),
    DIRECT("direct");

    private final String value;

    MergeStrategy(String value) {
        this.value = value;
    }

    public String getValue() { return value; }

    public static MergeStrategy fromValue(String value) {
        for (MergeStrategy s : values()) {
            if (s.value.equals(value)) return s;
        }
        throw new IllegalArgumentException("Unknown MergeStrategy: " + value);
    }
}
