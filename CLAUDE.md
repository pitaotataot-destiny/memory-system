# Memory System — 声明式 DSL + Meta Model 记忆引擎

## 项目介绍

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
CLAUDE.md                    # 本项目文档

src/main/java/com/memory/
  MemoryClient.java          # 外部调用门面（统一入口）
  MemoryFactory.java         # 工厂类（一行创建 MemoryClient）

src/main/java/com/memory/
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
                              #   - MetaModel 原子引用（热更新）
                              #   - 快捷组件引用（stores / searchProviders / eventBus）
                              #   - L0 热记忆缓存
                              #   - 统计指标
    RuntimeStateException.java # 运行时异常

  spi/                       # SPI 扩展接口
    MemoryStore.java         #   存储层扩展点（6 方法：save/load/delete/listAll/init/shutdown）
    SearchProvider.java      #   搜索提供者扩展点（4 方法：name/init/index/search）
    Scheduler.java           #   调度器扩展点（4 方法：name/schedule/cancel/shutdown）
    EventBus.java            #   事件总线扩展点（3 方法：subscribe/publish/shutdown）
    ExpressionEngine.java    #   表达式解析扩展点（2 方法：evaluate/getName）

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
    com.memory.spi.MemoryStore
    com.memory.spi.SearchProvider
    com.memory.spi.EventBus
    com.memory.spi.Scheduler
    com.memory.spi.ExpressionEngine

src/test/java/com/memory/
  dsl/
    DSLParserTest.java         # 单元测试（7 个用例）
  engine/
    store/JsonMemoryStoreTest.java          # 10 个用例
    scheduler/DefaultSchedulerTest.java     # 7 个用例
    expression/DefaultExpressionEngineTest  # 14 个用例
    manager/MemoryMgrTest.java              # 7 个用例
    manager/SearchMgrTest.java              # 6 个用例
    manager/DecayMgrTest.java               # 6 个用例
    manager/TriggerMgrTest.java             # 4 个用例
    event/LocalEventBusTest.java            # 6 个用例
    search/EmbeddingSearchProviderTest.java # 4 个用例
    search/KeywordSearchProviderTest.java   # 6 个用例
    search/TfidfSearchProviderTest.java     # 5 个用例
  registry/
    ComponentRegistryTest.java              # 6 个用例
  runtime/
    MemoryRuntimeContextTest.java           # 10 个用例
  pipeline/
    PipelineIntegrationTest.java            # 端到端集成测试（9 阶段）
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

