package com.memory.registry;

/**
 * Registry 异常 — 组件注册/装配失败时抛出。
 */
public class RegistryException extends RuntimeException {
    public RegistryException(String message) {
        super(message);
    }

    public RegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
