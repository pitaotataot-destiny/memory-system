package com.memory.engine.manager;

import com.memory.model.MetaModel;
import com.memory.model.constraint.FieldConstraint;
import com.memory.model.constraint.TagConstraint;
import com.memory.model.type.MemoryType;
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
 *
 * 存储引擎通过 MetaModel 配置获取，不再硬编码。
 */
public class MemoryMgr {

    private final MemoryRuntimeContext ctx;

    public MemoryMgr(MemoryRuntimeContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 获取配置的存储引擎名称（从 DSL globals.storage.engine 读取）。
     */
    private String getStoreName() {
        return ctx.getMetaModel().getGlobals().getStorage().getEngine().getValue();
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

        // 解析类型：优先用传入类型，fallback 到全局默认类型
        String resolvedKind = (typeKind != null && !typeKind.isBlank())
            ? typeKind : model.getGlobals().getDefaultType().getValue();

        // Validate type exists
        MemoryType type = model.getType(resolvedKind).orElse(null);
        if (type == null) {
            throw new IllegalArgumentException("Unknown memory type: " + resolvedKind);
        }

        // Validate field constraints
        validateFieldConstraints(type, data);

        // Validate tag constraints
        validateTagConstraints(type, tags);

        // Generate ID and save
        String id = UUID.randomUUID().toString();

        // Wrap data with metadata（使用解析后类型名，避免 _type 为 null）
        String wrapped = wrapWithMetadata(data, id, resolvedKind, tags);

        MemoryStore store = ctx.getStore(getStoreName());
        store.save(id, wrapped);
        ctx.incrementWrites();

        // 自动索引到所有搜索引擎
        indexForSearch(id, wrapped, tags);

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
     * Updates last_accessed time and increases importance via access_gain.
     *
     * @param id memory ID
     * @return memory JSON data, null if not found
     */
    public String read(String id) {
        MemoryStore store = ctx.getStore(getStoreName());
        String data = store.load(id);
        if (data != null) {
            ctx.incrementQueries();
            // 真正更新 last_accessed 和 importance
            String updated = applyAccessUpdate(data);
            store.save(id, updated);
            ctx.incrementWrites();
            return updated;
        }
        return null;
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
        MemoryStore store = ctx.getStore(getStoreName());

        String existing = store.load(id);
        if (existing == null) {
            throw new IllegalArgumentException("Memory not found: " + id);
        }

        // 校验不可变字段
        checkImmutableFields(model, existing, newData);

        // 保留元数据（_id, _type, _importance, _created_at, _tags），替换 _data
        String merged = mergeWithMetadata(existing, newData);
        store.save(id, merged);
        ctx.incrementWrites();

        // 更新搜索索引
        indexForSearch(id, merged, null);

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
        MemoryStore store = ctx.getStore(getStoreName());
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
        MemoryStore store = ctx.getStore(getStoreName());
        return store.listAll();
    }

    /**
     * Get memory count.
     */
    public int count() {
        return listAll().size();
    }

    // ── 辅助方法 ────────────────────────────────────────────

    /**
     * 校验不可变字段：比较新旧 JSON 中 _data 内的 immutable_fields，
     * 任何变化都会抛出 IllegalArgumentException。
     */
    private void checkImmutableFields(MetaModel model, String existing, String newData) {
        String typeKind = parseMetaField(existing, "_type");
        if (typeKind == null) return;

        MemoryType type = model.getType(typeKind).orElse(null);
        if (type == null || type.getMeta() == null) return;

        java.util.List<String> immutableFields = type.getMeta().getImmutableFields();
        if (immutableFields == null || immutableFields.isEmpty()) return;

        for (String field : immutableFields) {
            // 只比较 _data 内的用户字段，不比较系统元数据
            String oldVal = extractFieldFromData(existing, "\"" + field + "\"");
            String newVal = extractFieldFromData(newData, "\"" + field + "\"");
            if (oldVal == null && newVal == null) continue;
            if (oldVal == null || !oldVal.equals(newVal)) {
                throw new IllegalArgumentException(
                    "字段 '" + field + "' 不可修改（类型 " + typeKind + " 的 immutable_field）");
            }
        }
    }

    /**
     * 合并新旧数据：保留系统元数据，data 部分用新的。
     */
    private String mergeWithMetadata(String existing, String newData) {
        long now = Instant.now().getEpochSecond();
        String id = parseMetaField(existing, "_id");
        String type = parseMetaField(existing, "_type");
        String importance = parseMetaField(existing, "_importance");
        String createdAt = parseMetaField(existing, "_created_at");
        String tags = parseMetaField(existing, "_tags");
        // _tags 可能是 JSON 数组，需特殊处理
        String tagsJson = extractTagsJson(existing);

        return "{\"_id\":\"" + (id != null ? id : "") + "\",\"_type\":\"" + (type != null ? type : "") +
            "\",\"_importance\":" + (importance != null ? importance : "1.0") +
            ",\"_created_at\":" + (createdAt != null ? createdAt : String.valueOf(now)) +
            ",\"_last_accessed\":" + now +
            ",\"_tags\":" + (tagsJson != null ? tagsJson : "[]") +
            ",\"_data\":" + newData + "}";
    }

    /**
     * 应用访问增益：更新 last_accessed 时间戳并增加 importance。
     */
    private String applyAccessUpdate(String data) {
        long now = Instant.now().getEpochSecond();
        double accessGain = ctx.getMetaModel().getDecay().getDefaultConfig().getAccessGain();

        // 更新 _last_accessed
        String updated = replaceJsonField(data, "_last_accessed", String.valueOf(now));

        // 更新 _importance
        String oldImportance = parseMetaField(updated, "_importance");
        if (oldImportance != null) {
            try {
                double imp = Double.parseDouble(oldImportance);
                imp = Math.min(1.0, imp + accessGain);
                updated = replaceJsonField(updated, "_importance", String.valueOf(imp));
            } catch (NumberFormatException ignored) {
                // 无法解析则跳过
            }
        }
        return updated;
    }

    /**
     * 从包装 JSON 中提取指定元数据字段的值（字符串字段）。
     */
    private static String parseMetaField(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        // 跳过空格
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        // 读引号包裹的字符串值
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == '"') {
            int end = json.indexOf('"', idx + 1);
            if (end < 0) return null;
            return json.substring(idx + 1, end);
        }
        // 读数字/布尔值
        int end = idx;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(idx, end);
    }

    /**
     * 从 JSON 的 _data 部分提取字段值。
     */
    private static String extractFieldFromData(String json, String fieldKey) {
        // 在 _data:{...} 内部查找
        int dataStart = json.indexOf("\"_data\":{");
        if (dataStart < 0) return null;
        int searchStart = json.indexOf(fieldKey, dataStart);
        if (searchStart < 0) return null;
        searchStart += fieldKey.length();
        while (searchStart < json.length() && json.charAt(searchStart) == ' ') searchStart++;
        if (searchStart < json.length() && json.charAt(searchStart) == ':') {
            searchStart++;
            while (searchStart < json.length() && json.charAt(searchStart) == ' ') searchStart++;
        }
        if (searchStart >= json.length()) return null;
        if (json.charAt(searchStart) == '"') {
            int end = json.indexOf('"', searchStart + 1);
            if (end < 0) return null;
            return json.substring(searchStart, end + 1);
        }
        int end = searchStart;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(searchStart, end);
    }

    /**
     * 替换 JSON 中指定字段的值（原地替换，假设字段已存在）。
     */
    private static String replaceJsonField(String json, String field, String newValue) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return json;
        idx += key.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return json;

        // 找到旧值结束位置
        int end;
        if (json.charAt(idx) == '"') {
            end = json.indexOf('"', idx + 1);
            if (end < 0) return json;
            end++; // 包含右引号
        } else {
            end = idx;
            while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        }
        return json.substring(0, idx) + newValue + json.substring(end);
    }