**各层职责：**

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
│  Engine 模块（已实现）                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │ MemoryMgr│ │SearchMgr │ │DecayMgr  │ │TriggerMgr│  │
│  │ 记忆CRUD │ │搜索编排  │ │衰减计算  │ │调度/事件  │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘  │
│       └────────────┴──────┬─────┴────────────┘          │
│                           ↓                             │
├───────────────────────────┼─────────────────────────────┤
│  Runtime Context（已实现）│                            │
│  ┌────────────────────────┴───────────────────────┐   │
│  │           MemoryRuntimeContext                  │   │
│  │  - MetaModel 当前实例（AtomicRef，支持热更新）  │   │
│  │  - stores: Map<String, MemoryStore>            │   │
│  │  - searchProviders: Map<String, SearchProvider>│   │
│  │    ├── KeywordSearchProvider                   │   │
│  │    ├── TfidfSearchProvider                     │   │
│  │    └── EmbeddingSearchProvider                 │   │
│  │  - eventBus: EventBus (LocalEventBus)          │   │
│  │  - L0 热记忆缓存                                │   │
│  │  - 请求上下文 / 统计 / 指标                     │   │
│  └────────────────────────────────────────────────┘   │
│                           ↑                           │
├───────────────────────────┼─────────────────────────────┤
│  Registry（已实现）         │                            │
│  ┌────────────────────────┴───────────────────────┐   │
│  │             ComponentRegistry                   │   │
│  │  - ServiceLoader 扫描 META-INF/services         │   │
│  │  - 按 MetaModel 声明注册组件                    │   │
│  │  - 生命周期管理 (init → start → ready)         │   │
│  │  - 依赖校验（声明了但找不到实现 → 启动报错）    │   │
│  └────────────────────────────────────────────────┘   │
│                           ↑                           │
├───────────────────────────┼─────────────────────────────┤
│  MetaModel 模块（已实现） │                            │
│  ┌────────────────────────┴───────────────────────┐   │
│  │              MetaModel (顶层容器)               │   │
│  │  globals / types / decay / search / triggers   │   │
│  └────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────┤
│  DSL 模块（已实现）                                     │
│  ┌──────────────┐  ┌──────────────┐                    │
│  │ DSLParser    │  │ DSL Validator│                    │
│  │ YAML→Model   │  │ 交叉引用校验 │                    │
│  └──────────────┘  └──────────────┘                    │
├────────────────────────────────────────────────────────┤
│  Storage 模块（已实现）                                  │
│  ┌──────────────┐  ┌──────────────┐                    │
│  │ JsonStore    │  │ SqliteStore  │ (预留)              │
│  │ 文件读写      │  │ SQL 操作     │                    │
│  └──────────────┘  └──────────────┘                    │
├────────────────────────────────────────────────────────┤
│  Search Provider 模块（已实现）                           │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐  │
│  │ KeywordSearch│ │ TfidfSearch  │ │ EmbeddingSearch│  │
│  │  Provider    │ │  Provider    │ │   Provider     │  │
│  │ 倒排索引      │ │ TF-IDF统计   │ │ 向量语义(已实现) │  │
│  └──────────────┘ └──────────────┘ └────────────────┘  │
├────────────────────────────────────────────────────────┤
│  Event Bus 模块（已实现）                               │
│  ┌──────────────┐                                      │
│  │ LocalEventBus│   ← 本地内存事件总线                  │
│  │ 订阅/发布    │                                      │
│  └──────────────┘                                      │
└────────────────────────────────────────────────────────┘
```

**模块依赖方向：**
- Engine → Runtime Context（读写状态）
- Runtime Context → Registry（读取已注册组件）
- Runtime Context → MetaModel（读取规则声明）
- Runtime Context → Engine 实现（直接调用 SearchProvider / MemoryStore / EventBus）
- Registry → MetaModel（读取组件声明）
- Registry → SPI 实现（加载具体类）
- Engine 实现 → SPI 接口（实现 SearchProvider / MemoryStore / EventBus）
- DSL → MetaModel（构建）
- 各模块之间不允许反向依赖

### 记忆分层

记忆按生命周期和重要性分为 4 层，每层对应不同的衰减策略和存储策略。

```
┌──────────────────────────────────────────────────┐
│ L0 — 热记忆 (Hot)                                │
│  importance >= 0.7  且 最近 24h 内有访问          │
│  策略：内存缓存，搜索优先返回，不衰减             │
│  示例：当前项目上下文、用户刚强调的偏好           │
├──────────────────────────────────────────────────┤
│ L1 — 温记忆 (Warm)                               │
│  importance >= 0.4  且 最近 7 天内有访问          │
│  策略：磁盘存储，正常搜索参与，按公式衰减         │
│  示例：已确认的事实知识、稳定的用户偏好           │
├──────────────────────────────────────────────────┤
│ L2 — 冷记忆 (Cold)                               │
│  importance >= 0.1  但 超过 7 天未访问            │
│  策略：磁盘存储，搜索降权，加速衰减               │
│  示例：旧项目上下文、过期参考资料                 │
├──────────────────────────────────────────────────┤
│ L3 — 归档 (Archive)                              │
│  importance < 0.1  或 超过 stale_after_days       │
│  策略：移入归档文件，搜索不返回，可批量清理       │
│  示例：已完成的旧会话、废弃的配置                 │
└──────────────────────────────────────────────────┘
```

**分层判定规则（由 DSL 驱动）：**

| 层级 | importance 范围 | 访问时效 | DSL 控制字段 |
|------|-----------------|----------|-------------|
| L0 Hot | >= 0.7 | <= 24h | 预留 `hot_threshold` / `hot_window_hours` |
| L1 Warm | >= 0.4 | <= 7d | `decay.lifecycle.stale_after_days` |
| L2 Cold | >= 0.1 | > 7d | `decay.lifecycle.archive_after_days` |
| L3 Archive | < 0.1 或超时 | 超过阈值 | `decay.lifecycle.purge_when` |

**分层与类型的映射关系：**
- `preference` — 通常在 L0/L1 之间徘徊（importance_floor=0.3 保底不掉入 L3）
- `fact` — 创建后进入 L1，频繁访问升至 L0，长期不访问缓慢降至 L2
- `context` — 创建后快速进入 L0/L1，衰减快，容易落入 L3
- `reference` — 创建后进入 L1，几乎不升 L0（静态资料），缓慢降至 L2

### 运行时流程

```
═══════════════════════════════════════════════════════
阶段一：启动初始化
═══════════════════════════════════════════════════════

