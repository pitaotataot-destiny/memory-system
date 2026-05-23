package com.memory.dsl;

/**
 * DSL 解析异常
 */
public class DSLParseException extends RuntimeException {
    public DSLParseException(String message) {
        super(message);
    }

    public DSLParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
