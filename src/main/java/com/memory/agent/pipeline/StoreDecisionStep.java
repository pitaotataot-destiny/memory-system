package com.memory.agent.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.MemoryClient;
import com.memory.model.MetaModel;

import java.util.Map;

/**
 * 同步步骤 4：存储决策——将提取的字段转为 JSON 并写入 MemoryClient。
 */
class StoreDecisionStep implements PipelineStep {

    private final MemoryClient client;
    private final MetaModel model;
    private final ObjectMapper mapper = new ObjectMapper();

    StoreDecisionStep(MemoryClient client, MetaModel model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public String name() { return "store"; }

    @Override
    public void execute(PipelineContext ctx) {
        Map<String, Object> fields = ctx.getExtractedFields();
        String json;
        try {
            json = mapper.writeValueAsString(fields);
        } catch (Exception e) {
            json = "{\"content\":\"" + escapeJson(ctx.getRawText()) + "\"}";
        }

        String id = client.create(ctx.getTypeKind(), json, ctx.getExtractedTags());
        ctx.setMemoryId(id);
    }

    /** 简易 JSON 字符串转义 */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
