# Memory System — 声明式 DSL + Meta Model 记忆引擎

## 项目介绍

基于声明式 DSL 的记忆管理系统。行为规则（类型定义、衰减策略、搜索策略、触发器）用 `memory_dsl.yaml` 描述，运行时解析为 MetaModel，引擎只消费模型对象，不直接读 YAML。

**核心目标：改规则不改代码。** 新增类型、调整衰减、切换搜索策略，只改 YAML，不改 Java。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17+ | 主语言 |
| Maven | 4.0.0 | 构建工具 |
| SnakeYAML | 2.3 | YAML 解析 |
| JUnit Jupiter | 5.11 | 单元测试 |

## 架构约束

### 分层（自上而下）

| 层 | 性质 | 生命周期 | 类比 |
|---|------|----------|------|
| MetaModel | 静态声明 | 启动时解析一次 | 配置清单 |
| Registry | 装配工厂 | 启动时装配一次 | 接线板 |
| Runtime Context | 运行状态 | 整个运行时（可热更新） | 工作台 |
| Engine | 操作逻辑 | 每次请求 | 工人 |

### 依赖方向（单向，禁止反向）

- Engine → Runtime Context → Registry → MetaModel
- Engine 实现 → SPI 接口（实现 SearchProvider / MemoryStore / EventBus）
- DSL → MetaModel（构建）
- Runtime Context → Engine 实现（直接调用）

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
- 不用 `System.out.println`，日志用 slf4j
- 不用 `@Data`（Lombok），手动写 getter/setter

### 文件组织

- 一个 Java 文件一个 public class
- enum 放在 `model.enums` 包
- model 类按功能子包分组（`model.decay`, `model.search` 等）
- 测试文件与源文件同名，放 `src/test/java` 对应包

## AI 编码规则（4 条硬性规则）

### 1. 禁止硬编码

- 类型名/引擎名/路径/阈值必须从 `MetaModel` 或 DSL 配置获取
- 魔法数字（`0.92`、`5000`、`14`）必须来自 DSL 字段
- 字符串（`"fact"`、`"embedding"`）必须通过枚举获取
- 正确做法：所有可变值从 `MetaModel` 读取，常量通过枚举或 `globals` 声明

### 2. 优先组合而非继承

- 使用接口 + 组合，不用抽象类继承链
- 策略模式替代 if-else，多引擎由引擎名动态路由
- 职责链/管道模式处理流程
- 非设计为扩展的类标记 `final`

### 3. 所有组件必须可替换

- 每个核心组件必须定义接口（`MemoryStore`, `SearchProvider`, `Scheduler`, `EventBus`）
- 通过工厂/SPI 实例化，不直接 `new` 具体实现
- 依赖通过构造器注入，不用静态方法获取全局单例

### 4. 必须支持 SPI 扩展

- 可替换组件声明在 `com.memory.spi` 包
- 接口方法 ≤5 个，遵循接口隔离
- 默认实现注册在 `META-INF/services`

## 开发规则

### DSL 修改

1. 先改 YAML，再改 Java 解析代码
2. 新增字段必须在 `DSLParser` 中提供默认值
3. 新增类型必须在 `type_overrides` 同步更新衰减参数
4. 删除类型时同步删除对应的 `type_override` 和 trigger 引用

### Meta Model

1. 引擎只能依赖 MetaModel，不能直接读 YAML 或 Raw Map
2. 新增模块必须在 `MetaModel` 顶层容器注册
3. fallback 逻辑放在策略层（如 `DecayPolicy.getConfigForType()`），不放在引擎
4. 校验放在 DSLParser 中，MetaModel 假设数据已校验

### 测试

1. 新增解析逻辑必须有测试
2. 测试用真实 YAML 文件，不用 mock
3. 边界值单独测试（fallback 行为、空列表、版本不兼容）

## 禁止事项

- 引擎直接读取 YAML
- MetaModel 中写业务逻辑
- 硬编码类型名/引擎名/路径/阈值/魔法数字
- 解析器中吞异常（`DSLParseException` 必须向上抛，包含字段名）
- YAML 中使用未声明的引擎引用
- `null` 作为 required 字段
- 修改已冻结的 DSL 结构而不递增 version
- 直接 `new` 具体实现类
- 抽象类继承链设计核心组件
- 胖接口（SPI 方法 > 5 个）
- 自行添加依赖（引入新 jar 前先询问）
- 删除已存在的 DSL 字段（除非明确要求，只允许新增）

## AI 行为约束

- **改规则先改 YAML** — 接到需求先改 `memory_dsl.yaml`，再改 Java
- **改代码先确认影响** — 修改 MetaModel 字段时，同步更新 `DSLParser` 和测试
- **不要跳过测试** — 任何代码改动后必须 `mvn test` 验证通过
- **不要自行添加依赖** — 引入新 jar 包前必须询问
- **保持注释完整** — 新增代码必须有中文注释，解释「为什么」而非「做什么」
- **不确定时先问** — 遇到架构选择先给方案让用户选
- **写新组件先定义接口** — 先设计 SPI 接口，再给默认实现
- **优先组合** — 用组合（委托/管道/策略），不写继承链
- **组件必须可替换** — 新建的 Store/Engine/Scheduler 必须通过工厂创建

## 常用命令

```bash
export JAVA_HOME=G:/env/jdk-17.0.17
export PATH=$JAVA_HOME/bin:$PATH
mvn compile
mvn test
mvn test -Dtest=DSLParserTest
mvn clean compile
mvn package
mvn dependency:tree
```
