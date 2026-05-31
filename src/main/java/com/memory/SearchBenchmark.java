package com.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

/**
 * 搜索性能基准测试。
 * 在不同数据规模下测试 keyword 搜索的耗时。
 */
public class SearchBenchmark {

    private static final Logger LOG = LoggerFactory.getLogger(SearchBenchmark.class);

    private static final int[] SIZES = {100, 500, 1000, 5000};

    private static final String[] SAMPLE_CONTENTS = {
        "Java 是一种广泛使用的编程语言，支持面向对象和函数式编程",
        "Python 的 GIL 限制了多线程的并行执行能力",
        "Spring Boot 自动配置简化了项目的启动流程",
        "Docker 容器技术实现了应用的环境隔离",
        "Kubernetes 提供了容器编排和自动伸缩能力",
        "Redis 缓存可以显著提升数据读取的性能",
        "MySQL 索引优化是数据库调优的重要手段",
        "GraphQL 替代 REST 可以减少不必要的数据传输",
        "微服务架构将大型应用拆分为多个独立服务",
        "CI/CD 管道自动化了代码的构建测试和部署",
        "机器学习模型训练需要大量的计算资源和数据",
        "分布式系统的一致性协议保证了数据的正确性",
        "响应式编程通过异步事件流处理提高吞吐量",
        "函数式编程强调不可变数据和纯函数",
        "设计模式提供了解决常见软件设计问题的模板",
        "内存泄漏会导致应用性能逐渐下降最终崩溃",
        "单元测试是保证代码质量的重要手段",
        "代码重构可以改善代码的可读性和可维护性",
        "API 网关统一管理微服务的入口和认证",
        "事件驱动架构通过消息队列解耦服务间通信"
    };

    private static final String[][] SAMPLE_TAGS = {
        {"java", "programming"},
        {"python", "concurrency"},
        {"spring", "framework"},
        {"docker", "devops"},
        {"kubernetes", "cloud"},
        {"redis", "cache"},
        {"mysql", "database"},
        {"graphql", "api"},
        {"microservice", "architecture"},
        {"cicd", "devops"},
        {"ml", "ai"},
        {"distributed", "system"},
        {"reactive", "async"},
        {"functional", "paradigm"},
        {"pattern", "design"},
        {"memory", "performance"},
        {"test", "quality"},
        {"refactor", "clean-code"},
        {"gateway", "api"},
        {"event", "messaging"}
    };

    public static void main(String[] args) throws Exception {
        LOG.info("=== Memory System Search Benchmark ===");

        for (int size : SIZES) {
            benchmark(size);
        }
    }

    private static void benchmark(int size) throws IOException {
        Path dataDir = Files.createTempDirectory("bench-");
        String safePath = dataDir.toString().replace("\\", "/");

        String yaml = yamlTemplate(safePath);

        // write + warmup + measured search
        try (MemoryClient client = MemoryFactory.createFromString(yaml)) {
            long writeStart = System.currentTimeMillis();
            for (int i = 0; i < size; i++) {
                int idx = i % SAMPLE_CONTENTS.length;
                client.create("fact",
                    "{\"content\":\"" + SAMPLE_CONTENTS[idx] + " #" + i + "\"}",
                    new HashSet<>(Arrays.asList(SAMPLE_TAGS[idx])));
            }
            long writeTime = System.currentTimeMillis() - writeStart;

            // warmup
            client.search("java");

            long[] times = new long[5];
            int resultCount = 0;
            for (int r = 0; r < 5; r++) {
                long s = System.nanoTime();
                var res = client.search("java");
                long e = System.nanoTime();
                times[r] = (e - s) / 1_000; // microseconds
                if (r == 2) resultCount = res.size();
            }

            Arrays.sort(times);
            long median = times[2];

            LOG.info("N={} | write={} ms | search=median {} us (min={}, max={}) | results={}",
                String.format("%-6d", size),
                String.format("%5d", writeTime),
                String.format("%-6d", median),
                times[0], times[4], resultCount);
        }

        // Cleanup
        try (var s = Files.list(dataDir)) {
            s.forEach(f -> { try { Files.deleteIfExists(f); } catch (IOException ignored) {} });
        }
        Files.deleteIfExists(dataDir);
    }

    private static String yamlTemplate(String path) {
        return """
            version: "1.0"
            globals:
              default_type: fact
              max_memory_size: 10000
              default_ttl_days: 30
              storage:
                engine: json
                path: '%s'
            types:
              fact:
                description: "事实"
                fields:
                  content: { type: string, required: true }
                tags:
                  max: 10
            decay:
              default:
                daily_decay: 0.9
                access_gain: 0.05
                min_importance: 0.1
            search:
              engines:
                keyword:
                  enabled: true
              strategies:
                default:
                  steps:
                    - engine: keyword
                      weight: 1.0
                      top_k: 10
                fast:
                  steps:
                    - engine: keyword
                      weight: 1.0
                      top_k: 10
            triggers: []
            """.formatted(path);
    }
}
