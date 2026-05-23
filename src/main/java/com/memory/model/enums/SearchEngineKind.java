package com.memory.model.enums;

public enum SearchEngineKind {
    KEYWORD("keyword"),
    TFIDF("tfidf"),
    EMBEDDING("embedding");

    private final String value;

    SearchEngineKind(String value) {
        this.value = value;
    }

    public String getValue() { return value; }

    public static SearchEngineKind fromValue(String value) {
        for (SearchEngineKind e : values()) {
            if (e.value.equals(value)) return e;
        }
        throw new IllegalArgumentException("Unknown SearchEngineKind: " + value);
    }
}