1. 加载 memory_dsl.yaml
   → DSLParser.parse() 解析 YAML
   → 校验：版本 / 类型引用 / 引擎引用 / 必填字段
   → 填充默认值 → 构建 MetaModel 对象

2. Registry 组件装配
   → SPI 扫描：从 META-INF/services 加载所有实现
   → 按 MetaModel.search.engines 声明注册搜索提供者
     - keyword  → KeywordSearchProvider
     - tfidf    → TfidfSearchProvider
     - embedding → EmbeddingSearchProvider
   → 按 MetaModel.globals.storage 注册存储引擎
     - json     → JsonStore
     - sqlite   → SqliteStore
   → 注册事件总线
     - local    → LocalEventBus
   → 生命周期：init() → start() → ready()
   → 依赖校验：DSL 声明了但 SPI 找不到 → 启动失败

3. 创建 Runtime Context
   → 持有当前 MetaModel 实例引用
   → 持有 Registry 中已就绪的组件实例引用
   → 初始化 L0 热记忆缓存
   → 初始化统计指标计数器

4. 预热搜索引擎
   → 遍历启用的引擎
   → embedding 模型下载到 cache_dir
   → 为已有记忆生成索引（向量 / tfidf 矩阵）


═══════════════════════════════════════════════════════
阶段二：记忆 CRUD
═══════════════════════════════════════════════════════

【创建记忆】
1. 从 Runtime Context 获取 MetaModel.types 约束规则
2. 校验：
   - 必填字段是否存在
   - 字段类型/范围是否符合 FieldConstraint
   - tags 数量是否超过 TagConstraint.max
   - unique_by 字段是否与已有记忆冲突
3. 初始化重要性：importance = 1.0（新建满分）
4. 通过 Runtime Context 获取 MemoryStore 写入
5. 触发事件 MEMORY_CREATED → 通过 EventBus.publish() → 匹配 triggers → 执行动作

【读取记忆】
1. 通过 Runtime Context 获取 MemoryStore 加载
2. 更新 last_accessed 时间戳
3. 增加 importance += access_gain
4. 更新存储

【更新记忆】
1. 校验 immutable_fields 是否被修改（拒绝）
2. 校验新值是否符合 FieldConstraint
3. 通过 MemoryStore 更新
4. 触发事件 MEMORY_UPDATED → 通过 EventBus.publish() → 匹配 triggers → 执行动作

【删除记忆】
1. 通过 MemoryStore 物理删除
2. 更新索引


═══════════════════════════════════════════════════════
阶段三：搜索流程
═══════════════════════════════════════════════════════

1. 从 Runtime Context 获取 SearchStrategy
2. 按 type_filters 过滤搜索范围
3. 执行策略的 steps：
   a. 通过 Runtime Context 获取对应 SearchProvider 实例
   b. 依次调用搜索提供者
   c. 如果 step.fallback=true 且前序步骤有结果，跳过此步
   d. 每步返回 top_k 个结果 + 原始分数
4. 按 merge 策略合并结果
5. 按 limit 截取最终结果
6. 返回前更新每条记忆的 last_accessed 和 importance


═══════════════════════════════════════════════════════
阶段四：衰减与生命周期
═══════════════════════════════════════════════════════

【定时衰减 — 由 trigger schedule 触发】
1. 遍历所有记忆
2. 计算距上次访问的天数 delta_days
3. 从 Runtime Context 获取该类型的 DecayConfig
4. 计算新重要性：importance = importance × (daily_decay ^ delta_days)
5. 应用 importance_floor
6. 更新存储

【生命周期检查 — 每次衰减后执行】
1. stale 检查：last_accessed > stale_after_days → 标记 stale
2. archive 检查：last_accessed > archive_after_days → 移入归档
3. purge 检查：
   - importance < purge_when.importance_below → 删除
   - last_accessed > purge_when.or_stale_days → 删除

【溢出清理 — 由 trigger condition 触发】
1. 检查记忆总数是否超过 globals.max_memory_size
2. 按 importance ASC 排序
3. 删除最低的 limit 条记忆


═══════════════════════════════════════════════════════
阶段五：热更新（支持运行时替换 DSL）
═══════════════════════════════════════════════════════

