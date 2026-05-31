package com.memory.dsl;

import com.memory.MemorySystemException;

/**
 * DSL 解析异常 — YAML 解析/校验失败时抛出。
 */
public class DSLParseException extends MemorySystemException {
    public DSLParseException(String message) {
        super(message);
    }

    public DSLParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
