package com.memory.registry;

import com.memory.MemorySystemException;

/**
 * Registry 异常 — 组件注册/装配失败时抛出。
 */
public class RegistryException extends MemorySystemException {
    public RegistryException(String message) {
        super(message);
    }

    public RegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
