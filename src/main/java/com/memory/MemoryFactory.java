package com.memory;

import com.memory.dsl.DSLParser;
import com.memory.model.MetaModel;

import java.nio.file.Path;

/**
 * MemoryClient 工厂类。
 * 提供统一的入口创建 MemoryClient 实例，外部调用只需一行代码。
 */
public final class MemoryFactory {

    private MemoryFactory() {
        // 防止实例化
    }

    /**
     * 从 DSL 文件创建 MemoryClient。
     *
     * @param dslPath DSL YAML 文件路径
     * @return 已启动的 MemoryClient 实例
     */
    public static MemoryClient create(Path dslPath) {
        DSLParser parser = new DSLParser();
        MetaModel model = parser.parse(dslPath);
        return new MemoryClient(model, dslPath);
    }

    /**
     * 从 YAML 字符串创建 MemoryClient。
     *
     * @param yaml DSL YAML 字符串内容
     * @return 已启动的 MemoryClient 实例
     */
    public static MemoryClient createFromString(String yaml) {
        DSLParser parser = new DSLParser();
        MetaModel model = parser.parseString(yaml);
        return new MemoryClient(model);
    }

    /**
     * 从预构建的 MetaModel 创建 MemoryClient。
     *
     * @param model 已解析的 MetaModel 实例
     * @return 已启动的 MemoryClient 实例
     */
    public static MemoryClient create(MetaModel model) {
        return new MemoryClient(model);
    }
}
