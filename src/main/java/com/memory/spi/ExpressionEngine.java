package com.memory.spi;

import com.memory.engine.expression.DefaultExpressionEngine;

import java.util.Map;

/**
 * 表达式解析 SPI 扩展点。
 *
 * 实现此接口可替换条件表达式引擎（如 SpEL → Aviator → MVEL）。
 * 用于触发器中的 condition 字段解析。
 *
 * 方法数：2
 */
@SPI(name = "expression-engine", description = "表达式解析扩展点",
     defaultImpl = DefaultExpressionEngine.class)
public interface ExpressionEngine {

    /**
     * 表达式引擎标识。如 "spel", "aviator"。
     */
    String name();

    /**
     * 计算布尔表达式。
     * @param expression 表达式字符串（如 "memory_count > globals.max_memory_size"）
     * @param variables  变量上下文（如 {"memory_count": 5100, "globals.max_memory_size": 5000}）
     * @return 表达式求值结果
     */
    boolean evaluate(String expression, Map<String, Object> variables);
}
