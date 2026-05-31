# Memory System — 声明式 DSL + Meta Model 记忆引擎

基于声明式 DSL 的记忆（Memory）管理系统。核心设计是将记忆系统的行为规则从代码中抽离，用 YAML DSL 描述，运行时解析为内部 Meta Model，引擎只消费模型对象。

**核心目标：改规则不改代码。** 新增类型、调整衰减、切换搜索策略，只改 YAML，不改 Java。

## 三层架构

```
Layer 3  MemoryAgent    ← 输入原始文本，自主分类+提取+存储
Layer 2  MemoryClient   ← 结构化 CRUD + 搜索 + 衰减 + 触发器
Layer 1  存储层          ← JSON 文件 / SQLite / Redis (SPI 可替换)
```

| 层 | 入口 | 输入 | 谁做决策 |
|---|------|------|---------|
| Layer 3 Agent | `MemoryAgent.ingest(rawText)` | 原始自然语言 | **系统自主** |
| Layer 2 Engine | `MemoryClient.create(type, data)` | 结构化 JSON | 调用方决定 |

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17+ | 主语言 |
| Maven | 4.0.0 | 构建工具 |
| SnakeYAML | 2.3 | YAML 解析 |
| Jackson | 2.18.3 | JSON 序列化 |
| JUnit Jupiter | 5.11 | 单元测试 |

## 快速开始

### 嵌入式调用

```java
try (MemoryAgent agent = MemoryAgentFactory.create(Path.of("memory_dsl.yaml"))) {
    // Layer 3: 自主摄入
    IngestResult r = agent.ingest("我喜欢用 4 空格缩进");
    // → typeKind="preference", confidence=0.92, memoryId="abc123"

    // Layer 2 API 照常可用
    agent.search("java");
    agent.runDecay();
}
```

### HTTP 服务

```bash
# 启动 (JDK 17 内置 HttpServer，零额外依赖)
java -cp memory-system.jar com.memory.server.MemoryAgentServer memory_dsl.yaml

# 摄入
curl -X POST http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -d '{"text":"我喜欢用 4 空格缩进"}'

# 搜索
curl "http://localhost:8080/api/search?q=Java+LTS"

# 健康检查
curl http://localhost:8080/api/health
```

### LLM 模式

```bash
# 设置密钥后，DSL 中 engine: "llm" 会自动调用 LLM
export OPENAI_API_KEY="sk-..."
java -cp memory-system.jar com.memory.server.MemoryAgentServer memory_dsl.yaml
```

## HTTP API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ingest` | 摄入原始文本 (Agent 全链路) |
| GET | `/api/memories` | 列出所有记忆 (含生命周期状态) |
| GET | `/api/memories/{id}` | 读取一条记忆 |
| POST | `/api/memories` | 创建结构化记忆 |
| PUT | `/api/memories/{id}` | 更新记忆 |
| DELETE | `/api/memories/{id}` | 删除记忆 |
| GET | `/api/search?q=...&strategy=...` | 搜索 |
| POST | `/api/decay` | 执行衰减计算 |
| GET | `/api/health` | 健康检查 |

## Agent 管道 (Layer 3)

```
原始文本 ──► [同步] 分类+提取 ──► [同步] 重要性 ──► [同步] 存储
                │                                        │
                ▼                                        ▼
           返回结果给用户                          [异步] 冲突检测
```

| 组件 | SPI 接口 | 默认实现 | LLM 实现 |
|------|---------|---------|---------|
| 意图分类 | IntentClassifier | KeywordIntentClassifier (关键词匹配) | LlmIntentClassifier |
| 信息提取 | InformationExtractor | TemplateInfoExtractor (规则提取) | LlmInfoExtractor |
| 冲突检测 | ConflictDetector | FieldConflictDetector (字段对比) | — |
| 重要性 | ImportanceAssigner | HeuristicImportanceAssigner | — |
| 记忆合并 | MemoryConsolidator | SimpleConsolidator | — |

**LLM 调用次数**：

| 配置 | 调用次数 |
|------|---------|
| keyword + template | 0 次 (纯本地) |
| llm + template | 1 次 |
| llm + llm | **1 次** (合并调用，分类+提取一次完成) |

## SPI 扩展点

```
com.memory.spi.MemoryStore          — 存储层 (JsonMemoryStore)
com.memory.spi.SearchProvider       — 搜索 (Keyword / TF-IDF / Embedding)
com.memory.spi.EventBus             — 事件总线 (LocalEventBus)
com.memory.spi.Scheduler            — 调度器 (DefaultScheduler)
com.memory.spi.ExpressionEngine     — 表达式 (DefaultExpressionEngine)

com.memory.agent.spi.IntentClassifier      — Agent 意图分类
com.memory.agent.spi.InformationExtractor  — Agent 信息提取
com.memory.agent.spi.ConflictDetector      — Agent 冲突检测
com.memory.agent.spi.ImportanceAssigner    — Agent 重要性评估
com.memory.agent.spi.MemoryConsolidator   — Agent 记忆合并
```

## 项目结构

```
memory_dsl.yaml                  # DSL 规则定义
src/main/java/com/memory/
  MemoryClient.java              # Layer 2 门面
  MemoryFactory.java             # Layer 2 工厂
  agent/
    MemoryAgent.java             # Layer 3 门面
    MemoryAgentFactory.java      # Layer 3 工厂
    spi/                         # Agent SPI (5 接口)
    pipeline/                    # Ingest 管道 (同步+异步)
    engine/                      # Agent 默认实现 (5 个 + 2 个 LLM)
  engine/
    manager/                     # MemoryMgr / SearchMgr / DecayMgr / TriggerMgr
    search/                      # Keyword / TF-IDF / Embedding 搜索
    store/                       # JsonMemoryStore
    scheduler/                   # DefaultScheduler
    event/                       # LocalEventBus
    expression/                  # DefaultExpressionEngine
  model/                         # Meta Model
    agent/                       # Agent DSL 配置模型
  spi/                           # 核心 SPI (5 接口)
  registry/                      # ComponentRegistry (ServiceLoader)
  runtime/                       # MemoryRuntimeContext + DSLWatcher
  server/                        # HTTP API (MemoryAgentServer)
  dsl/                           # DSLParser
```

## 常用命令

```bash
export JAVA_HOME=G:/env/jdk-17.0.17
export PATH=$JAVA_HOME/bin:$PATH

mvn compile              # 编译
mvn test                 # 运行测试 (当前 140+)
mvn package              # 打包
mvn dependency:tree      # 查看依赖树
```
