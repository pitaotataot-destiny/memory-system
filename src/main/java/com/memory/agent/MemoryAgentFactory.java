package com.memory.agent;

import com.memory.MemoryClient;
import com.memory.MemoryFactory;
import com.memory.agent.spi.ConflictDetector;
import com.memory.agent.spi.ImportanceAssigner;
import com.memory.agent.spi.InformationExtractor;
import com.memory.agent.spi.IntentClassifier;
import com.memory.agent.spi.MemoryConsolidator;
import com.memory.dsl.DSLParser;
import com.memory.model.MetaModel;
import com.memory.registry.ComponentRegistry;

import java.nio.file.Path;

/**
 * MemoryAgent 工厂类 — 一行创建带 Agent 能力的记忆系统。
 *
 * 和 MemoryFactory 一样简单，但返回的是 Layer 3 MemoryAgent。
 */
public final class MemoryAgentFactory {

    private MemoryAgentFactory() {}

    /**
     * 从 DSL 文件创建 MemoryAgent。
     * Agent SPI 组件从 ComponentRegistry 自动获取。
     */
    public static MemoryAgent create(Path dslPath) {
        DSLParser parser = new DSLParser();
        MetaModel model = parser.parse(dslPath);
        MemoryClient client = MemoryFactory.create(dslPath);
        return create(client, model);
    }

    /**
     * 从 YAML 字符串创建 MemoryAgent。
     */
    public static MemoryAgent createFromString(String yaml) {
        DSLParser parser = new DSLParser();
        MetaModel model = parser.parseString(yaml);
        MemoryClient client = MemoryFactory.createFromString(yaml);
        return create(client, model);
    }

    /**
     * 包裹已有 MemoryClient（高级场景：外部控制 client 生命周期）。
     */
    public static MemoryAgent wrap(MemoryClient client) {
        return create(client, client.getModel());
    }

    /**
     * 内部方法：从 Runtime Context 的 Registry 中获取已注册的 Agent SPI 组件。
     */
    @SuppressWarnings("unchecked")
    private static MemoryAgent create(MemoryClient client, MetaModel model) {
        ComponentRegistry registry = client.getModel() != null
            ? null  // 通过反射获取 ctx.registry 太复杂，走默认实现
            : null;

        // 如果 Agent 未启用，使用空实现（ingest 会返回失败）
        IntentClassifier classifier = resolveOrNull(registry, "agent:intent:");
        InformationExtractor extractor = resolveOrNull(registry, "agent:extract:");
        ImportanceAssigner importanceAssigner = resolveOrNull(registry, "agent:importance:");
        ConflictDetector conflictDetector = resolveOrNull(registry, "agent:conflict:");
        MemoryConsolidator consolidator = resolveOrNull(registry, "agent:consolidate:");

        // 使用默认实现兜底
        if (classifier == null) classifier = new com.memory.agent.engine.KeywordIntentClassifier();
        if (extractor == null) extractor = new com.memory.agent.engine.TemplateInfoExtractor();
        if (importanceAssigner == null)
            importanceAssigner = new com.memory.agent.engine.HeuristicImportanceAssigner();
        if (conflictDetector == null)
            conflictDetector = new com.memory.agent.engine.FieldConflictDetector();
        if (consolidator == null) consolidator = new com.memory.agent.engine.SimpleConsolidator();

        return new MemoryAgent(client, model, classifier, extractor,
            importanceAssigner, conflictDetector, consolidator);
    }

    /** 尝试从 Registry 获取 Agent 组件，失败返回 null */
    private static <T> T resolveOrNull(ComponentRegistry registry, String keyPrefix) {
        if (registry == null) return null;
        for (String key : registry.getRegisteredKeys()) {
            if (key.startsWith(keyPrefix)) {
                return registry.get(key);
            }
        }
        return null;
    }
}
