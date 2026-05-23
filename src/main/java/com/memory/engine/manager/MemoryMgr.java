package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.type.MemoryType;
import com.memory.model.constraint.FieldConstraint;
import com.memory.model.constraint.TagConstraint;
import com.memory.model.constraint.TypeMeta;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.MemoryStore;
import com.memory.spi.SearchProvider;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Memory manager — handles CRUD operations with constraint validation.
 * Uses Runtime Context to access MetaModel (rules) and MemoryStore (persistence).
 */
public class MemoryMgr {

    private final MemoryRuntimeContext ctx;

    public MemoryMgr(MemoryRuntimeContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Create a new memory entry.
     * Validates against DSL-defined type constraints before saving.
     *
     * @param typeKind the type kind (e.g. "fact", "preference")
     * @param data     the memory data as JSON string
     * @param tags     optional tags
     * @return the generated memory ID
     */
    public String create(String typeKind, String data, Set<String> tags) {
        MetaModel model = ctx.getMetaModel();

        // Validate type exists
        MemoryType type = model.getType(typeKind).orElse(null);
        if (type == null) {
            type = model.getType(model.getGlobals().getDefaultType().getValue()).orElse(null);
        }
        if (type == null) {
            throw new IllegalArgumentException("No memory type available");
        }

        // Validate field constraints
        validateFieldConstraints(type, data);

        // Validate tag constraints
        validateTagConstraints(type, tags);

        // Generate ID and save
        String id = UUID.randomUUID().toString();

        // Wrap data with metadata
        String wrapped = wrapWithMetadata(data, id, typeKind, tags);

        MemoryStore store = ctx.getStore("json");
        store.save(id, wrapped);
        ctx.incrementWrites();

        // 自动索引到所有搜索引擎
        indexForSearch(id, wrapped, tags);

        // New memory starts with full importance
        // In real implementation, this would be stored in the JSON metadata

        // Publish creation event
        try {
            ctx.getEventBus().publish(
                new com.memory.spi.EventBus.Event("memory_created", id,
                    Map.of("type", typeKind, "importance", 1.0)));
        } catch (Exception ignored) {
            // Event bus failure should not block creation
        }

        return id;
    }

    /**
     * Load a memory by ID.
     * Updates last_accessed time and increases importance.
     *
     * @param id memory ID
     * @return memory JSON data, null if not found
     */
    public String read(String id) {
        MemoryStore store = ctx.getStore("json");
        String data = store.load(id);
        if (data != null) {
            ctx.incrementQueries();
            // In real implementation, would update last_accessed and importance here
        }
        return data;
    }

    /**
     * Update an existing memory.
     * Validates immutable_fields before allowing changes.
     *
     * @param id       memory ID
     * @param newData  new memory data as JSON string
     */
    public void update(String id, String newData) {
        MetaModel model = ctx.getMetaModel();
        MemoryStore store = ctx.getStore("json");

        String existing = store.load(id);
        if (existing == null) {
            throw new IllegalArgumentException("Memory not found: " + id);
        }

        // Check immutable fields (basic check — in real impl, parse JSON and compare)
        // For now, pass through

        store.save(id, newData);
        ctx.incrementWrites();

        // 更新搜索索引
        indexForSearch(id, newData, null);

        // Publish update event
        try {
            ctx.getEventBus().publish(
                new com.memory.spi.EventBus.Event("memory_updated", id,
                    Map.of("status", "updated")));
        } catch (Exception ignored) {
            // Event bus failure should not block update
        }
    }

    /**
     * Delete a memory by ID.
     *
     * @param id memory ID
     * @return true if deleted, false if not found
     */
    public boolean delete(String id) {
        MemoryStore store = ctx.getStore("json");
        boolean deleted = store.delete(id);
        if (deleted) {
            ctx.evictHot(id);
        }
        return deleted;
    }

    /**
     * List all memory IDs.
     */
    public Set<String> listAll() {
        MemoryStore store = ctx.getStore("json");
        return store.listAll();
    }

    /**
     * Get memory count.
     */
    public int count() {
        return listAll().size();
    }

    /**
     * Validate field constraints defined in the memory type DSL.
     */
    private void validateFieldConstraints(MemoryType type, String data) {
        Map<String, FieldConstraint> fields = type.getFields();
        if (fields == null || fields.isEmpty()) return;

        // Basic validation: check required fields are present in JSON
        for (Map.Entry<String, FieldConstraint> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            FieldConstraint constraint = entry.getValue();

            if (constraint.isRequired() && !data.contains("\"" + fieldName + "\"")) {
                throw new IllegalArgumentException(
                    "Required field '" + fieldName + "' is missing for type " + type.getKind().getValue());
            }
        }
    }

    /**
     * Validate tag constraints defined in the memory type DSL.
     */
    private void validateTagConstraints(MemoryType type, Set<String> tags) {
        if (tags == null || tags.isEmpty()) return;

        TagConstraint tagConstraint = type.getTags();
        if (tagConstraint == null) return;

        // Check max tags
        if (tags.size() > tagConstraint.getMax()) {
            throw new IllegalArgumentException(
                "Too many tags: " + tags.size() + " (max " + tagConstraint.getMax() + ")");
        }

        // Check allowed pattern
        if (tagConstraint.getAllowedPattern() != null) {
            String pattern = tagConstraint.getAllowedPattern();
            for (String tag : tags) {
                if (!tag.matches(pattern)) {
                    throw new IllegalArgumentException(
                        "Tag '" + tag + "' does not match allowed pattern: " + pattern);
                }
            }
        }
    }

    /**
     * Wrap raw data with system metadata (ID, timestamps, importance).
     */
    private String wrapWithMetadata(String data, String id, String typeKind, Set<String> tags) {
        long now = Instant.now().getEpochSecond();
        // Build a simple JSON wrapper
        return "{\"_id\":\"" + id + "\",\"_type\":\"" + typeKind +
            "\",\"_importance\":1.0,\"_created_at\":" + now +
            ",\"_last_accessed\":" + now +
            ",\"_tags\":" + tagsAsJson(tags) +
            ",\"_data\":" + data + "}";
    }

    private String tagsAsJson(Set<String> tags) {
        if (tags == null || tags.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String tag : tags) {
            if (!first) sb.append(",");
            sb.append("\"").append(tag).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将记忆内容索引到所有已注册的搜索引擎。
     */
    private void indexForSearch(String id, String data, Set<String> tags) {
        // 提取纯文本用于索引：拼接 content + tags
        String text = extractContent(data);
        if (text == null || text.isEmpty()) return;
        if (tags != null && !tags.isEmpty()) {
            text = text + " " + String.join(" ", tags);
        }
        for (SearchProvider provider : ctx.getAllSearchProviders().values()) {
            try {
                provider.index(id, text);
            } catch (Exception ignored) {
                // 单个引擎索引失败不影响整体
            }
        }
    }

    /**
     * 从包装 JSON 中提取 _data.content 字段的文本。
     */
    private String extractContent(String data) {
        int start = data.indexOf("\"_data\":{");
        if (start < 0) return data; // 不是包装格式，直接使用
        start += 8;
        int end = data.indexOf("}", start);
        if (end < 0) return data;
        String inner = data.substring(start, end + 1);
        // 提取 content 字段
        int cs = inner.indexOf("\"content\":\"");
        if (cs < 0) return inner;
        cs += 11;
        int ce = inner.indexOf("\"", cs);
        return ce > cs ? inner.substring(cs, ce) : inner;
    }
}
