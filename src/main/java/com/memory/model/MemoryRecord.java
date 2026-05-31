package com.memory.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MemoryRecord — 记忆存储的标准化数据模型。
 *
 * 替代之前所有手工 JSON 字符串拼装/解析（indexOf + substring），
 * 使用 Jackson 进行序列化/反序列化，保证嵌套字段、空格格式、转义字符的正确处理。
 *
 * JSON 结构：
 * <pre>{@code
 * {
 *   "_id": "uuid",
 *   "_type": "fact",
 *   "_importance": 1.0,
 *   "_created_at": 1234567890,
 *   "_last_accessed": 1234567890,
 *   "_tags": ["java", "lts"],
 *   "_data": { "content": "...", ... }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryRecord {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("_id")
    private String id;

    @JsonProperty("_type")
    private String type;

    @JsonProperty("_importance")
    private double importance = 1.0;

    @JsonProperty("_created_at")
    private long createdAt;

    @JsonProperty("_last_accessed")
    private long lastAccessed;

    @JsonProperty("_tags")
    private Set<String> tags = Collections.emptySet();

    @JsonProperty("_data")
    private JsonNode data;

    public MemoryRecord() {}

    /** 从用户数据 JSON 字符串创建新记录 */
    public static MemoryRecord create(String id, String typeKind, String userDataJson, Set<String> tags) {
        MemoryRecord record = new MemoryRecord();
        record.id = id;
        record.type = typeKind;
        record.importance = 1.0;
        record.createdAt = Instant.now().getEpochSecond();
        record.lastAccessed = record.createdAt;
        record.tags = tags != null ? new LinkedHashSet<>(tags) : Collections.emptySet();
        try {
            record.data = MAPPER.readTree(userDataJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid user data JSON: " + userDataJson, e);
        }
        return record;
    }

    /** 从已存储的 JSON 字符串反序列化 */
    public static MemoryRecord fromJson(String json) {
        try {
            return MAPPER.readValue(json, MemoryRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize MemoryRecord: " + e.getMessage(), e);
        }
    }

    /** 序列化为 JSON 字符串 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MemoryRecord", e);
        }
    }

    // ── 只读访问器 ──────────────────────────────────────────

    public String getId() { return id; }
    public String getType() { return type; }
    public double getImportance() { return importance; }
    public long getCreatedAt() { return createdAt; }
    public long getLastAccessed() { return lastAccessed; }
    public Set<String> getTags() { return tags != null ? tags : Collections.emptySet(); }

    /** 获取用户数据（_data 节点）的 JSON 字符串表示 */
    public String getDataAsString() {
        return data != null ? data.toString() : "{}";
    }

    /** 获取 _data 中指定字段的字符串值 */
    public String getDataField(String fieldName) {
        if (data == null) return null;
        JsonNode node = data.get(fieldName);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    /** 检查 _data 中指定字段的值是否与给定 JSON 字符串相同 */
    public boolean dataFieldEquals(String fieldName, String expectedJson) {
        if (data == null) return expectedJson == null;
        JsonNode node = data.get(fieldName);
        if (node == null) return expectedJson == null;
        return node.toString().equals(expectedJson);
    }

    // ── 可变操作（返回 this 支持链式调用）─────────────────

    /** 更新重要性 */
    public MemoryRecord setImportance(double importance) {
        this.importance = Math.min(1.0, Math.max(0, importance));
        return this;
    }

    /** 更新最后访问时间 */
    public MemoryRecord touch() {
        this.lastAccessed = Instant.now().getEpochSecond();
        return this;
    }

    /** 替换用户数据（_data），保留元数据 */
    public MemoryRecord replaceData(String newDataJson) {
        try {
            this.data = MAPPER.readTree(newDataJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON data: " + newDataJson, e);
        }
        return this;
    }

    /** 合并用户数据：保留元数据不变，仅替换 _data 字段 */
    public static MemoryRecord mergeWithMetadata(MemoryRecord existing, String newDataJson) {
        MemoryRecord merged = new MemoryRecord();
        merged.id = existing.id;
        merged.type = existing.type;
        merged.importance = existing.importance;
        merged.createdAt = existing.createdAt;
        merged.lastAccessed = Instant.now().getEpochSecond();
        merged.tags = existing.tags;
        try {
            merged.data = MAPPER.readTree(newDataJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON data: " + newDataJson, e);
        }
        return merged;
    }

    /**
     * 提取可搜索的文本内容（_data.content）。
     */
    public String extractSearchableText() {
        if (data == null) return "";
        JsonNode content = data.get("content");
        if (content != null && content.isTextual()) {
            String text = content.asText();
            if (tags != null && !tags.isEmpty()) {
                text += " " + String.join(" ", tags);
            }
            return text;
        }
        return data.toString();
    }

    // ── Object 方法 ─────────────────────────────────────────

    @Override
    public String toString() {
        return toJson();
    }
}
