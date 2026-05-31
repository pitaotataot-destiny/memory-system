package com.memory;

import com.memory.engine.manager.DecayMgr;
import com.memory.engine.manager.SearchMgr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 记忆系统演示程序。
 * 演示完整的记忆 CRUD、搜索、衰减、生命周期检查等流程。
 */
public class Demo {

    private static final Logger LOG = LoggerFactory.getLogger(Demo.class);
    private static final String DSL_PATH = "memory_dsl.yaml";

    public static void main(String[] args) {
        LOG.info("╔══════════════════════════════════════════════╗");
        LOG.info("║  Memory System — 记忆系统演示                 ║");
        LOG.info("╚══════════════════════════════════════════════╝");

        Path dslFile = Path.of(DSL_PATH);

        // ── 阶段 1: 初始化 ─────────────────────────────
        LOG.info("【1. 初始化】");
        LOG.info("  加载 DSL: {}", dslFile.toAbsolutePath());
        try (MemoryClient client = MemoryFactory.create(dslFile)) {
            var model = client.getModel();
            LOG.info("  DSL 版本: {}", model.getVersion());
            LOG.info("  记忆类型: {}", model.getTypes().keySet());
            LOG.info("  搜索引擎: {}", model.getSearch().getEngines().keySet());
            LOG.info("  搜索策略: {}", model.getSearch().getStrategies().keySet());
            LOG.info("  触发器:   {}", model.getTriggers().stream()
                    .map(t -> t.getName()).toList());
            LOG.info("  最大容量: {}", model.getGlobals().getMaxMemorySize());
            LOG.info("  存储路径: {}", client.getModel().getGlobals().getStorage().getPath());

            // ── 阶段 2: 创建记忆 ──────────────────────────
            LOG.info("【2. 创建记忆】");

            String id1 = client.create("fact",
                "{\"content\":\"Java 17 是 LTS 版本，支持到 2029 年\",\"source\":\"Oracle\"}",
                java.util.Set.of("java", "lts", "version"));
            LOG.info("  创建事实: {}  tags=[java,lts,version]", shortId(id1));

            String id2 = client.create("fact",
                "{\"content\":\"Python 的 GIL 导致多线程无法真正利用多核 CPU\"}",
                java.util.Set.of("python", "concurrency"));
            LOG.info("  创建事实: {}  tags=[python,concurrency]", shortId(id2));

            String id3 = client.create("preference",
                "{\"content\":\"代码缩进使用 4 空格而不是 Tab\",\"category\":\"coding_style\",\"strength\":5}",
                java.util.Set.of("coding", "style"));
            LOG.info("  创建偏好: {}  category=coding_style strength=5", shortId(id3));

            String id4 = client.create("context",
                "{\"content\":\"当前项目是 memory-system，基于 DSL 驱动\",\"scope\":\"project\"}",
                java.util.Set.of("current-project"));
            LOG.info("  创建上下文: {}  scope=project", shortId(id4));

            String id5 = client.create("reference",
                "{\"content\":\"SnakeYAML 文档\",\"url\":\"https://bitbucket.org/snakeyaml/snakeyaml\",\"format\":\"link\"}",
                java.util.Set.of("yaml", "doc"));
            LOG.info("  创建资料: {}  format=link", shortId(id5));

            LOG.info("  当前总数: {}", client.count());

            // ── 阶段 3: 读取记忆 ──────────────────────────
            LOG.info("【3. 读取记忆】");
            String data = client.read(id1);
            LOG.info("  读取 {}: {}...", shortId(id1), extractField(data, "content", 40));

            // ── 阶段 4: 更新记忆 ──────────────────────────
            LOG.info("【4. 更新记忆】");
            client.update(id1,
                "{\"content\":\"Java 17 是 LTS 版本，Oracle 支持到 2029 年\",\"source\":\"Oracle\"}");
            LOG.info("  更新后: {} -> content 已修改", shortId(id1));

            // ── 阶段 5: 搜索 ─────────────────────────────
            LOG.info("【5. 搜索】");

            var results = client.search("Java", "fast");
            LOG.info("  搜索 \"Java\" (fast策略):");
            if (results.isEmpty()) {
                LOG.info("    (无结果)");
            } else {
                int rank = 1;
                for (SearchMgr.SearchResult r : results) {
                    LOG.info("    [{}] id={}  score={}", rank++, shortId(r.memoryId()),
                        String.format("%.2f", r.rawScore()));
                }
            }

            results = client.search("Python", "fast");
            LOG.info("  搜索 \"Python\" (fast策略):");
            if (results.isEmpty()) {
                LOG.info("    (无结果 — 同上)");
            } else {
                int rank = 1;
                for (SearchMgr.SearchResult r : results) {
                    LOG.info("    [{}] id={}  score={}", rank++, shortId(r.memoryId()),
                        String.format("%.2f", r.rawScore()));
                }
            }

            // ── 阶段 6: 衰减与生命周期 ─────────────────────
            LOG.info("【6. 衰减与生命周期】");
            DecayMgr.LifecycleSummary summary = client.runDecay();
            LOG.info("  衰减完成: 总记忆={}  清理={}  归档={}",
                summary.total(), summary.purged(), summary.archived());

            for (String mid : client.listAll()) {
                DecayMgr.LifecycleStatus status = client.checkLifecycle(mid);
                LOG.info("    {} -> {} ({})", shortId(mid), status.status(), status.reason());
            }

            // ── 阶段 7: 统计指标 ─────────────────────────
            LOG.info("【7. 统计指标】");
            LOG.info("  最大容量: {}", client.getModel().getGlobals().getMaxMemorySize());
            LOG.info("  当前记忆: {} 条", client.count());

            // ── 阶段 8: 删除记忆 ─────────────────────────
            LOG.info("【8. 删除记忆】");
            boolean del = client.delete(id5);
            LOG.info("  删除资料: {} -> {}", shortId(id5), del ? "成功" : "失败");
            LOG.info("  剩余: {} 条", client.count());

        } catch (Exception e) {
            LOG.error("演示过程发生错误: {}", e.getMessage(), e);
        }

        LOG.info("【9. 数据已持久化到本地】");
        LOG.info("  执行: mvn compile exec:java -Dexec.mainClass=com.memory.Demo");
        LOG.info("  再次运行将读取到之前保存的数据");
        LOG.info("演示完成 ✓");
    }

    private static String shortId(String id) {
        if (id == null || id.length() <= 12) return id;
        return id.substring(0, 8) + "..." + id.substring(id.length() - 4);
    }

    private static String extractField(String json, String field, int maxLen) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return "(unknown)";
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return "(unknown)";
        String value = json.substring(start, end);
        return value.length() > maxLen ? value.substring(0, maxLen) + "..." : value;
    }
}
