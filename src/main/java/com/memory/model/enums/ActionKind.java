package com.memory.model.enums;

public enum ActionKind {
    RUN_DECAY("run_decay"),
    PURGE("purge"),
    GENERATE_EMBEDDING("generate_embedding"),
    NORMALIZE_TAGS("normalize_tags");

    private final String value;

    ActionKind(String value) {
        this.value = value;
    }

    public String getValue() { return value; }

    public static ActionKind fromValue(String value) {
        for (ActionKind a : values()) {
            if (a.value.equals(value)) return a;
        }
        throw new IllegalArgumentException("Unknown ActionKind: " + value);
    }
}