    /**
     * 提取 _tags JSON 数组字符串（如 "[\"java\",\"lts\"]"）。
     */
    private static String extractTagsJson(String json) {
        String key = "\"_tags\":";
        int idx = json.indexOf(key);
        if (idx < 0) return "[]";
        idx += key.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length() || json.charAt(idx) != '[') return "[]";
        int depth = 0;
        int end = idx;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) { end++; break; } }
            end++;
        }
        return json.substring(idx, end);
    }

    /**
     * Validate field constraints defined in the memory type DSL.
     */
    private void validateFieldConstraints(MemoryType type, String data) {
        Map<String, FieldConstraint> fields = type.getFields();
        if (fields == null || fields.isEmpty()) return;

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

        if (tags.size() > tagConstraint.getMax()) {
            throw new IllegalArgumentException(
                "Too many tags: " + tags.size() + " (max " + tagConstraint.getMax() + ")");
        }

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
        if (start < 0) return data;
        start += 9;  // "\"_data\":{".length()
        int end = data.indexOf("}", start);
        if (end < 0) return data;
        String inner = data.substring(start, end + 1);
        int cs = inner.indexOf("\"content\":\"");
        if (cs < 0) return inner;
        cs += 10;  // "\"content\":\"".length()
        int ce = inner.indexOf("\"", cs);
        return ce > cs ? inner.substring(cs, ce) : inner;
    }
}
