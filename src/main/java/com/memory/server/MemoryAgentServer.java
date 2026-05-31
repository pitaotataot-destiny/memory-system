package com.memory.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.agent.MemoryAgent;
import com.memory.agent.MemoryAgentFactory;
import com.memory.agent.pipeline.IngestResult;
import com.memory.engine.manager.DecayMgr;
import com.memory.engine.manager.SearchMgr;
import com.memory.model.MemoryRecord;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Memory Agent HTTP API 服务器。
 *
 * 基于 JDK 内置 {@link HttpServer}，无需 Spring Boot 等额外依赖。
 * 提供 RESTful API 供外部系统（Python/Node/Go/curl）调用。
 *
 * <h3>端点</h3>
 * <pre>
 * POST   /api/ingest          摄入原始文本
 * GET    /api/memories        列出所有记忆 ID
 * GET    /api/memories/{id}   读取一条记忆
 * POST   /api/memories        创建记忆（结构化）
 * PUT    /api/memories/{id}   更新记忆
 * DELETE /api/memories/{id}   删除记忆
 * GET    /api/search?q=...    搜索
 * POST   /api/decay           执行衰减计算
 * GET    /api/health          健康检查
 * </pre>
 */
public class MemoryAgentServer {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryAgentServer.class);

    private static final String CONTENT_JSON = "application/json; charset=utf-8";
    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int HTTP_NO_CONTENT = 204;

    private final HttpServer server;
    private final MemoryAgent agent;
    private final ObjectMapper mapper;

    public MemoryAgentServer(String host, int port, MemoryAgent agent) throws IOException {
        this.agent = agent;
        this.mapper = new ObjectMapper();
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        registerRoutes();
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()));
    }

    // ── 路由注册 ──────────────────────────────────────────────

    private void registerRoutes() {
        server.createContext("/api/ingest", this::handleIngest);
        server.createContext("/api/memories", this::handleMemories);
        server.createContext("/api/search", this::handleSearch);
        server.createContext("/api/decay", this::handleDecay);
        server.createContext("/api/health", this::handleHealth);
    }

    public void start() {
        server.start();
        LOG.info("Memory Agent HTTP API started at {}", server.getAddress());
    }

    public void stop() {
        server.stop(1);
        agent.close();
        LOG.info("Memory Agent HTTP API stopped");
    }

    // ── 端点处理 ──────────────────────────────────────────────

    /** POST /api/ingest — 摄入原始文本 */
    private void handleIngest(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, HTTP_BAD_REQUEST, "Use POST");
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(exchange.getRequestBody(), Map.class);
            String text = (String) body.get("text");
            if (text == null || text.isBlank()) {
                sendError(exchange, HTTP_BAD_REQUEST, "Field 'text' is required");
                return;
            }
            IngestResult result = agent.ingest(text);
            sendJson(exchange, HTTP_OK, toIngestResponse(result));
        } catch (Exception e) {
            LOG.error("Ingest failed", e);
            sendJson(exchange, HTTP_INTERNAL_ERROR, Map.of("error", e.getMessage()));
        }
    }

    /** GET/POST/PUT/DELETE /api/memories [/...] */
    private void handleMemories(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        // /api/memories/{id}
        if (path.length() > "/api/memories".length()) {
            String id = path.substring("/api/memories/".length());
            switch (method) {
                case "GET" -> handleRead(exchange, id);
                case "PUT" -> handleUpdate(exchange, id);
                case "DELETE" -> handleDelete(exchange, id);
                default -> sendError(exchange, HTTP_BAD_REQUEST, "Unsupported method: " + method);
            }
            return;
        }

        // /api/memories
        switch (method) {
            case "GET" -> handleList(exchange);
            case "POST" -> handleCreate(exchange);
            default -> sendError(exchange, HTTP_BAD_REQUEST, "Unsupported method: " + method);
        }
    }

    /** GET /api/memories/{id} */
    private void handleRead(HttpExchange exchange, String id) throws IOException {
        String data = agent.read(id);
        if (data == null) {
            sendError(exchange, HTTP_NOT_FOUND, "Memory not found: " + id);
            return;
        }
        try {
            // 尝试解析 JSON 返回
            Object parsed = mapper.readValue(data, Object.class);
            sendJson(exchange, HTTP_OK, Map.of("id", id, "data", parsed));
        } catch (Exception e) {
            sendJson(exchange, HTTP_OK, Map.of("id", id, "raw", data));
        }
    }

    /** DELETE /api/memories/{id} */
    private void handleDelete(HttpExchange exchange, String id) throws IOException {
        boolean deleted = agent.delete(id);
        sendJson(exchange, deleted ? HTTP_OK : HTTP_NOT_FOUND,
            Map.of("deleted", deleted, "id", id));
    }

    /** PUT /api/memories/{id} */
    private void handleUpdate(HttpExchange exchange, String id) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(exchange.getRequestBody(), Map.class);
        String data = body.containsKey("data") ? body.get("data").toString() : null;
        if (data == null) {
            // 尝试将整个 body 序列化为 JSON
            data = mapper.writeValueAsString(body);
        }
        try {
            agent.update(id, data);
            sendJson(exchange, HTTP_OK, Map.of("updated", true, "id", id));
        } catch (Exception e) {
            sendError(exchange, HTTP_NOT_FOUND, e.getMessage());
        }
    }

    /** GET /api/memories — 列出记忆，支持 ?type=&tag=&limit= */
    private void handleList(HttpExchange exchange) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getQuery());
        String filterType = q.get("type");
        String filterTag = q.get("tag");
        int limit = asInt(q.get("limit"), 100);

        Set<String> ids = agent.listAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) {
            String raw = agent.read(id);
            if (raw == null) continue;

            MemoryRecord record;
            try { record = MemoryRecord.fromJson(raw); }
            catch (Exception e) { continue; }

            // 类型过滤
            if (filterType != null && !filterType.equals(record.getType())) continue;
            // 标签过滤
            if (filterTag != null && !record.getTags().contains(filterTag)) continue;

            DecayMgr.LifecycleStatus status = agent.checkLifecycle(id);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("type", record.getType());
            item.put("lifecycle", status.status());
            item.put("importance", record.getImportance());
            item.put("tags", record.getTags());
            item.put("content", preview(record.getDataField("content"), 100));
            item.put("lastAccessed", record.getLastAccessed());
            result.add(item);

            if (result.size() >= limit) break;
        }
        sendJson(exchange, HTTP_OK, Map.of("count", result.size(), "memories", result));
    }

    private static String preview(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static int asInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    /** POST /api/memories — 创建结构化记忆 */
    private void handleCreate(HttpExchange exchange) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = mapper.readValue(exchange.getRequestBody(), Map.class);
        String typeKind = (String) body.getOrDefault("type", "fact");
        String data = body.containsKey("data")
            ? mapper.writeValueAsString(body.get("data"))
            : mapper.writeValueAsString(body);

        @SuppressWarnings("unchecked")
        List<String> tagList = (List<String>) body.get("tags");
        Set<String> tags = tagList != null ? Set.copyOf(tagList) : Set.of();

        try {
            String id = agent.create(typeKind, data, tags);
            sendJson(exchange, HTTP_OK, Map.of("id", id, "type", typeKind));
        } catch (Exception e) {
            sendError(exchange, HTTP_BAD_REQUEST, e.getMessage());
        }
    }

    /** GET /api/search?q=...&strategy=... */
    private void handleSearch(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        String q = params.get("q");
        if (q == null || q.isBlank()) {
            sendError(exchange, HTTP_BAD_REQUEST, "Query param 'q' is required");
            return;
        }
        String strategy = params.get("strategy");
        List<SearchMgr.SearchResult> results = strategy != null
            ? agent.search(q, strategy)
            : agent.search(q);

        List<Map<String, Object>> items = new ArrayList<>();
        for (SearchMgr.SearchResult r : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.memoryId());
            item.put("score", r.rawScore());
            item.put("source", r.source());
            items.add(item);
        }
        sendJson(exchange, HTTP_OK, Map.of("query", q, "count", items.size(), "results", items));
    }

    /** POST /api/decay — 执行衰减计算 */
    private void handleDecay(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, HTTP_BAD_REQUEST, "Use POST");
            return;
        }
        DecayMgr.LifecycleSummary summary = agent.runDecay();
        sendJson(exchange, HTTP_OK, Map.of(
            "total", summary.total(),
            "stale", summary.stale(),
            "archived", summary.archived(),
            "purged", summary.purged()
        ));
    }

    /** GET /api/health — 健康检查 */
    private void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, HTTP_OK, Map.of(
            "status", "ok",
            "version", agent.getModel() != null ? agent.getModel().getVersion() : "unknown",
            "memories", agent.count(),
            "agent_enabled", agent.getModel() != null
                && agent.getModel().getAgent() != null
                && agent.getModel().getAgent().isEnabled()
        ));
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        String json = mapper.writeValueAsString(body);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return result;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                result.put(
                    java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                );
            }
        }
        return result;
    }

    private Map<String, Object> toIngestResponse(IngestResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rawText", r.rawText());
        m.put("typeKind", r.typeKind());
        m.put("confidence", r.confidence());
        m.put("memoryId", r.memoryId());
        m.put("decision", r.decision().name());
        m.put("success", r.isSuccess());
        if (r.fields() != null) m.put("fields", r.fields());
        if (r.tags() != null) m.put("tags", r.tags());
        if (r.error() != null) m.put("error", r.error());
        return m;
    }

    // ── main 入口 ─────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String dslPath = args.length > 0 ? args[0] : "memory_dsl.yaml";
        String host = System.getProperty("memory.server.host", "0.0.0.0");
        int port = Integer.parseInt(System.getProperty("memory.server.port",
            System.getenv().getOrDefault("MEMORY_SERVER_PORT", "8080")));

        // 将环境变量中的 API key 同步到系统属性（确保 LLM 组件能读到）
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            System.setProperty("OPENAI_API_KEY", apiKey);
            LOG.info("OPENAI_API_KEY loaded from environment");
        }

        LOG.info("Starting Memory Agent from DSL: {}", dslPath);
        MemoryAgent agent = MemoryAgentFactory.create(Path.of(dslPath));
        MemoryAgentServer server = new MemoryAgentServer(host, port, agent);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down...");
            server.stop();
        }));
    }
}
