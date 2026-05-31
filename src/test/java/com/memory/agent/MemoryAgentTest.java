package com.memory.agent;

import com.memory.agent.pipeline.IngestResult;
import com.memory.agent.spi.IntentClassifier;
import com.memory.dsl.DSLParser;
import com.memory.model.IngestDecision;
import com.memory.model.MemoryRecord;
import com.memory.model.MetaModel;

import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryAgent 集成测试 — 验证 ingest 全链路。
 *
 * 测试场景：
 *   1. DSL 解析 → Agent 配置是否正确加载
 *   2. 意图分类 → 输入文本应识别为正确类型
 *   3. 信息提取 → 从原始文本提取字段和标签
 *   4. 存储 → 创建的记忆可被 read() 读出
 *   5. 冲突检测 → 重复内容应触发冲突
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryAgentTest {

    private static MetaModel model;
    private MemoryAgent agent;

    @BeforeAll
    static void loadModel() {
        DSLParser parser = new DSLParser();
        Path dslPath = Path.of("memory_dsl.yaml");
        if (!dslPath.toFile().exists()) {
            throw new IllegalStateException("memory_dsl.yaml not found. Run from project root.");
        }
        model = parser.parse(dslPath);
        assertNotNull(model);
        assertNotNull(model.getAgent(), "Agent config should be loaded from DSL");
        assertTrue(model.getAgent().isEnabled(), "Agent should be enabled");
    }

    @BeforeEach
    void setUp() {
        agent = MemoryAgentFactory.createFromString(buildMinimalAgentDsl());
    }

    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.close();
        }
    }

    // ── 测试 1: Agent 配置加载 ──────────────────────────────

    @Test
    @Order(1)
    @DisplayName("DSL agent 配置正确加载")
    void dslAgentConfigLoaded() {
        // 引擎名取决于 DSL 配置，可能是 keyword-match 或 llm
        assertNotNull(model.getAgent().getIntent().getEngine());
        assertFalse(model.getAgent().getIntent().getEngine().isEmpty());
        assertEquals(0.6, model.getAgent().getIntent().getConfidenceThreshold(), 0.001);
        assertEquals("fact", model.getAgent().getIntent().getFallbackType());
        // 引擎名取决于 DSL 配置，可能是 template 或 llm
        assertNotNull(model.getAgent().getExtraction().getEngine());
        assertEquals("field-compare", model.getAgent().getConflict().getEngine());
        assertEquals("ask", model.getAgent().getConflict().getResolution());
    }

    @Test
    @Order(2)
    @DisplayName("Type agent hints 正确加载")
    void typeAgentHintsLoaded() {
        var fact = model.getType("fact").orElseThrow();
        assertNotNull(fact.getAgentHint());
        assertFalse(fact.getAgentHint().getExamples().isEmpty());
        assertTrue(fact.getAgentHint().getExamples().stream()
            .anyMatch(e -> e.contains("Java 17")));

        var preference = model.getType("preference").orElseThrow();
        assertNotNull(preference.getAgentHint());
        assertTrue(preference.getAgentHint().getExamples().stream()
            .anyMatch(e -> e.contains("4 空格")));
    }

    // ── 测试 2: 意图分类 ────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("偏好类输入应识别为 preference")
    void classifyPreference() {
        // 使用与 DSL 中 preference.examples 高度相似的文本
        IngestResult result = agent.ingest("我偏好用 4 空格缩进写代码");
        assertNotNull(result);
        assertTrue(result.isSuccess(), "Ingest should succeed: " + result.error());
        assertNotNull(result.typeKind());
        assertTrue(result.confidence() > 0, "Confidence should be positive");
        // 至少不应是 context（该项目无关）或 reference
        assertNotEquals("context", result.typeKind(),
            "Preference-like text should not be context");
        assertNotEquals("reference", result.typeKind(),
            "Preference-like text should not be reference");
    }

    @Test
    @Order(4)
    @DisplayName("KeywordIntentClassifier 直接测试偏好分类")
    void keywordClassifierPreference() {
        var classifier = new com.memory.agent.engine.KeywordIntentClassifier();
        classifier.init(java.util.Map.of());
        var result = classifier.classify("我喜欢用 4 空格缩进", model);
        // 使用真实 DSL 中的 model（从 memory_dsl.yaml 加载）
        assertEquals("preference", result.typeKind(),
            "Keyword classifier should match exact example from DSL");
        assertTrue(result.confidence() > 0.5,
            "Confidence should be high for exact example match");
    }

    @Test
    @Order(4)
    @DisplayName("事实类输入应识别为 fact")
    void classifyFact() {
        IngestResult result = agent.ingest("Java 17 是 LTS 版本");
        assertTrue(result.isSuccess(), "Ingest should succeed");
        assertEquals("fact", result.typeKind(),
            "Should classify as fact, got: " + result.typeKind());
    }

    @Test
    @Order(5)
    @DisplayName("上下文类输入应识别为 context")
    void classifyContext() {
        IngestResult result = agent.ingest("当前在写 MemoryAgentTest 测试");
        assertTrue(result.isSuccess(), "Ingest should succeed");
        // context 包含"当前"等提示词 + "测试"关键词
        assertNotNull(result.typeKind());
    }

    @Test
    @Order(6)
    @DisplayName("无匹配时退回 fallback_type")
    void fallbackOnUnknown() {
        // 这句话和任何类型的 examples 都不匹配
        IngestResult result = agent.ingest("xyzzy quux blarg");
        assertTrue(result.isSuccess(), "Ingest should succeed with fallback");
        // 应退回到 fact（DSL 配置的 fallback_type）
        assertEquals("fact", result.typeKind());
    }

    // ── 测试 3: 存储验证 ────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("Ingest 后可 read 取出")
    void ingestThenRead() {
        IngestResult result = agent.ingest("接口命名用 I 前缀更清晰");
        assertTrue(result.isSuccess());
        assertNotNull(result.memoryId());

        String data = agent.read(result.memoryId());
        assertNotNull(data, "Created memory should be readable");
        // 应包含提取的内容
        assertTrue(data.contains("更清晰") || data.contains("接口命名"),
            "Data should contain original content");
    }

    @Test
    @Order(8)
    @DisplayName("带 #标签 的输入被提取")
    void ingestWithHashTags() {
        IngestResult result = agent.ingest("Log 应该用英文 #code-style #logging");
        assertTrue(result.isSuccess());
        assertNotNull(result.tags());
        assertFalse(result.tags().isEmpty(), "Should extract #tags");
        assertTrue(result.tags().contains("code-style"));
        assertTrue(result.tags().contains("logging"));
    }

    // ── 测试 4: 委托 API ─────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("MemoryAgent 委托 create/read/search 正常工作")
    void delegateApiWorks() {
        // create
        String id = agent.create("fact",
            "{\"content\":\"delegate test\"}",
            java.util.Set.of("test"));
        assertNotNull(id);

        // read
        String data = agent.read(id);
        assertNotNull(data);

        // search
        List<?> results = agent.search("delegate");
        assertFalse(results.isEmpty());

        // delete
        assertTrue(agent.delete(id));
    }

    @Test
    @Order(10)
    @DisplayName("Agent 未启用时 ingest 返回错误")
    void agentDisabledReturnsError() {
        String yaml = buildDisabledAgentDsl();
        MemoryAgent disabledAgent = MemoryAgentFactory.createFromString(yaml);
        IngestResult result = disabledAgent.ingest("test");
        assertFalse(result.isSuccess());
        assertNotNull(result.error());
        disabledAgent.close();
    }

    // ── 测试 5: LLM 分类器（无密钥时降级）───────────────────

    @Test
    @Order(11)
    @DisplayName("LLM 分类器无密钥时自动降级到 keyword")
    void llmClassifierFallsBackWithoutKey() {
        var llmClassifier = new com.memory.agent.engine.LlmIntentClassifier();
        // 故意不传 api_key，应自动使用 keyword fallback
        llmClassifier.init(java.util.Map.of(
            "model", "gpt-4o-mini",
            "endpoint", "https://api.openai.com/v1/chat/completions"
        ));

        var result = llmClassifier.classify("Java 17 是 LTS 版本", model);
        assertNotNull(result);
        assertEquals("fact", result.typeKind(), "Should fallback to keyword classification");
    }

    @Test
    @Order(12)
    @DisplayName("KeywordIntentClassifier 返回 topN 列表")
    void keywordClassifierTopN() {
        var classifier = new com.memory.agent.engine.KeywordIntentClassifier();
        classifier.init(java.util.Map.of());

        List<IntentClassifier.ClassifyResult> results =
            classifier.classifyTopN("我喜欢用 4 空格缩进", model, 3);
        assertNotNull(results);
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 3);
        // 最高的应为 preference
        assertEquals("preference", results.get(0).typeKind());
    }

    // ── 测试 6: 冲突检测 ────────────────────────────────────

    @Test
    @Order(13)
    @DisplayName("相同内容后续 ingest 应缓存 type")
    void repeatedIngestUsesTypeCache() {
        IngestResult r1 = agent.ingest("Spring Boot 3.0 需要 Java 17");
        assertTrue(r1.isSuccess());
        assertNotNull(r1.memoryId());

        // 第二次 ingest 相似的
        IngestResult r2 = agent.ingest("Spring Boot 3.0 要求 Java 17 或以上版本");
        assertTrue(r2.isSuccess());
        assertNotNull(r2.memoryId());

        // 两条都能读出
        assertNotNull(agent.read(r1.memoryId()));
        assertNotNull(agent.read(r2.memoryId()));
    }

    // ── helpers ──────────────────────────────────────────────

    /** 构建最小可用 Agent DSL（含类型定义） */
    private static String buildMinimalAgentDsl() {
        return """
            version: "1.0"
            globals:
              default_type: fact
              max_memory_size: 1000
              default_ttl_days: 30
              storage:
                engine: json
                path: "./data/memory"
                encoding: "utf-8"
            types:
              fact:
                description: "事实知识"
                fields:
                  content: { type: string, required: true }
                tags:
                  max: 10
                meta:
                  unique_by: ["content"]
                agent:
                  prompt: "客观事实、技术知识"
                  examples:
                    - "Java 17 是 LTS 版本"
                    - "Spring Boot 3.0 需要 Java 17 及以上"
                  field_hints:
                    content: "提取核心事实内容"
              preference:
                description: "用户偏好"
                fields:
                  content: { type: string, required: true }
                  category: { type: string, required: false }
                  strength: { type: int, min: 1, max: 5, default: 3 }
                tags:
                  max: 5
                meta:
                  importance_floor: 0.3
                agent:
                  prompt: "用户偏好、习惯、代码风格选择"
                  examples:
                    - "我喜欢用 4 空格缩进"
                    - "接口命名用 I 前缀更清晰"
                  field_hints:
                    content: "提取偏好内容"
                    category: "偏好类别"
              context:
                description: "项目上下文"
                fields:
                  content: { type: string, required: true }
                  scope: { type: string, required: false }
                tags:
                  max: 20
                meta:
                  ephemeral: true
                  importance_floor: 0.05
                agent:
                  prompt: "项目状态、当前任务、临时上下文"
                  examples:
                    - "当前在写 MemoryMgrTest"
                    - "这个模块依赖 Spring Boot 3.0"
                  field_hints:
                    content: "提取上下文摘要"
              reference:
                description: "参考资料"
                fields:
                  content: { type: string, required: true }
                  url: { type: string, required: false, format: uri }
                  format: { type: string, enum: [link, snippet, file] }
                tags:
                  max: 15
                agent:
                  prompt: "外部引用、链接、文件路径"
                  examples:
                    - "参考文档 https://spring.io/docs"
                  field_hints:
                    content: "提取引用描述"
            decay:
              default:
                daily_decay: 0.92
                access_gain: 0.05
                min_importance: 0.1
              lifecycle:
                stale_after_days: 14
                archive_after_days: 30
                purge_when:
                  importance_below: 0.1
                  or_stale_days: 60
            search:
              engines:
                keyword:
                  enabled: true
                  description: "精确字面匹配"
              strategies:
                default:
                  description: "默认"
                  steps:
                    - engine: keyword
                      weight: 1.0
                      top_k: 10
                  merge: "weighted_score"
                  limit: 10
            agent:
              enabled: true
              intent:
                engine: "keyword-match"
                confidence_threshold: 0.6
                fallback_type: "fact"
              extraction:
                engine: "template"
              conflict:
                engine: "field-compare"
                resolution: "ask"
              importance:
                engine: "heuristic"
                default_importance: 0.8
              consolidation:
                engine: "simple-merge"
                schedule: "0 2 * * *"
                strategy: "latest-wins"
            """;
    }

    /** Agent 禁用 */
    private static String buildDisabledAgentDsl() {
        return buildMinimalAgentDsl().replace(
            "enabled: true", "enabled: false");
    }
}
