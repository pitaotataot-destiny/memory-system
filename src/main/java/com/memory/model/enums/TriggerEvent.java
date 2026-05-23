package com.memory.model.enums;

public enum TriggerEvent {
    MEMORY_CREATED("memory_created"),
    MEMORY_UPDATED("memory_updated"),
    SCHEDULED("scheduled");

    private final String value;

    TriggerEvent(String value) {
        this.value = value;
    }

    public String getValue() { return value; }

    public static TriggerEvent fromValue(String value) {
        for (TriggerEvent e : values()) {
            if (e.value.equals(value)) return e;
        }
        throw new IllegalArgumentException("Unknown TriggerEvent: " + value);
    }
}
