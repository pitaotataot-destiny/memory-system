package com.memory.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.agent.spi.InformationExtractor;
import com.memory.model.MetaModel;
import com.memory.model.agent.AgentTypeHint;
import com.memory.model.type.MemoryType;
import com.memory.spi.SPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM 信息提取器 — 调用 OpenAI 兼容 API 从原始文本中提取字段和标签。
 *
 * 配置示例：
 * <pre>{@code
 * agent:
 *   extraction:
 *     engine: "llm"
 *     params:
 *       endpoint: "https://api.openai.com/v1/chat/completions"
 *       model: "gpt-4o-mini"
 *       api_key_env: "OPENAI_API_KEY"
 * }</pre>
 *
 * 密钥读取与 LlmIntentClassifier 共享环境变量 KEY。
 * 不可用时降级到 TemplateInfoExtractor。
 */
@SPI(name = "llm", description = "LLM 信息提取（OpenAI 兼容 API）")
public class LlmInfoExtractor implements InformationExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(LlmInfoExtractor.class);

    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_MAX_TOKENS = 512;
    private static final int HTTP_OK = 200;

    private HttpClient httpClient;
    private String endpoint;
    private String model;
    private String apiKey;
    private int timeoutMs;
    private int maxTokens;
    private final ObjectMapper mapper = new ObjectMapper();
    private TemplateInfoExtractor fallback;

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public void init(Map<String, Object> params) {
        this.endpoint = (String) params.getOrDefault("endpoint",
            "https://api.openai.com/v1/chat/completions");
        this.model = (String) params.getOrDefault("model", DEFAULT_MODEL);
        this.timeoutMs = asInt(params.get("timeout_ms"), DEFAULT_TIMEOUT_MS);
        this.maxTokens = asInt(params.get("max_tokens"), DEFAULT_MAX_TOKENS);

        // 密钥：与 LlmIntentClassifier 共享环境变量
        String directKey = (String) params.get("api_key");
        String keyEnv = (String) params.getOrDefault("api_key_env", "OPENAI_API_KEY");

        if (directKey != null && !directKey.isBlank()) {
            this.apiKey = directKey;
        } else {
            this.apiKey = System.getenv(keyEnv);
            if (this.apiKey == null) {
                this.apiKey = System.getProperty(keyEnv);
            }
        }

        if (this.apiKey == null || this.apiKey.isBlank()) {
            LOG.warn("LLM InfoExtractor: no API key. Tried env={}. "
                + "Set OPENAI_API_KEY env var or -DOPENAI_API_KEY=sk-xxx JVM arg.",
                keyEnv);
        } else {
            LOG.info("LLM InfoExtractor: API key loaded from {}",
                System.getenv(keyEnv) != null ? "env " + keyEnv : "system property");
        }

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build();
        this.fallback = new TemplateInfoExtractor();
        fallback.init(Map.of());
    }

    @Override
    public ExtractedInfo extract(String rawText, String typeKind, MetaModel model) {
        if (apiKey == null || apiKey.isBlank()) {
            return fallback.extract(rawText, typeKind, model);
        }

        try {
            String prompt = buildExtractionPrompt(rawText, typeKind, model);
            String response = callLlm(prompt);
            return parseResponse(response, typeKind);
        } catch (Exception e) {
            LOG.error("LLM extraction failed, falling back: {}", e.getMessage());
            return fallback.extract(rawText, typeKind, model);
        }
    }

    // ── LLM 调用 ────────────────────────────────────────────

    String buildExtractionPrompt(String rawText, String typeKind, MetaModel model) {
        MemoryType type = model.getType(typeKind).orElse(null);
        AgentTypeHint hint = type != null ? type.getAgentHint() : null;

        StringBuilder fieldDesc = new StringBuilder();
        if (type != null && type.getFields() != null) {
            type.getFields().forEach((name, fc) -> {
                String desc = "";
                if (hint != null && hint.getFieldHints() != null) {
                    desc = hint.getFieldHints().getOrDefault(name, "");
                }
                fieldDesc.append(String.format("    \"%s\": \"%s\"  // %s %s\n",
                    name, fc.getType(),
                    fc.isRequired() ? "[必填]" : "[可选]",
                    desc));
            });
        }

        String typeDesc = "";
        if (hint != null && hint.getPrompt() != null) {
            typeDesc = "（" + hint.getPrompt() + "）";
        }

        return """
            你是信息提取器。从用户输入中提取结构化字段和标签。

            目标类型: "%s" %s
            字段定义:
            %s

            请提取并返回 JSON：
            {
              "fields": { ... },   // 字段名→值
              "tags": [...]        // 关键词标签列表
            }

            只返回 JSON，不要其他内容。

            输入文本: "%s"
            """.formatted(typeKind, typeDesc, fieldDesc.toString(),
                rawText.replace("\\", "\\\\").replace("\"", "\\\""));
    }

    String callLlm(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.0);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", prompt));
        body.put("messages", messages);

        String json = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != HTTP_OK) {
            throw new RuntimeException("LLM API returned " + response.statusCode()
                + ": " + response.body());
        }
        return response.body();
    }

    @SuppressWarnings("unchecked")
    ExtractedInfo parseResponse(String responseBody, String typeKind) throws Exception {
        Map<String, Object> fullResponse = mapper.readValue(responseBody, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) fullResponse.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM response has no choices");
        }

        String content = (String) choices.get(0).get("message");
        // 去掉 markdown 代码块
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        Map<String, Object> result = mapper.readValue(content, Map.class);

        Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>)
            result.getOrDefault("fields", new HashMap<>()));

        List<String> tagList = (List<String>) result.getOrDefault("tags", List.of());
        Set<String> tags = new LinkedHashSet<>(tagList);
        tags.add(typeKind);  // 类型名始终作为标签

        return new ExtractedInfo(fields, tags);
    }

    private static int asInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}
