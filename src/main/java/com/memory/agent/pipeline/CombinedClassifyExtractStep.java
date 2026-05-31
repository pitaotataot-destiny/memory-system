package com.memory.agent.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.model.MetaModel;
import com.memory.model.agent.AgentTypeHint;
import com.memory.model.type.MemoryType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合并分类+提取步骤 — 一次 LLM 调用同时完成类型判断和信息提取。
 *
 * 当 DSL 中 intent.engine: llm 且 extraction.engine: llm 时，
 * 用此步骤替换 IntentClassificationStep + InformationExtractionStep，
 * 将 LLM 调用从 2 次降为 1 次。
 */
class CombinedClassifyExtractStep implements PipelineStep {

    private static final Logger LOG = LoggerFactory.getLogger(CombinedClassifyExtractStep.class);

    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_MAX_TOKENS = 512;
    private static final int HTTP_OK = 200;
    private static final double DEFAULT_CONFIDENCE = 0.7;
    private static final double FALLBACK_CONFIDENCE = 0.3;

    private final HttpClient httpClient;
    private final String endpoint;
    private final String llmModel;
    private final String apiKey;
    private final int timeoutMs;
    private final MetaModel metaModel;
    private final ObjectMapper mapper = new ObjectMapper();

    // 无 API key 时的降级
    private final PipelineStep fallbackClassify;
    private final PipelineStep fallbackExtract;

    CombinedClassifyExtractStep(MetaModel model,
                                 PipelineStep fallbackClassify,
                                 PipelineStep fallbackExtract) {
        this.metaModel = model;
        this.fallbackClassify = fallbackClassify;
        this.fallbackExtract = fallbackExtract;

        var intentCfg = metaModel.getAgent() != null ? metaModel.getAgent().getIntent() : null;
        var extractCfg = metaModel.getAgent() != null ? metaModel.getAgent().getExtraction() : null;

        Map<String, Object> params = intentCfg != null ? intentCfg.getParams() : Map.of();
        // extraction 的 params 优先（可能包含更具体的配置）
        if (extractCfg != null && extractCfg.getParams() != null) {
            params = extractCfg.getParams();
        }

        this.endpoint = (String) params.getOrDefault("endpoint",
            "https://api.openai.com/v1/chat/completions");
        this.llmModel = (String) params.getOrDefault("model", DEFAULT_MODEL);
        this.timeoutMs = asInt(params.get("timeout_ms"), DEFAULT_TIMEOUT_MS);

        // 读取密钥（先放局部变量，最后一次性赋给 final 字段）
        String key = null;
        String directKey = (String) params.get("api_key");
        String keyEnv = (String) params.getOrDefault("api_key_env", "OPENAI_API_KEY");
        if (directKey != null && !directKey.isBlank()) {
            key = directKey;
        } else {
            key = System.getenv(keyEnv);
            if (key == null) {
                key = System.getProperty(keyEnv);
            }
        }
        this.apiKey = key;

        if (this.apiKey == null || this.apiKey.isBlank()) {
            LOG.warn("CombinedClassifyExtract: no API key, using fallback");
        }

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build();
    }

    @Override
    public String name() { return "combined-classify-extract"; }

    @Override
    public void execute(PipelineContext ctx) {
        if (apiKey == null || apiKey.isBlank()) {
            fallbackClassify.execute(ctx);
            fallbackExtract.execute(ctx);
            return;
        }

        try {
            String prompt = buildPrompt(ctx.getRawText());
            String response = callLlm(prompt);
            parseResponse(response, ctx);
        } catch (Exception e) {
            LOG.error("Combined LLM call failed, falling back: {}", e.getMessage());
            fallbackClassify.execute(ctx);
            fallbackExtract.execute(ctx);
        }
    }

    // ── 提示词 ──────────────────────────────────────────────

    private String buildPrompt(String rawText) {
        StringBuilder typesDesc = new StringBuilder();
        for (Map.Entry<String, MemoryType> entry : metaModel.getTypes().entrySet()) {
            MemoryType type = entry.getValue();
            AgentTypeHint hint = type.getAgentHint();
            typesDesc.append("  \"").append(entry.getKey()).append("\": ")
                .append(hint != null && hint.getPrompt() != null ? hint.getPrompt() : "")
                .append("\n");
            if (hint != null && hint.getFieldHints() != null) {
                hint.getFieldHints().forEach((f, desc) ->
                    typesDesc.append("    字段 ").append(f).append(": ").append(desc).append("\n"));
            }
            if (type.getFields() != null) {
                type.getFields().forEach((f, fc) -> {
                    if (hint == null || hint.getFieldHints() == null
                        || !hint.getFieldHints().containsKey(f)) {
                        typesDesc.append("    字段 ").append(f)
                            .append(" [").append(fc.isRequired() ? "必填" : "可选")
                            .append("]\n");
                    }
                });
            }
        }

        return """
            你是记忆分析器。分析用户输入，同时完成分类和信息提取。

            可用类型：
            %s

            返回 JSON（只返回 JSON，不要其他内容）：
            {
              "typeKind": "类型名",
              "confidence": 0-1,
              "fields": { "字段名": "值", ... },
              "tags": ["标签1", "标签2", "..."]
            }

            输入文本: "%s"
            """.formatted(typesDesc.toString(),
                rawText.replace("\\", "\\\\").replace("\"", "\\\""));
    }

    // ── LLM 调用 ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String callLlm(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llmModel);
        body.put("max_tokens", DEFAULT_MAX_TOKENS);
        body.put("temperature", 0.0);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));
        body.put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != HTTP_OK) {
            throw new RuntimeException("LLM API " + resp.statusCode());
        }

        Map<String, Object> full = mapper.readValue(resp.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) full.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("No choices");
        return (String) choices.get(0).get("message");
    }

    // ── 解析 ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void parseResponse(String content, PipelineContext ctx) throws Exception {
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        Map<String, Object> result = mapper.readValue(content, Map.class);

        String typeKind = (String) result.get("typeKind");
        double confidence = asDouble(result.get("confidence"), DEFAULT_CONFIDENCE);
        Map<String, Object> fields = (Map<String, Object>) result.getOrDefault("fields",
            new LinkedHashMap<>());
        List<String> tagList = (List<String>) result.getOrDefault("tags", List.of());
        Set<String> tags = new LinkedHashSet<>(tagList);
        if (typeKind != null && !typeKind.isEmpty()) {
            tags.add(typeKind);
        }

        // 校验类型名
        if (typeKind == null || !metaModel.getTypes().containsKey(typeKind)) {
            typeKind = metaModel.getAgent().getIntent().getFallbackType();
            confidence = FALLBACK_CONFIDENCE;
        }

        ctx.setTypeKind(typeKind);
        ctx.setConfidence(confidence);
        ctx.setExtractedFields(fields);
        ctx.setExtractedTags(tags);
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) return Integer.parseInt(v.toString());
        return def;
    }

    private static double asDouble(Object v, double def) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v != null) return Double.parseDouble(v.toString());
        return def;
    }
}
