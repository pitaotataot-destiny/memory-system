package com.memory.engine.manager;

import com.memory.model.MemoryRecord;
import com.memory.model.MetaModel;
import com.memory.model.constraint.FieldConstraint;
import com.memory.model.constraint.TagConstraint;
import com.memory.model.type.MemoryType;
import com.memory.runtime.MemoryRuntimeContext;
import com.memory.spi.MemoryStore;
import com.memory.spi.SearchProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Memory manager — handles CRUD operations with constraint validation.
 * JSON 序列化/反序列化全部委托给 {@link MemoryRecord}（Jackson），不再手工拼接字符串。
 */
public class MemoryMgr {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryMgr.class);

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

        // Validate field / tag constraints
        validateFieldConstraints(type, data);
        validateTagConstraints(type, tags);

        // 唯一性检查（unique_by）
        checkUniqueness(type, data, resolvedKind);

        // 构造 MemoryRecord（Jackson 序列化用户数据）
        String id = UUID.randomUUID().toString();
        MemoryRecord record = MemoryRecord.create(id, resolvedKind, data, tags);

        // 持久化
        MemoryStore store = ctx.getStore(getStoreName());
        store.save(id, record.toJson());
        ctx.incrementWrites();

        // 缓存类型（避免搜索过滤时逐条磁盘 IO）
        ctx.cacheType(id, resolvedKind);

        // 新记忆 importance=1.0 → 标记为热记忆
        ctx.markHot(id, 1.0);

        // 索引到所有搜索引擎
        indexForSearch(id, record);

        // 发布创建事件
        publishEvent("memory_created", id, Map.of("type", resolvedKind, "importance", 1.0));

        return id;
    }

    /**
     * Load a memory by ID.
     * 访问时间更新为内存操作（不再每读必写磁盘）。
     *
     * @param id memory ID
     * @return memory JSON data, null if not found
     */
    public String read(String id) {
        MemoryStore store = ctx.getStore(getStoreName());
        String raw = store.load(id);
        if (raw != null) {
            ctx.incrementQueries();
            // 内存中更新访问时间，避免磁盘写回
            ctx.touchAccess(id, Instant.now().getEpochSecond());
            // 返回原始 JSON 以保持 API 兼容
            return raw;
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
        MemoryStore store = ctx.getStore(getStoreName());

        String raw = store.load(id);
        if (raw == null) {
            throw new IllegalArgumentException("Memory not found: " + id);
        }

        MemoryRecord existing = MemoryRecord.fromJson(raw);

        // 校验不可变字段
        checkImmutableFields(existing, newData);

        // 合并：保留元数据，替换 _data
        MemoryRecord merged = MemoryRecord.mergeWithMetadata(existing, newData);
        store.save(id, merged.toJson());
        ctx.incrementWrites();

        // 更新搜索索引
        indexForSearch(id, merged);

        // 发布更新事件
        publishEvent("memory_updated", id, Map.of("status", "updated"));
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

    /** List all memory IDs. */
    public Set<String> listAll() {
        return ctx.getStore(getStoreName()).listAll();
    }

    /** Get memory count. */
    public int count() {
        return listAll().size();
    }

    // ── 辅助方法 ────────────────────────────────────────────

    /**
     * 校验不可变字段：用 MemoryRecord 比较新旧 _data 中的 immutable_fields。
     */
    private void checkImmutableFields(MemoryRecord existing, String newData) {
        String typeKind = existing.getType();
        if (typeKind == null) return;

        MemoryType type = ctx.getMetaModel().getType(typeKind).orElse(null);
        if (type == null || type.getMeta() == null) return;

        java.util.List<String> immutableFields = type.getMeta().getImmutableFields();
        if (immutableFields == null || immutableFields.isEmpty()) return;

        // 临时解析新数据以比较不可变字段
        MemoryRecord incoming;
        try {
            incoming = MemoryRecord.create("_", typeKind, newData, Set.of());
        } catch (IllegalArgumentException e) {
            return;  // 新数据 JSON 无效，跳过不可变检查
        }

        for (String field : immutableFields) {
            String oldVal = existing.getDataField(field);
            String newVal = incoming.getDataField(field);
            if (oldVal == null && newVal == null) continue;
            if (oldVal == null || !oldVal.equals(newVal)) {
                throw new IllegalArgumentException(
                    "字段 '" + field + "' 不可修改（类型 " + typeKind + " 的 immutable_field）");
            }
        }
    }

    /**
     * 校验必填字段约束。
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
     * 校验标签约束。
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
     * 唯一性校验：按 type.meta.unique_by 字段检查是否已有重复记忆。
     */
    private void checkUniqueness(MemoryType type, String newData, String resolvedKind) {
        java.util.List<String> uniqueBy = type.getMeta() != null
            ? type.getMeta().getUniqueBy() : null;
        if (uniqueBy == null || uniqueBy.isEmpty()) return;

        // 解析新数据的唯一字段值
        Map<String, String> newValues = new java.util.LinkedHashMap<>();
        for (String field : uniqueBy) {
            String val = extractFieldValue(newData, field);
            if (val != null) newValues.put(field, val);
        }
        if (newValues.isEmpty()) return;

        // 遍历已有记忆，检查冲突
        MemoryStore store = ctx.getStore(getStoreName());
        for (String existingId : store.listAll()) {
            String raw = store.load(existingId);
            if (raw == null) continue;

            // 只检查同类型
            String existingType = extractMetaField(raw, "_type");
            if (!resolvedKind.equals(existingType)) continue;

            boolean allMatch = true;
            for (Map.Entry<String, String> e : newValues.entrySet()) {
                String existingVal = extractDataFieldValue(raw, e.getKey());
                if (!e.getValue().equals(existingVal)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                throw new IllegalArgumentException(
                    "Duplicate memory: type=" + resolvedKind
                    + ", unique_by=" + uniqueBy
                    + ", existing=" + existingId);
            }
        }
    }

    /** 从 JSON 字符串中提取顶层字段的字符串值 */
    private static String extractFieldValue(String json, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == '"') {
            int end = json.indexOf('"', idx + 1);
            return end > idx ? json.substring(idx + 1, end) : null;
        }
        int end = idx;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(idx, end).trim();
    }

    /** 从包装 JSON 的 _data 部分提取字段值 */
    private static String extractDataFieldValue(String wrappedJson, String fieldName) {
        int dataStart = wrappedJson.indexOf("\"_data\":{");
        if (dataStart < 0) return null;
        return extractFieldValue(wrappedJson.substring(dataStart), fieldName);
    }

    /** 从包装 JSON 提取元数据字段（字符串） */
    private static String extractMetaField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();
        int end = json.indexOf('"', idx);
        return end > idx ? json.substring(idx, end) : null;
    }

    /**
     * 将记忆索引到所有已注册的搜索引擎。
     */
    private void indexForSearch(String id, MemoryRecord record) {
        String text = record.extractSearchableText();
        if (text == null || text.isEmpty()) return;

        for (SearchProvider provider : ctx.getAllSearchProviders().values()) {
            try {
                provider.index(id, text);
            } catch (Exception ignored) {
                // 单个引擎索引失败不影响整体
            }
        }
    }

    /** 发布事件到事件总线（失败不影响主流程）。 */
    private void publishEvent(String eventType, String memoryId, Map<String, Object> payload) {
        try {
            ctx.getEventBus().publish(
                new com.memory.spi.EventBus.Event(eventType, memoryId, payload));
        } catch (Exception ignored) {
            LOG.debug("Event publish failed for {} (non-critical)", eventType);
        }
    }
}
