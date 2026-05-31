package com.memory.agent.engine;

import com.memory.agent.spi.InformationExtractor;
import com.memory.model.MetaModel;
import com.memory.model.agent.AgentTypeHint;
import com.memory.model.type.MemoryType;
import com.memory.spi.SPI;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 模板信息提取器 — 默认轻量实现。
 *
 * 按类型的 field_hints 进行简单提取：
 * - 如果原始文本看起来像是 JSON，尝试按字段名提取
 * - 否则将全文作为 content 字段值
 * - 从文本中提取 #标签 作为 tags
 */
@SPI(name = "template", description = "模板/规则信息提取")
public class TemplateInfoExtractor implements InformationExtractor {

    @Override
    public String name() {
        return "template";
    }

    @Override
    public void init(Map<String, Object> params) {
        // 模板提取器无需额外初始化
    }

    @Override
    public ExtractedInfo extract(String rawText, String typeKind, MetaModel model) {
        Map<String, Object> fields = new HashMap<>();
        Set<String> tags = new LinkedHashSet<>();

        MemoryType type = model.getType(typeKind).orElse(null);

        // 尝试 JSON 解析
        if (rawText.trim().startsWith("{")) {
            extractFromJsonAttempt(rawText, fields, type);
        } else {
            // 全文作为 content
            fields.put("content", rawText.trim());
        }

        // 提取 #标签
        extractTags(rawText, tags);

        // 如果类型有 field_hints 但字段为空，填充默认值
        fillDefaults(fields, type);

        return new ExtractedInfo(fields, tags);
    }

    /**
     * 尝试将输入解析为 JSON 并提取字段。
     */
    private void extractFromJsonAttempt(String rawText, Map<String, Object> fields,
                                         MemoryType type) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(rawText, Map.class);
            fields.putAll(parsed);
        } catch (Exception e) {
            // 非合法 JSON，整体作为 content
            fields.put("content", rawText.trim());
        }
    }

    /**
     * 从文本中提取 #标签 格式的标签。
     */
    private void extractTags(String text, Set<String> tags) {
        int idx = 0;
        while (idx < text.length()) {
            int hash = text.indexOf('#', idx);
            if (hash < 0) break;
            int end = hash + 1;
            while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end))
                || text.charAt(end) == '_' || text.charAt(end) == '-')) {
                end++;
            }
            if (end > hash + 1) {
                tags.add(text.substring(hash + 1, end).toLowerCase());
            }
            idx = end;
        }
    }

    /**
     * 为缺失的必填字段填充默认值。
     * 遍历类型定义中所有 required=true 的字段，缺失时填入类型合适的默认值。
     */
    private void fillDefaults(Map<String, Object> fields, MemoryType type) {
        if (type == null || type.getFields() == null) return;

        for (Map.Entry<String, com.memory.model.constraint.FieldConstraint> entry
                : type.getFields().entrySet()) {
            String fieldName = entry.getKey();
            com.memory.model.constraint.FieldConstraint constraint = entry.getValue();

            if (constraint.isRequired() && !fields.containsKey(fieldName)) {
                fields.put(fieldName, defaultValueFor(constraint));
            }
        }
    }

    /** 根据字段类型生成合适的默认值 */
    private static Object defaultValueFor(com.memory.model.constraint.FieldConstraint c) {
        // 优先用 DSL 中声明的 default 值
        if (c.getDefaultValue() != null) {
            return c.getDefaultValue();
        }

        String type = c.getType() != null ? c.getType() : "string";
        return switch (type) {
            case "string" -> "";
            case "int" -> c.getMin() != null ? c.getMin() : 0;
            case "float" -> 0.0;
            default -> "";
        };
    }

}
