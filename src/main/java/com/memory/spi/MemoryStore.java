package com.memory.spi;

import com.memory.engine.store.JsonMemoryStore;

/**
 * 存储层 SPI 扩展点。
 *
 * 实现此接口可替换存储后端（如 JSON 文件 → SQLite → Redis）。
 * 组件通过 ServiceLoader 自动发现，由 Registry 按 DSL 声明装配。
 *
 * 方法数：6（接口隔离，不设计胖接口）
 */
@SPI(name = "memory-store", description = "存储层扩展点",
     defaultImpl = JsonMemoryStore.class)
public interface MemoryStore {

    /**
     * 存储组件标识，必须与 DSL 中 storage.engine 的值对应。
     * 如 "json", "sqlite"。
     */
    String name();

    /**
     * 初始化存储引擎（创建目录、连接池等）。
     * 由 Registry 在 assemble 阶段调用。
     */
    void init();

    /**
     * 保存记忆。
     * @param id   记忆 ID
     * @param data 记忆数据的 JSON 字符串
     */
    void save(String id, String data);

    /**
     * 加载记忆。
     * @param id 记忆 ID
     * @return 记忆数据的 JSON 字符串，不存在返回 null
     */
    String load(String id);

    /**
     * 删除记忆。
     * @param id 记忆 ID
     * @return true 如果删除成功，false 如果不存在
     */
    boolean delete(String id);

    /**
     * 列出所有已存储的记忆 ID。
     * @return 记忆 ID 集合
     */
    java.util.Set<String> listAll();
}