1. 检测到 memory_dsl.yaml 变更
2. DSLParser 重新解析 → 生成新 MetaModel
3. 校验新 MetaModel 与运行状态的兼容性
4. 替换 Runtime Context 中的 MetaModel 引用（原子操作）
5. Registry 根据新声明重新装配变化的组件
6. 旧组件优雅关闭，新组件接管
7. 无需重启服务


═══════════════════════════════════════════════════════
阶段六：优雅关闭
═══════════════════════════════════════════════════════

1. 停止所有调度器和事件监听
2. 执行一次完整衰减计算
3. 刷盘所有缓存数据
4. Registry 调用所有组件的 shutdown()
5. 关闭存储引擎连接
```

### DSL 模块

| 模块 | 职责 |
|------|------|
| `globals` | 全局：默认类型、容量上限、TTL、存储配置 |
| `types` | 记忆类型：字段约束、标签规则、去重、重要性下限 |
| `decay` | 衰减策略：公式参数、类型覆盖、生命周期钩子 |
| `search` | 搜索策略：多引擎组合、权重、合并方式 |
| `triggers` | 触发器：定时/条件/事件驱动 |

## 编码规范

### 命名

- **类名**: PascalCase（`MetaModel`, `DSLParser`）
- **方法名**: camelCase（`getConfigForType`, `parseString`）
- **常量**: UPPER_SNAKE_CASE（`SUPPORTED_VERSIONS`）
- **包名**: `com.memory.<module>` 全小写
- **枚举值**: UPPER_SNAKE_CASE（`MEMORY_CREATED`, `WEIGHTED_SCORE`）

### 代码风格

- 4 空格缩进，不用 tab
- 左大括号与代码同行
- 方法长度不超过 50 行，超长方法拆私有方法
- 每个 public 方法必须有 Javadoc（`@param`, `@return`）
- 私有方法用单行注释说明用途
- 不用 `System.out.println`，日志用 slf4j（尚未引入，暂用异常抛出）
- 不用 `@Data`（Lombok），手动写 getter/setter

### 文件组织

- 一个 Java 文件一个 public class
- enum 放在 `model.enums` 包
- model 类按功能子包分组（`model.decay`, `model.search` 等）
- 测试文件与源文件同名，放 `src/test/java` 对应包

### AI 编码规则

#### 1. 禁止硬编码

- **禁止在代码中写类型名/引擎名/路径/阈值** — 必须从 `MetaModel` 或 DSL 配置中获取
- **禁止写死魔法数字** — 如 `0.92`、`5000`、`14` 等必须来自 DSL 字段
- **禁止硬编码字符串** — 如 `"fact"`、`"embedding"` 等必须通过 `MemoryTypeKind`、`SearchEngineKind` 枚举获取
- **正确做法**：所有可变值从 `MetaModel` 读取，常量通过枚举或 `globals` 声明

```java
// 禁止
if (type.equals("fact")) { ... }

// 正确
if (type.getKind() == MemoryTypeKind.FACT) { ... }

// 禁止
double decay = 0.92;

// 正确
double decay = model.getDecay().getConfigForType(type.getKind().getValue()).getDailyDecay();
```

#### 2. 优先组合而非继承

- **使用接口 + 组合**，不用抽象类继承链
- **策略模式替代 if-else** — 如搜索引擎用 `SearchEngine` 接口 + 多个实现类，由引擎名动态路由
- **职责链/管道模式处理流程** — 如记忆创建走 `ValidationPipeline`，由多个 `Validator` 组合
- **用 `final` 类防止意外继承** — 非设计为扩展的类标记 final

```java
// 禁止：深层继承链
abstract class AbstractMemoryStore { }
class JsonMemoryStore extends AbstractMemoryStore { }

// 正确：接口 + 组合
interface MemoryStore { void save(Memory m); Memory load(String id); }
class JsonMemoryStore implements MemoryStore {
    private final StorageConfig config;  // 组合配置
    // ...
}
```

#### 3. 所有组件必须可替换

- **每个核心组件必须定义接口** — `MemoryStore`, `SearchEngine`, `Scheduler`, `EventBus`
- **组件通过工厂/SPI 实例化** — 不直接 `new` 具体实现，用工厂方法或 SPI 加载
- **组件构造器接收依赖** — 不用静态方法获取全局单例，依赖通过构造器注入
- **存储/搜索/调度三大组件必须可热替换** — 切换 JSON 存储为 SQLite 不需要改引擎代码

```java
// 禁止：直接 new 具体实现
JsonMemoryStore store = new JsonMemoryStore();

