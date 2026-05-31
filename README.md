# Memory System — 声明式 DSL + Meta Model 记忆引擎

基于声明式 DSL 的记忆（Memory）管理系统。核心设计是将记忆系统的行为规则（类型定义、衰减策略、搜索策略、触发器）从代码中抽离，用 YAML DSL 描述，运行时解析为内部 Meta Model，引擎只消费模型对象，不直接读 YAML。

**核心目标：改规则不改代码。** 新增记忆类型、调整衰减曲线、切换搜索策略，只需修改 `memory_dsl.yaml`，不需要改一行 Java 代码。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17+ | 主语言 |
| Maven | 4.0.0 | 构建工具 |
| SnakeYAML | 2.3 | YAML 解析 |
| JUnit Jupiter | 5.11 | 单元测试 |

## 项目结构

```
memory_dsl.yaml              # DSL 规则定义（声明式，v1.0 已冻结）
pom.xml                      # Maven 构建配置

src/main/java/com/memory/
  MemoryClient.java          # 外部调用门面（统一入口）
  MemoryFactory.java         # 工厂类（一行创建 MemoryClient）

  model/                     # Meta Model 层 — 静态数据结构（声明）
    enums/                   # 枚举定义（6 个）
      MemoryTypeKind         #   fact / preference / context / reference
      StorageEngine          #   json / sqlite
      MergeStrategy          #   weighted_score / dedup / concat / direct
      SearchEngineKind       #   keyword / tfidf / embedding
      TriggerEvent           #   memory_created / memory_updated / scheduled
      ActionKind             #   run_decay / purge / generate_embedding / normalize_tags
    constraint/              # 约束模型（3 个）
      FieldConstraint        #   字段约束：type/required/min/max/default/format/enum
      TypeMeta               #   类型元数据：immutable_fields/unique_by/importance_floor/ephemeral
      TagConstraint          #   标签约束：max/allowed_pattern
    type/
      MemoryType             # 记忆类型：kind + description + fields + tags + meta
    decay/                   # 衰减策略（3 个）
      DecayConfig            #   单类型衰减参数
      LifecycleConfig        #   生命周期：stale/archive/purge 阈值
      DecayPolicy            #   策略容器 + fallback 逻辑
    search/                  # 搜索策略（4 个）
      EngineConfig           #   搜索引擎配置 + params 透传
      SearchStep             #   搜索步骤：engine/weight/top_k/fallback
      SearchStrategy         #   搜索策略：steps + merge + limit
      SearchConfig           #   搜索配置容器
    trigger/                 # 触发器（3 个）
      TriggerCondition       #   触发条件
      TriggerAction          #   触发动作
      Trigger                #   触发器：when + then
    globals/                 # 全局设置（2 个）
      StorageConfig          #   存储配置
      Globals                #   全局：default_type/max_memory_size/default_ttl_days
    MetaModel.java           # 顶层容器：version + globals + types + decay + search + triggers

  dsl/
    DSLParser.java           # YAML → MetaModel 解析 + 校验 + 默认值填充
    DSLParseException.java   # 解析异常

  registry/                  # Registry 层 — 组件注册与装配
    ComponentRegistry.java   #   SPI 扫描 / 组件注册 / 生命周期管理
    RegistryException.java   #   注册异常

  runtime/                   # Runtime Context 层 — 运行时状态
    MemoryRuntimeContext.java #  运行时上下文
    RuntimeStateException.java # 运行时异常

  spi/                       # SPI 扩展接口
    MemoryStore.java         #   存储层扩展点
    SearchProvider.java      #   搜索提供者扩展点
    Scheduler.java           #   调度器扩展点
    EventBus.java            #   事件总线扩展点
    ExpressionEngine.java    #   表达式解析扩展点

  engine/                    # 引擎默认实现（SPI 实现）
    manager/                 #   引擎管理层
      MemoryMgr              #   记忆 CRUD + 约束校验 + 元数据封装 + 事件发布
      SearchMgr              #   多步骤搜索编排 + 加权分数合并
      DecayMgr               #   衰减计算 + 生命周期管理（stale/archive/purge）
      TriggerMgr             #   事件/定时/条件驱动触发器
    search/
      KeywordSearchProvider  #   关键词字面匹配（倒排索引）
      TfidfSearchProvider    #   TF-IDF 词频统计
      EmbeddingSearchProvider #  向量语义搜索（余弦相似度已实现，嵌入模型预留）
    store/
      JsonMemoryStore        #   JSON 文件存储 + 内存索引
    scheduler/
      DefaultScheduler       #   Cron 定时调度（ScheduledExecutorService）
    expression/
      DefaultExpressionEngine #  轻量表达式解析器（比较/逻辑运算符）
    event/
      LocalEventBus          #   本地内存事件总线

src/main/resources/
  META-INF/services/         # SPI 自动发现文件（5 个）

src/test/java/com/memory/
  dsl/DSLParserTest.java                   # 单元测试（7 个用例）
  engine/store/JsonMemoryStoreTest.java    # 10 个用例
  engine/manager/MemoryMgrTest.java        # 7 个用例
  engine/manager/SearchMgrTest.java        # 6 个用例
  engine/manager/DecayMgrTest.java         # 6 个用例
  engine/manager/TriggerMgrTest.java       # 4 个用例
  pipeline/PipelineIntegrationTest.java    # 端到端集成测试（9 阶段）
  ... (共 21 个测试文件)
```

