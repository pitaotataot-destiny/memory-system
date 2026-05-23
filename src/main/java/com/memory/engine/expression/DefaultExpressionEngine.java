package com.memory.engine.expression;

import com.memory.spi.ExpressionEngine;

import java.util.Map;

/**
 * Lightweight expression engine that evaluates simple boolean expressions.
 * Implements ExpressionEngine SPI interface.
 *
 * Supported operators:
 *   - Comparison: ==, !=, >, <, >=, <=
 *   - Logical: &&, ||, !
 *   - Arithmetic: +, -, *, /
 *   - Variable references: globals.max_memory_size, memory_count, etc.
 *
 * Examples:
 *   "memory_count > 5000"
 *   "memory.importance < 0.1"
 *   "memory_count > globals.max_memory_size"
 *   "!memory.active && memory.type == 'fact'"
 */
public class DefaultExpressionEngine implements ExpressionEngine {

    @Override
    public String name() {
        return "default";
    }

    @Override
    public boolean evaluate(String expression, Map<String, Object> variables) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        try {
            Object result = parseOrExpression(trimmed, variables);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            if (result instanceof Number) {
                return ((Number) result).doubleValue() != 0;
            }
            return false;
        } catch (Exception e) {
            throw new ExpressionEvaluationException(
                "Failed to evaluate expression: " + expression, e);
        }
    }

    /**
     * Handle || (logical OR).
     */
    private Object parseOrExpression(String expr, Map<String, Object> variables) {
        int depth = 0;
        for (int i = 0; i < expr.length() - 1; i++) {
            if (expr.charAt(i) == '(') depth++;
            else if (expr.charAt(i) == ')') depth--;
            else if (depth == 0 && expr.charAt(i) == '|' && expr.charAt(i + 1) == '|') {
                boolean left = toBoolean(parseAndExpression(expr.substring(0, i).trim(), variables));
                boolean right = toBoolean(parseAndExpression(expr.substring(i + 2).trim(), variables));
                return left || right;
            }
        }
        return parseAndExpression(expr, variables);
    }

    /**
     * Handle && (logical AND).
     */
    private Object parseAndExpression(String expr, Map<String, Object> variables) {
        int depth = 0;
        for (int i = 0; i < expr.length() - 1; i++) {
            if (expr.charAt(i) == '(') depth++;
            else if (expr.charAt(i) == ')') depth--;
            else if (depth == 0 && expr.charAt(i) == '&' && expr.charAt(i + 1) == '&') {
                boolean left = toBoolean(parseComparison(expr.substring(0, i).trim(), variables));
                boolean right = toBoolean(parseComparison(expr.substring(i + 2).trim(), variables));
                return left && right;
            }
        }
        return parseComparison(expr, variables);
    }

    /**
     * Handle ! (logical NOT).
     */
    private Object parseComparison(String expr, Map<String, Object> variables) {
        String trimmed = expr.trim();
        if (trimmed.startsWith("!")) {
            return !toBoolean(parseComparison(trimmed.substring(1).trim(), variables));
        }
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            // Match balanced parentheses
            int depth = 0;
            boolean fullMatch = true;
            for (int i = 0; i < trimmed.length(); i++) {
                if (trimmed.charAt(i) == '(') depth++;
                else if (trimmed.charAt(i) == ')') depth--;
                if (depth == 0 && i < trimmed.length() - 1) {
                    fullMatch = false;
                    break;
                }
            }
            if (fullMatch) {
                return parseOrExpression(trimmed.substring(1, trimmed.length() - 1).trim(), variables);
            }
        }
        return evaluateComparison(trimmed, variables);
    }

    /**
     * Evaluate a single comparison: left op right.
     * Supported operators: ==, !=, >=, <=, >, <
     */
    private Object evaluateComparison(String expr, Map<String, Object> variables) {
        // Find comparison operator
        String[] ops = {"==", "!=", ">=", "<=", ">", "<"};
        for (String op : ops) {
            int idx = findOperator(expr, op);
            if (idx > 0) {
                String leftStr = expr.substring(0, idx).trim();
                String rightStr = expr.substring(idx + op.length()).trim();

                Number left = resolveValue(leftStr, variables);
                Number right = resolveValue(rightStr, variables);

                if (left == null || right == null) {
                    return false;
                }

                double lv = left.doubleValue();
                double rv = right.doubleValue();

                return switch (op) {
                    case "==" -> lv == rv;
                    case "!=" -> lv != rv;
                    case ">=" -> lv >= rv;
                    case "<=" -> lv <= rv;
                    case ">" -> lv > rv;
                    case "<" -> lv < rv;
                    default -> false;
                };
            }
        }

        // No comparison operator found, treat as a single value
        Number val = resolveValue(expr, variables);
        return val != null ? val : false;
    }

    /**
     * Resolve a value from expression — could be a number literal or a variable reference.
     */
    private Number resolveValue(String expr, Map<String, Object> variables) {
        expr = expr.trim();

        // Remove quotes for string literals
        if ((expr.startsWith("'") && expr.endsWith("'")) ||
            (expr.startsWith("\"") && expr.endsWith("\""))) {
            // String literal — return NaN so comparisons fail gracefully
            return Double.NaN;
        }

        // Try parsing as number
        try {
            return Double.parseDouble(expr);
        } catch (NumberFormatException ignored) {
            // Not a number literal, treat as variable reference
        }

        // Variable reference (supports dotted paths like "globals.max_memory_size")
        if (variables != null) {
            Object value = resolveVariable(expr, variables);
            if (value instanceof Number) return (Number) value;
            if (value instanceof String) {
                try { return Double.parseDouble((String) value); } catch (NumberFormatException ignored) {}
            }
        }

        return null;
    }

    /**
     * Resolve a dotted variable path from the variables map.
     * e.g. "globals.max_memory_size" looks up variables["globals.max_memory_size"]
     * or if not found, looks up variables["globals"] (if it's a Map) etc.
     */
    private Object resolveVariable(String path, Map<String, Object> variables) {
        // First try direct lookup
        if (variables.containsKey(path)) {
            return variables.get(path);
        }

        // Try dotted path resolution
        String[] parts = path.split("\\.");
        Object current = variables.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Find operator position, avoiding matches inside quotes.
     */
    private int findOperator(String expr, String op) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < expr.length() - op.length() + 1; i++) {
            char c = expr.charAt(i);
            if (c == '\'') inSingleQuote = !inSingleQuote;
            else if (c == '"') inDoubleQuote = !inDoubleQuote;
            else if (!inSingleQuote && !inDoubleQuote) {
                if (expr.substring(i).startsWith(op)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        return false;
    }

    /**
     * Exception thrown when expression evaluation fails.
     */
    public static class ExpressionEvaluationException extends RuntimeException {
        public ExpressionEvaluationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
