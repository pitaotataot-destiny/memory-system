package com.memory.engine.expression;

import com.memory.spi.ExpressionEngine;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DefaultExpressionEngineTest {

    private ExpressionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultExpressionEngine();
    }

    @Test
    @Order(1)
    void nameReturnsDefault() {
        assertEquals("default", engine.name());
    }

    @Test
    @Order(2)
    void greaterThan() {
        Map<String, Object> vars = Map.of("memory_count", 5100);
        assertTrue(engine.evaluate("memory_count > 5000", vars));
        assertFalse(engine.evaluate("memory_count > 6000", vars));
    }

    @Test
    @Order(3)
    void lessThan() {
        Map<String, Object> vars = Map.of("importance", 0.05);
        assertTrue(engine.evaluate("importance < 0.1", vars));
        assertFalse(engine.evaluate("importance < 0.01", vars));
    }

    @Test
    @Order(4)
    void equalsNotEquals() {
        Map<String, Object> vars = Map.of("status", 1.0);
        assertTrue(engine.evaluate("status == 1", vars));
        assertFalse(engine.evaluate("status == 2", vars));
        assertTrue(engine.evaluate("status != 0", vars));
        assertFalse(engine.evaluate("status != 1", vars));
    }

    @Test
    @Order(5)
    void greaterThanOrEqual() {
        Map<String, Object> vars = Map.of("count", 100);
        assertTrue(engine.evaluate("count >= 100", vars));
        assertTrue(engine.evaluate("count >= 50", vars));
        assertFalse(engine.evaluate("count >= 200", vars));
    }

    @Test
    @Order(6)
    void lessThanOrEqual() {
        Map<String, Object> vars = Map.of("count", 100);
        assertTrue(engine.evaluate("count <= 100", vars));
        assertTrue(engine.evaluate("count <= 200", vars));
        assertFalse(engine.evaluate("count <= 50", vars));
    }

    @Test
    @Order(7)
    void logicalAnd() {
        Map<String, Object> vars = Map.of(
            "memory_count", 5100,
            "importance", 0.5
        );
        assertTrue(engine.evaluate("memory_count > 5000 && importance > 0.1", vars));
        assertFalse(engine.evaluate("memory_count > 5000 && importance < 0.1", vars));
    }

    @Test
    @Order(8)
    void logicalOr() {
        Map<String, Object> vars = Map.of(
            "memory_count", 5100,
            "importance", 0.05
        );
        assertTrue(engine.evaluate("memory_count > 5000 || importance > 0.9", vars));
        assertFalse(engine.evaluate("memory_count < 1000 || importance > 0.9", vars));
    }

    @Test
    @Order(9)
    void logicalNot() {
        Map<String, Object> vars = Map.of("active", 0.0);
        assertTrue(engine.evaluate("!active", vars));
        assertFalse(engine.evaluate("active", vars));
    }

    @Test
    @Order(10)
    void dottedVariablePath() {
        Map<String, Object> globals = Map.of("max_memory_size", 5000);
        Map<String, Object> vars = Map.of(
            "globals", globals,
            "memory_count", 5100
        );
        assertTrue(engine.evaluate("memory_count > globals.max_memory_size", vars));
    }

    @Test
    @Order(11)
    void emptyExpressionReturnsFalse() {
        assertFalse(engine.evaluate("", Map.of()));
        assertFalse(engine.evaluate("   ", Map.of()));
    }

    @Test
    @Order(12)
    void unknownVariableReturnsFalse() {
        assertFalse(engine.evaluate("nonexistent > 100", Map.of()));
    }

    @Test
    @Order(13)
    void complexExpression() {
        Map<String, Object> vars = Map.of(
            "memory_count", 6000,
            "importance", 0.8,
            "active", 1.0
        );
        // (count > 5000 && importance > 0.5) || !active
        assertTrue(engine.evaluate("memory_count > 5000 && importance > 0.5 || !active", vars));

        Map<String, Object> vars2 = Map.of(
            "memory_count", 100,
            "importance", 0.01,
            "active", 0.0
        );
        // !active should make it true
        assertTrue(engine.evaluate("memory_count > 5000 && importance > 0.5 || !active", vars2));
    }

    @Test
    @Order(14)
    void parenthesizedExpression() {
        Map<String, Object> vars = Map.of("a", 1.0, "b", 2.0, "c", 3.0);
        assertTrue(engine.evaluate("(a < b) && (b < c)", vars));
        assertFalse(engine.evaluate("(a > b) && (b < c)", vars));
    }
}