// 正确：通过工厂/SPI 获取
MemoryStore store = ComponentFactory.create(MemoryStore.class, model.getGlobals().getStorage());
```

#### 4. 必须支持 SPI 扩展

- **所有可替换组件必须声明 SPI 接口** — 在 `com.memory.spi` 包下定义接口
- **接口方法必须小而精** — 遵循接口隔离原则，不设计胖接口
- **提供 `@SPI` 注解标记扩展点** — 标明接口名称、默认实现、扩展示例
- **META-INF/services 注册默认实现** — Java SPI 标准机制，用户只需加 jar + 改配置即可替换

```
// 核心 SPI 接口 + 默认实现
com.memory.spi.MemoryStore                          — 存储层扩展点
  └── JsonMemoryStore                               — ✅ 已实现（JSON 文件 + 内存索引）
com.memory.spi.SearchProvider                       — 搜索提供者扩展点
  ├── KeywordSearchProvider                         — ✅ 已实现（倒排索引）
  ├── TfidfSearchProvider                           — ✅ 已实现（TF-IDF 统计）
  └── EmbeddingSearchProvider                       — ✅ 已实现（余弦相似度，嵌入模型预留）
com.memory.spi.EventBus                             — 事件总线扩展点
  └── LocalEventBus                                 — ✅ 已实现（本地内存）
com.memory.spi.Scheduler                            — 调度器扩展点
  └── DefaultScheduler                              — ✅ 已实现（Cron + ScheduledExecutorService）
com.memory.spi.ExpressionEngine                     — 表达式解析扩展点
  └── DefaultExpressionEngine                       — ✅ 已实现（比较/逻辑运算符，递归下降解析）
```

## 开发规则

### DSL 修改规则

1. **DSL 变更必须先改 YAML** — 先写规则，再改 Java 解析代码
2. **新增字段必须有默认值** — `DSLParser` 中对可选字段提供 fallback
3. **新增类型必须在 `type_overrides` 同步更新衰减参数** — 避免使用默认衰减
4. **删除类型时必须同步删除对应的 type_override 和 trigger 引用**

### Meta Model 开发规则

1. **引擎只能依赖 MetaModel 接口** — 不能直接读 YAML 或 Raw Map
2. **新增模块必须在 `MetaModel` 中注册** — 顶层容器必须包含所有子模块
3. **fallback 逻辑放在策略层** — 如 `DecayPolicy.getConfigForType()`，不放在引擎
4. **校验放在 DSLParser 中** — MetaModel 本身不包含校验逻辑，假设数据已校验

### 测试规则

1. **新增解析逻辑必须有测试** — `DSLParserTest` 覆盖所有 YAML 字段
2. **测试用真实 YAML 文件** — 不用 mock，直接解析 `memory_dsl.yaml`
3. **边界值单独测试** — 如 fallback 行为、空列表、版本不兼容

## 禁止事项

- **禁止引擎直接读取 YAML** — 所有规则必须通过 MetaModel 传递
- **禁止在 MetaModel 中写业务逻辑** — model 只是数据容器，不含计算方法
- **禁止硬编码类型名/引擎名/路径/阈值** — 必须通过枚举、DSL 配置或 MetaModel 获取
- **禁止写死魔法数字** — 所有阈值/系数/上限从 DSL 字段读取
- **禁止在解析器中吞异常** — `DSLParseException` 必须向上抛，包含具体字段名
- **禁止在 YAML 中使用未声明的引擎引用** — parser 会校验
- **禁止使用 `null` 作为 required 字段** — parser 校验必须拒绝
- **禁止修改已冻结的 DSL 结构而不递增 version**
- **禁止直接 new 具体实现类** — 用工厂方法或 SPI 加载组件
- **禁止使用抽象类继承链设计核心组件** — 用接口 + 组合
- **禁止设计胖接口** — SPI 接口方法不超过 5 个，遵循接口隔离

## AI 行为约束

- **改规则先改 YAML** — 接到「加新类型/改衰减/加搜索」需求，先改 `memory_dsl.yaml`，再改 Java 代码
- **改代码先确认影响** — 修改 MetaModel 字段时，同步更新 `DSLParser` 和测试
- **不要跳过测试** — 任何代码改动后必须 `mvn test` 验证通过
- **不要自行添加依赖** — 引入新 jar 包前必须询问用户
- **不要删除已存在的 DSL 字段** — 除非用户明确要求，只允许新增
- **保持注释完整** — 新增代码必须有中文注释，解释「为什么」而非「做什么」
- **不确定时先问** — 遇到架构选择（如表达式引擎选型、存储方案）先给方案让用户选
- **写新组件先定义接口** — 不要直接写实现类，先设计 SPI 接口，再给默认实现
- **优先组合** — 接到需要复用的功能需求，用组合（委托/管道/策略），不要写继承链
- **组件必须可替换** — 新建的 Store/Engine/Scheduler 必须通过工厂创建，不能写死 new

## 示例代码

### 1. 解析 DSL 文件

```java
DSLParser parser = new DSLParser();
MetaModel model = parser.parse(Path.of("memory_dsl.yaml"));
```

### 2. 获取记忆类型定义

```java
Optional<MemoryType> factType = model.getType("fact");
factType.ifPresent(t -> {
    System.out.println(t.getDescription());  // "事实知识"
    System.out.println(t.getFields().containsKey("content"));  // true
    System.out.println(t.getMeta().getImportanceFloor());  // 0.1
});
```

### 3. 获取衰减配置（含 fallback）

```java
DecayConfig factDecay = model.getDecay().getConfigForType("fact");
System.out.println(factDecay.getDailyDecay());  // 0.95（type_override）

