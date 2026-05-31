package com.memory.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.agent.spi.IntentClassifier;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 意图分类器 — 调用 OpenAI 兼容 API 进行语义分类。
 *
 * 配置示例（memory_dsl.yaml）：
 * <pre>{@code
 * agent:
 *   intent:
 *     engine: "llm"
 *     params:
 *       endpoint: "https://api.openai.com/v1/chat/completions"
 *       model: "gpt-4o-mini"
 *       api_key_env: "OPENAI_API_KEY"
 *       timeout_ms: 10000
 *       max_tokens: 200
 * }</pre>
 *
 * 密钥读取优先级：params.api_key → 环境变量 api_key_env → 系统属性 api_key_env
 * 这样保证密钥不出现在 YAML 中。
 */
@SPI(name = "llm", description = "LLM 语义意图分类（OpenAI 兼容 API）")
public class LlmIntentClassifier implements IntentClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(LlmIntentClassifier.class);

    // 默认配置
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_MAX_TOKENS = 256;
    private static final int HTTP_OK = 200;
    private static final double PARSE_FALLBACK_CONFIDENCE = 0.5;
    private static final double PARSE_FALLBACK_CONFIDENCE_LOW = 0.3;

    private HttpClient httpClient;
    private String endpoint;
    private String model;
    private String apiKey;
    private int timeoutMs;
    private int maxTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    // 兜底分类器（LLM 不可用时使用）
    private KeywordIntentClassifier fallback;

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public void init(Map<String, Object> params) {
        String rawEndpoint = (String) params.getOrDefault("endpoint",
            "https://api.openai.com/v1");
        this.endpoint = rawEndpoint.endsWith("/chat/completions")
            ? rawEndpoint : rawEndpoint.replaceAll("/+$", "") + "/chat/completions";
        this.model = (String) params.getOrDefault("model", DEFAULT_MODEL);
        this.timeoutMs = asInt(params.get("timeout_ms"), DEFAULT_TIMEOUT_MS);
        this.maxTokens = asInt(params.get("max_tokens"), DEFAULT_MAX_TOKENS);

        // 密钥：优先取直接配置，其次取环境变量/系统属性
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
            LOG.warn("LLM IntentClassifier: no API key. "
                + "Tried env={} and -D{}. Set OPENAI_API_KEY env var "
                + "or add -DOPENAI_API_KEY=sk-xxx to JVM args.", keyEnv, keyEnv);
        } else {
            LOG.info("LLM IntentClassifier: API key loaded ({}...{})",
                this.apiKey.substring(0, Math.min(7, this.apiKey.length())),
                this.apiKey.substring(Math.max(0, this.apiKey.length() - 4)));
        }

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build();
        this.fallback = new KeywordIntentClassifier();
        fallback.init(Map.of());
    }

    @Override
    public ClassifyResult classify(String rawText, MetaModel model) {
        if (apiKey == null || apiKey.isBlank()) {
            return fallback.classify(rawText, model);
        }

        try {
            String prompt = buildClassificationPrompt(rawText, model);
            String response = callLlm(prompt);
            return parseResponse(response, model);
        } catch (Exception e) {
            LOG.error("LLM classification failed, falling back to keyword: {}", e.getMessage());
            return fallback.classify(rawText, model);
        }
    }

    @Override
    public List<ClassifyResult> classifyTopN(String rawText, MetaModel model, int n) {
        // LLM 模式下 topN 就是单结果 + 其他类型按关键词得分补充
        ClassifyResult best = classify(rawText, model);
        List<ClassifyResult> results = new ArrayList<>();
        results.add(best);

        // 补充其他类型（用关键词得分作为参考）
        List<ClassifyResult> keywordResults = fallback.classifyTopN(rawText, model, n);
        for (ClassifyResult kr : keywordResults) {
            if (!kr.typeKind().equals(best.typeKind())) {
                results.add(kr);
            }
        }
        if (results.size() > n) {
            results = results.subList(0, n);
        }
        results.sort(Comparator.<ClassifyResult>comparingDouble(ClassifyResult::confidence).reversed());
        return results;
    }

    // ── LLM 调用 ────────────────────────────────────────────

    /**
     * 构建分类提示词。
     */
    private String buildClassificationPrompt(String rawText, MetaModel model) {
        StringBuilder typeDesc = new StringBuilder();
        for (Map.Entry<String, MemoryType> entry : model.getTypes().entrySet()) {
            MemoryType type = entry.getValue();
            typeDesc.append("  - \"").append(entry.getKey()).append("\": ")
                .append(type.getDescription() != null ? type.getDescription() : "");

            AgentTypeHint hint = type.getAgentHint();
            if (hint != null && hint.getPrompt() != null) {
                typeDesc.append(" (").append(hint.getPrompt()).append(")");
            }

            if (hint != null && !hint.getExamples().isEmpty()) {
                typeDesc.append("\n    示例: ");
                int count = 0;
                for (String ex : hint.getExamples()) {
                    if (count >= 3) break;
                    typeDesc.append("\"").append(ex).append("\"; ");
                    count++;
                }
            }
            typeDesc.append("\n");
        }

        return """
            你是一个记忆分类器。分析用户输入，判断它属于哪种记忆类型。

            可用类型：
            %s

            请判断以下文本的类型，返回 JSON：
            {"typeKind": "<类型名>", "confidence": <0-1>}

            只返回 JSON，不要其他内容。

            输入文本: "%s"
            """.formatted(typeDesc.toString(), rawText.replace("\"", "\\\""));
    }

    /**
     * 调用 LLM API。
     */
    private String callLlm(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.0);  // 分类任务用低温度

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

    /**
     * 解析 LLM 返回的 JSON，提取 typeKind 和 confidence。
     */
    @SuppressWarnings("unchecked")
    private ClassifyResult parseResponse(String responseBody, MetaModel model) throws Exception {
        Map<String, Object> fullResponse = mapper.readValue(responseBody, Map.class);

        // OpenAI 格式：{ choices: [{ message: { content: "..." } }] }
        List<Map<String, Object>> choices = (List<Map<String, Object>>) fullResponse.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM response has no choices");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("LLM response has no message");
        }

        String content = (String) message.get("content");
        // 去掉可能的 markdown 代码块包裹
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        Map<String, Object> result = mapper.readValue(content, Map.class);
        String typeKind = (String) result.get("typeKind");
        double confidence = asDouble(result.get("confidence"), PARSE_FALLBACK_CONFIDENCE);

        // 校验类型名是否有效
        if (typeKind == null || !model.getTypes().containsKey(typeKind)) {
            typeKind = model.getAgent() != null
                ? model.getAgent().getIntent().getFallbackType() : "fact";
            confidence = PARSE_FALLBACK_CONFIDENCE_LOW;
        }

        return new ClassifyResult(typeKind, Math.min(1.0, Math.max(0, confidence)));
    }

    // ── 辅助方法 ────────────────────────────────────────────

    private static int asInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static double asDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }
}