## 架构说明

### 分层设计

```
┌─────────────────────────────────────────────────┐
│  memory_dsl.yaml  ← 声明式规则（开发者编辑）    │
├─────────────────────────────────────────────────┤
│  DSL Parser       ← YAML 解析 / 校验 / 填充     │
├─────────────────────────────────────────────────┤
│  Meta Model       ← 静态数据结构（声明）         │
├─────────────────────────────────────────────────┤
│  Registry ★       ← SPI 扫描 / 组件注册 / 装配  │
├─────────────────────────────────────────────────┤
│  Runtime Context  ← 运行时状态 / 缓存 / 指标    │
├─────────────────────────────────────────────────┤
│  Engine           ← 消费 Runtime Context 执行   │
└─────────────────────────────────────────────────┘
```

| 层 | 性质 | 生命周期 | 类比 |
|---|------|----------|------|
| MetaModel | 静态声明 | 启动时解析一次 | 配置清单 |
| Registry | 装配工厂 | 启动时装配一次 | 接线板 |
| Runtime Context | 运行状态 | 整个运行时（可热更新） | 工作台 |
| Engine | 操作逻辑 | 每次请求 | 工人 |

### 数据流

```
新建记忆 → DSLParser 加载 YAML → 构建 MetaModel
         → 校验类型/字段约束    → 写入存储
         → 触发器监听事件       → 执行动作

搜索请求 → 从 MetaModel 获取 SearchStrategy
         → 按 steps 执行引擎   → merge 结果 → 返回

定时任务 → 触发器 schedule 匹配 → 执行衰减计算
         → 更新 importance     → 清理低于阈值的记忆
```

### 模块划分

系统按职责划分为 8 个模块，模块之间单向依赖，不允许循环依赖。

```
┌────────────────────────────────────────────────────────┐
│                      外部调用方                         │
│           (CLI / HTTP API / IDE Plugin)                │
├────────────────────────────────────────────────────────┤
│  Engine 模块                                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │ MemoryMgr│ │SearchMgr │ │DecayMgr  │ │TriggerMgr│  │
│  │ 记忆CRUD │ │搜索编排  │ │衰减计算  │ │调度/事件  │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘  │
│       └────────────┴──────┬─────┴────────────┘          │
│                           ↓                             │
├───────────────────────────┼─────────────────────────────┤
│  Runtime Context                                       │
│  ┌────────────────────────┴───────────────────────┐   │
│  │           MemoryRuntimeContext                  │   │
│  │  - MetaModel 当前实例（AtomicRef，支持热更新）  │   │
│  │  - stores / searchProviders / eventBus          │   │
│  │  - L0 热记忆缓存 / 统计指标                     │   │
│  └────────────────────────────────────────────────┘   │
│                           ↑                           │
├───────────────────────────┼─────────────────────────────┤
│  Registry                                              │
│  ┌────────────────────────┴───────────────────────┐   │
│  │             ComponentRegistry                   │   │
│  │  - ServiceLoader 扫描 META-INF/services         │   │
│  │  - 生命周期管理 (init → start → ready)         │   │
│  │  - 依赖校验（声明了但找不到实现 → 启动报错）    │   │
│  └────────────────────────────────────────────────┘   │
│                           ↑                           │
├───────────────────────────┼─────────────────────────────┤
│  MetaModel + DSL + SPI 实现                            │
│  ┌──────────────┐ ┌──────────────────────────────┐    │
│  │ DSLParser    │ │   KeywordSearch / TfidfSearch │    │
│  │ YAML→Model   │ │   EmbeddingSearch / JsonStore │    │
│  └──────────────┘ │   LocalEventBus / Scheduler   │    │
│                   └──────────────────────────────┘    │
└────────────────────────────────────────────────────────┘
```

