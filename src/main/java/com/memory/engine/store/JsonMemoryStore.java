package com.memory.engine.store;

import com.memory.spi.MemoryStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * JSON 文件存储 — 将每条记忆持久化为独立的 .json 文件。
 * 实现 MemoryStore SPI 接口，由 Registry 按 DSL 声明装配。
 *
 * 存储结构：
 *   {dataDir}/
 *     mem-1.json
 *     mem-2.json
 *     ...
 */
public class JsonMemoryStore implements MemoryStore {

    // 默认数据目录
    private static final String DEFAULT_DATA_DIR = "./data/memory";

    // 内存索引（ID → 文件名），避免每次 listAll 都扫描磁盘
    private final Set<String> memoryIds = ConcurrentHashMap.newKeySet();

    private Path dataDir;

    @Override
    public String name() {
        return "json";
    }

    @Override
    public void init() {
        String dirPath = System.getProperty("memory.store.path", DEFAULT_DATA_DIR);
        this.dataDir = Path.of(dirPath);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data directory: " + dataDir, e);
        }
        // 加载已有文件到内存索引
        try (Stream<Path> files = Files.list(dataDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                .map(p -> p.getFileName().toString())
                .map(f -> f.substring(0, f.length() - 5)) // 去掉 .json 后缀
                .forEach(memoryIds::add);
        } catch (IOException ignored) {
            // 目录为空或首次启动
        }
    }

    @Override
    public void save(String id, String data) {
        Path file = dataDir.resolve(id + ".json");
        try {
            Files.writeString(file, data, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            memoryIds.add(id);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save memory: " + id, e);
        }
    }

    @Override
    public String load(String id) {
        // 先从内存索引检查，避免不必要的磁盘 IO
        if (!memoryIds.contains(id)) {
            return null;
        }
        Path file = dataDir.resolve(id + ".json");
        try {
            if (!Files.exists(file)) {
                memoryIds.remove(id);
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load memory: " + id, e);
        }
    }

    @Override
    public boolean delete(String id) {
        Path file = dataDir.resolve(id + ".json");
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                memoryIds.remove(id);
            }
            return deleted;
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete memory: " + id, e);
        }
    }

    @Override
    public Set<String> listAll() {
        return Collections.unmodifiableSet(memoryIds);
    }
}