DecayConfig unknownDecay = model.getDecay().getConfigForType("unknown");
System.out.println(unknownDecay.getDailyDecay());  // 0.92（fallback 到 default）
```

### 4. 获取默认搜索策略

```java
SearchStrategy strategy = model.getSearch().getDefaultStrategy();
for (SearchStep step : strategy.getSteps()) {
    System.out.println(step.getEngine() + " weight=" + step.getWeight() + " topK=" + step.getTopK());
}
// 输出:
//   embedding weight=0.7 topK=20
//   keyword weight=0.3 topK=10
```

### 5. 遍历触发器

```java
for (Trigger trigger : model.getTriggers()) {
    System.out.println(trigger.getName());
    System.out.println("  when: " + trigger.getWhen().getSchedule());
    System.out.println("  then: " + trigger.getThen().getAction());
}
```

### 6. 解析 YAML 字符串

```java
String yaml = """
    version: "1.0"
    globals:
      default_type: fact
      max_memory_size: 100
      default_ttl_days: 7
      storage:
        engine: json
        path: "./data"
    types:
      fact:
        description: "事实"
        fields:
          content: { type: string, required: true }
        tags:
          max: 5
        meta:
          unique_by: ["content"]
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
    triggers: []
    """;

MetaModel model = new DSLParser().parseString(yaml);
```

## 常用命令

```bash
# 设置 Java 17 环境
export JAVA_HOME=G:/env/jdk-17.0.17
export PATH=$JAVA_HOME/bin:$PATH

# 编译
mvn compile

# 运行测试
mvn test

# 运行单个测试类
mvn test -Dtest=DSLParserTest

# 清理 + 编译
mvn clean compile

# 打包
mvn package

# 安装依赖
mvn install

# 查看依赖树
mvn dependency:tree
```

## 外部调用

作为库引入项目后，通过 `MemoryClient` 门面调用，无需关心内部组件装配。

### 1. 一行初始化（从 DSL 文件）

```java
// 从 YAML 文件启动
try (MemoryClient client = MemoryFactory.create(Path.of("memory_dsl.yaml"))) {
    // ready
}
```

### 2. 记忆 CRUD

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

### 3. 搜索

```java
try (MemoryClient client = MemoryFactory.create(Path.of("memory_dsl.yaml"))) {
    // 使用默认搜索策略
    var results = client.search("java");

    // 使用指定策略
    var results2 = client.search("spring", "default");
}
```

### 4. 衰减与生命周期

```java
try (MemoryClient client = MemoryFactory.create(Path.of("memory_dsl.yaml"))) {
    // 手动触发衰减计算
    var summary = client.runDecay();
    System.out.println("total=" + summary.total() + ", purged=" + summary.purged());

    // 检查单条记忆的生命周期状态
    var status = client.checkLifecycle(id);
    // status.status() → "active" / "stale" / "archive" / "purged"
}
```

### 5. 热更新

```java
// 重新解析 YAML
MetaModel newModel = new DSLParser().parse(Path.of("memory_dsl_v2.yaml"));

// 原子替换规则，无需重启
client.updateModel(newModel);
```

### 6. Maven 依赖

```xml
<dependency>
    <groupId>com.memory</groupId>
    <artifactId>memory-system</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```
