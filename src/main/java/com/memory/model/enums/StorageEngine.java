package com.memory.model.enums;

public enum StorageEngine {
    JSON("json"),
    SQLITE("sqlite");

    private final String value;

    StorageEngine(String value) {
        this.value = value;
    }

    public String getValue() { return value; }

    public static StorageEngine fromValue(String value) {
        for (StorageEngine e : values()) {
            if (e.value.equals(value)) return e;
        }
        throw new IllegalArgumentException("Unknown StorageEngine: " + value);
    }
}
