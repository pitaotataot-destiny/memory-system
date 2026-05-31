package com.memory;

/**
 * 记忆系统统一异常基类。
 *
 * 所有库内抛出的异常均继承此类，调用方可统一 catch：
 * <pre>{@code
 * try (MemoryClient client = MemoryFactory.create(path)) {
 *     client.search("...");
 * } catch (MemorySystemException e) {
 *     // 处理所有库内异常
 * }
 * }</pre>
 *
 * 子类按模块分类：
 * <ul>
 *   <li>{@link com.memory.dsl.DSLParseException} — DSL 解析/校验失败</li>
 *   <li>{@link com.memory.registry.RegistryException} — 组件注册/装配失败</li>
 *   <li>{@link com.memory.runtime.RuntimeStateException} — 运行时状态非法</li>
 *   <li>{@code ExpressionEvaluationException} — 表达式求值失败</li>
 * </ul>
 */
public class MemorySystemException extends RuntimeException {

    public MemorySystemException(String message) {
        super(message);
    }

    public MemorySystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