**模块依赖方向：**
- Engine → Runtime Context（读写状态）
- Runtime Context → Registry（读取已注册组件）
- Runtime Context → MetaModel（读取规则声明）
- Registry → MetaModel（读取组件声明）
- Registry → SPI 实现（加载具体类）
- DSL → MetaModel（构建）
- 各模块之间不允许反向依赖

### 记忆分层

记忆按生命周期和重要性分为 4 层：

```
┌──────────────────────────────────────────────────┐
│ L0 — 热记忆 (Hot)                                │
│  importance >= 0.7  且 最近 24h 内有访问          │
│  策略：内存缓存，搜索优先返回，不衰减             │
├──────────────────────────────────────────────────┤
│ L1 — 温记忆 (Warm)                               │
│  importance >= 0.4  且 最近 7 天内有访问          │
│  策略：磁盘存储，正常搜索参与，按公式衰减         │
├──────────────────────────────────────────────────┤
│ L2 — 冷记忆 (Cold)                               │
│  importance >= 0.1  但 超过 7 天未访问            │
│  策略：磁盘存储，搜索降权，加速衰减               │
├──────────────────────────────────────────────────┤
│ L3 — 归档 (Archive)                              │
│  importance < 0.1  或 超过 stale_after_days       │
│  策略：移入归档文件，搜索不返回，可批量清理       │
└──────────────────────────────────────────────────┘
```

| 层级 | importance 范围 | 访问时效 | DSL 控制字段 |
|------|-----------------|----------|-------------|
| L0 Hot | >= 0.7 | <= 24h | `hot_threshold` / `hot_window_hours` |
| L1 Warm | >= 0.4 | <= 7d | `decay.lifecycle.stale_after_days` |
| L2 Cold | >= 0.1 | > 7d | `decay.lifecycle.archive_after_days` |
| L3 Archive | < 0.1 或超时 | 超过阈值 | `decay.lifecycle.purge_when` |

**分层与类型的映射关系：**
- `preference` — 通常在 L0/L1 之间徘徊（importance_floor=0.3 保底不掉入 L3）
- `fact` — 创建后进入 L1，频繁访问升至 L0，长期不访问缓慢降至 L2
- `context` — 创建后快速进入 L0/L1，衰减快，容易落入 L3
- `reference` — 创建后进入 L1，几乎不升 L0（静态资料），缓慢降至 L2

### 运行时流程

<details>
<summary>阶段一：启动初始化</summary>

1. 加载 `memory_dsl.yaml` → `DSLParser.parse()` 解析 → 校验 → 填充默认值 → 构建 MetaModel
2. Registry 组件装配：SPI 扫描 → 注册搜索提供者/存储引擎/事件总线 → `init() → start() → ready()`
3. 创建 Runtime Context（持有 MetaModel 引用、组件引用、L0 缓存、指标计数器）
4. 预热搜索引擎（embedding 模型下载、为已有记忆生成索引）
</details>

<details>
<summary>阶段二：记忆 CRUD</summary>

- **创建**：校验必填字段/类型/标签/唯一性 → importance=1.0 → 写入存储 → 发布 `MEMORY_CREATED` 事件
- **读取**：加载 → 更新 `last_accessed` → `importance += access_gain` → 回写
- **更新**：校验 `immutable_fields` → 校验新值 → 写入 → 发布 `MEMORY_UPDATED` 事件
- **删除**：物理删除 + 更新索引
</details>

<details>
<summary>阶段三：搜索流程</summary>

1. 从 Runtime Context 获取 SearchStrategy
2. 按 `type_filters` 过滤范围
3. 执行策略 steps：依次调用 SearchProvider（fallback=true 时前序有结果则跳过）
4. 按 merge 策略合并结果
5. 按 limit 截取 → 更新 `last_accessed` 和 importance
</details>

<details>
<summary>阶段四：衰减与生命周期</summary>

- **定时衰减**：遍历记忆 → `importance *= daily_decay ^ delta_days` → 应用 importance_floor
- **生命周期检查**：stale（超时标记）→ archive（超时归档）→ purge（低重要性/超时删除）
- **溢出清理**：总数超 `max_memory_size` → 按 importance ASC 删除最低的 N 条
</details>

<details>
<summary>阶段五：热更新</summary>

检测 YAML 变更 → 重新解析 → 校验兼容性 → 原子替换 Runtime Context 中的 MetaModel → 新旧组件切换 → 无需重启
</details>

<details>
<summary>阶段六：优雅关闭</summary>

