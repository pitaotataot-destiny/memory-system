package com.memory.model.type;

import com.memory.model.enums.MemoryTypeKind;
import com.memory.model.constraint.FieldConstraint;
import com.memory.model.constraint.TagConstraint;
import com.memory.model.constraint.TypeMeta;

import java.util.Collections;
import java.util.Map;

/**
 * 记忆类型 Meta Model
 */
public class MemoryType {
    private MemoryTypeKind kind;
    private String description;
    private Map<String, FieldConstraint> fields = Collections.emptyMap();
    private TagConstraint tags = new TagConstraint();
    private TypeMeta meta = new TypeMeta();

    public MemoryType() {}

    public MemoryTypeKind getKind() { return kind; }
    public void setKind(MemoryTypeKind kind) { this.kind = kind; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, FieldConstraint> getFields() { return fields; }
    public void setFields(Map<String, FieldConstraint> fields) { this.fields = fields; }

    public TagConstraint getTags() { return tags; }
    public void setTags(TagConstraint tags) { this.tags = tags; }

    public TypeMeta getMeta() { return meta; }
    public void setMeta(TypeMeta meta) { this.meta = meta; }
}
