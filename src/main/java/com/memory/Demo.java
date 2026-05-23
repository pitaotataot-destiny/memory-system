package com.memory;

import com.memory.engine.manager.DecayMgr;
import com.memory.engine.manager.SearchMgr;

import java.nio.file.Path;

/**
 * 记忆系统演示程序。
 * 演示完整的记忆 CRUD、搜索、衰减、生命周期检查等流程。
 */
public class Demo {

    private static final String DSL_PATH = "memory_dsl.yaml";

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  Memory System — 记忆系统演示                 ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        Path dslFile = Path.of(DSL_PATH);

        // ── 阶段 1: 初始化 ─────────────────────────────
        System.out.println("【1. 初始化】");
        System.out.println("  加载 DSL: " + dslFile.toAbsolutePath());
        try (MemoryClient client = MemoryFactory.create(dslFile)) {
            var model = client.getModel();
            System.out.println("  DSL 版本: " + model.getVersion());
            System.out.println("  记忆类型: " + model.getTypes().keySet());
            System.out.println("  搜索引擎: " + model.getSearch().getEngines().keySet());
            System.out.println("  搜索策略: " + model.getSearch().getStrategies().keySet());
            System.out.println("  触发器:   " + model.getTriggers().stream()
                    .map(t -> t.getName()).toList());
            System.out.println("  最大容量: " + model.getGlobals().getMaxMemorySize());
            System.out.println("  存储路径: " + client.getModel().getGlobals().getStorage().getPath());
            System.out.println();

            // ── 阶段 2: 创建记忆 ──────────────────────────
            System.out.println("【2. 创建记忆】");

            String id1 = client.create("fact",
                "{\"content\":\"Java 17 是 LTS 版本，支持到 2029 年\",\"source\":\"Oracle\"}",
                java.util.Set.of("java", "lts", "version"));
            System.out.println("  创建事实: " + shortId(id1) + "  tags=[java,lts,version]");

            String id2 = client.create("fact",
                "{\"content\":\"Python 的 GIL 导致多线程无法真正利用多核 CPU\"}",
                java.util.Set.of("python", "concurrency"));
            System.out.println("  创建事实: " + shortId(id2) + "  tags=[python,concurrency]");

            String id3 = client.create("preference",
                "{\"content\":\"代码缩进使用 4 空格而不是 Tab\",\"category\":\"coding_style\",\"strength\":5}",
                java.util.Set.of("coding", "style"));
            System.out.println("  创建偏好: " + shortId(id3) + "  category=coding_style strength=5");

            String id4 = client.create("context",
                "{\"content\":\"当前项目是 memory-system，基于 DSL 驱动\",\"scope\":\"project\"}",
                java.util.Set.of("current-project"));
            System.out.println("  创建上下文: " + shortId(id4) + "  scope=project");

            String id5 = client.create("reference",
                "{\"content\":\"SnakeYAML 文档\",\"url\":\"https://bitbucket.org/snakeyaml/snakeyaml\",\"format\":\"link\"}",
                java.util.Set.of("yaml", "doc"));
            System.out.println("  创建资料: " + shortId(id5) + "  format=link");

            System.out.println("  当前总数: " + client.count());
            System.out.println();

            // ── 阶段 3: 读取记忆 ──────────────────────────
            System.out.println("【3. 读取记忆】");
            String data = client.read(id1);
            System.out.println("  读取 " + shortId(id1) + ": " +
                extractField(data, "content", 40) + "...");
            System.out.println();

            // ── 阶段 4: 更新记忆 ──────────────────────────
            System.out.println("【4. 更新记忆】");
            client.update(id1,
                "{\"content\":\"Java 17 是 LTS 版本，Oracle 支持到 2029 年\",\"source\":\"Oracle\"}");
            System.out.println("  更新后: " + shortId(id1) + " -> content 已修改");
            System.out.println();

            // ── 阶段 5: 搜索 ─────────────────────────────
            System.out.println("【5. 搜索】");

            // keyword search (default strategy includes embedding which is placeholder)
            // Use fast strategy (keyword only)
            var results = client.search("Java", "fast");
            System.out.println("  搜索 \"Java\" (fast策略):");
            if (results.isEmpty()) {
                System.out.println("    (无结果)");
            } else {
                int rank = 1;
                for (SearchMgr.SearchResult r : results) {
                    System.out.printf("    [%d] id=%s  score=%.2f%n", rank++, shortId(r.memoryId()), r.rawScore());
                }
            }

            results = client.search("Python", "fast");
            System.out.println("  搜索 \"Python\" (fast策略):");
            if (results.isEmpty()) {
                System.out.println("    (无结果 — 同上)");
            } else {
                int rank = 1;
                for (SearchMgr.SearchResult r : results) {
                    System.out.printf("    [%d] id=%s  score=%.2f%n", rank++, shortId(r.memoryId()), r.rawScore());
                }
            }
            System.out.println();

            // ── 阶段 6: 衰减与生命周期 ─────────────────────
            System.out.println("【6. 衰减与生命周期】");
            DecayMgr.LifecycleSummary summary = client.runDecay();
            System.out.println("  衰减完成: 总记忆=" + summary.total() +
                "  清理=" + summary.purged() + "  归档=" + summary.archived());

            for (String mid : client.listAll()) {
                DecayMgr.LifecycleStatus status = client.checkLifecycle(mid);
                System.out.println("    " + shortId(mid) + " -> " + status.status() +
                    " (" + status.reason() + ")");
            }
            System.out.println();

            // ── 阶段 7: 统计指标 ─────────────────────────
            System.out.println("【7. 统计指标】");
            System.out.println("  查询次数: " + client.getModel().getGlobals().getMaxMemorySize());
            System.out.println("  实际查询: " + client.count() + " 条记忆");
            System.out.println();

            // ── 阶段 8: 删除记忆 ─────────────────────────
            System.out.println("【8. 删除记忆】");
            boolean del = client.delete(id5);
            System.out.println("  删除资料: " + shortId(id5) + " -> " + (del ? "成功" : "失败"));
            System.out.println("  剩余: " + client.count() + " 条");
            System.out.println();

        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("【9. 数据已持久化到本地】");
        System.out.println("  执行: mvn compile exec:java -Dexec.mainClass=com.memory.Demo");
        System.out.println("  再次运行将读取到之前保存的数据");
        System.out.println();
        System.out.println("演示完成 ✓");
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