停止调度器/事件监听 → 执行完整衰减 → 刷盘缓存 → 调用所有组件 `shutdown()` → 关闭存储连接
</details>

### DSL 模块

| 模块 | 职责 |
|------|------|
| `globals` | 全局：默认类型、容量上限、TTL、存储配置 |
| `types` | 记忆类型：字段约束、标签规则、去重、重要性下限 |
| `decay` | 衰减策略：公式参数、类型覆盖、生命周期钩子 |
| `search` | 搜索策略：多引擎组合、权重、合并方式 |
| `triggers` | 触发器：定时/条件/事件驱动 |

### SPI 扩展点

```
com.memory.spi.MemoryStore                          — 存储层扩展点
  └── JsonMemoryStore                               — 已实现（JSON 文件 + 内存索引）
com.memory.spi.SearchProvider                       — 搜索提供者扩展点
  ├── KeywordSearchProvider                         — 已实现（倒排索引）
  ├── TfidfSearchProvider                           — 已实现（TF-IDF 统计）
  └── EmbeddingSearchProvider                       — 已实现（余弦相似度，嵌入模型预留）
com.memory.spi.EventBus                             — 事件总线扩展点
  └── LocalEventBus                                 — 已实现（本地内存）
com.memory.spi.Scheduler                            — 调度器扩展点
  └── DefaultScheduler                              — 已实现（Cron + ScheduledExecutorService）
com.memory.spi.ExpressionEngine                     — 表达式解析扩展点
  └── DefaultExpressionEngine                       — 已实现（比较/逻辑运算符，递归下降解析）
```

## 外部调用

作为库引入项目后，通过 `MemoryClient` 门面调用，无需关心内部组件装配。

### 一行初始化

```java
try (MemoryClient client = MemoryFactory.create(Path.of("memory_dsl.yaml"))) {
    // ready
}
```

### 记忆 CRUD

```java
try (MemoryClient client = MemoryFactory.create(Path.of("memory_dsl.yaml"))) {
    // 创建
    String id = client.create("fact", "{\"content\":\"Java 17 是 LTS 版本\"}", Set.of("java", "version"));

    // 读取
    String data = client.read(id);

    // 更新
    client.update(id, "{\"content\":\"Java 17.0.17 已发布\"}");

    // 删除
    boolean deleted = client.delete(id);

    // 列表
    int count = client.count();
    Set<String> allIds = client.listAll();
}
```

### 搜索

```java
try (MemoryClient client = MemoryFactory.create(Path.of("memory_dsl.yaml"))) {
    var results = client.search("java");            // 默认策略
    var results2 = client.search("spring", "default"); // 指定策略
}
```

### 衰减与生命周期

```java
try (MemoryClient client = MemoryFactory.create(Path.of("memory_dsl.yaml"))) {
    var summary = client.runDecay();
    // summary.total() / summary.purged()

    var status = client.checkLifecycle(id);
    // status.status() → "active" / "stale" / "archive" / "purged"
}
```

### 热更新

```java
MetaModel newModel = new DSLParser().parse(Path.of("memory_dsl_v2.yaml"));
client.updateModel(newModel);  // 原子替换，无需重启
```

### Maven 依赖

```xml
<dependency>
    <groupId>com.memory</groupId>
    <artifactId>memory-system</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### API 示例（内部使用）

```java
// 解析 DSL
DSLParser parser = new DSLParser();
MetaModel model = parser.parse(Path.of("memory_dsl.yaml"));

// 获取类型定义
Optional<MemoryType> factType = model.getType("fact");

// 获取衰减配置（含 fallback）
DecayConfig factDecay = model.getDecay().getConfigForType("fact");
// fact → dailyDecay=0.95（type_override），unknown → 0.92（fallback）

// 获取搜索策略
SearchStrategy strategy = model.getSearch().getDefaultStrategy();
// 输出: embedding weight=0.7 topK=20, keyword weight=0.3 topK=10

// 遍历触发器
for (Trigger trigger : model.getTriggers()) {
    System.out.println(trigger.getName() + " → " + trigger.getWhen().getSchedule());
}
```

## 常用命令

```bash
# 设置 Java 17 环境
export JAVA_HOME=G:/env/jdk-17.0.17
export PATH=$JAVA_HOME/bin:$PATH

mvn compile              # 编译
mvn test                 # 运行测试
mvn test -Dtest=DSLParserTest   # 运行单个测试类
mvn clean compile        # 清理 + 编译
mvn package              # 打包
mvn dependency:tree      # 查看依赖树
```
