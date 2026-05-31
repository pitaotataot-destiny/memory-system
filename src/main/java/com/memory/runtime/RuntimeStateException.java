package com.memory.runtime;

import com.memory.MemorySystemException;

/**
 * Runtime Context 异常 — 运行时状态访问失败时抛出。
 */
public class RuntimeStateException extends MemorySystemException {
    public RuntimeStateException(String message) {
        super(message);
    }

    public RuntimeStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
