package com.memory.model.enums;

public enum MemoryTypeKind {
    FACT("fact"),
    PREFERENCE("preference"),
    CONTEXT("context"),
    REFERENCE("reference");

    private final String value;

    MemoryTypeKind(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MemoryTypeKind fromValue(String value) {
        for (MemoryTypeKind kind : values()) {
            if (kind.value.equals(value)) return kind;
        }
        throw new IllegalArgumentException("Unknown MemoryTypeKind: " + value);
    }
}
