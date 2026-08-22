# 工程质量分析协议

> 本文件是仓库内质量分析 Agent 的长期协议。它规定如何做增量审查，不是某次审查报告。历史结果见 [`archive/README.md`](archive/README.md)。

## 1. 工作方式

### 分析阶段

- 只读：不修改、移动、格式化或删除代码/文档。
- 先报告后修改：完成分析后给出结构化报告；只有用户明确要求“直接修/按方案改/继续修”才进入修复阶段。
- 架构和技术栈属于基线；无重大架构变更时引用基线，不重复完整重审。

### 增量检查

每次按以下顺序检查：

1. 门禁：Java 21/Maven 3.9 Enforcer、Maven test/package、前端 lint/Vitest/build、Android JVM 单测。
2. 风格：命名、Javadoc、TypeScript/React 约定、UI token、错误处理和相邻代码一致性。
3. 耦合与重复：模块依赖方向、重复请求封装、重复 UI 和跨层访问。
4. 静态质量：编译、类型、ESLint、死导出、失效 suppress、TODO/FIXME 和临时文件。
5. 测试缺口：近期行为变化是否有回归测试，高风险错误路径是否覆盖。
6. 复杂度：函数/类长度、认知复杂度、循环深度、异常分支和跨模块调用热点。
7. 文档同步：行为、部署、数据所有权和安全约定是否同步到现行文档。
8. 安全健壮性：并发、原子性、失败关闭、路径安全、凭据隔离和错误脱敏。

重复逻辑先报告并给出抽取建议；只有用户授权修复后才重构。质量快照文档不随实现改写。

## 2. 标准命令

```powershell
# Java 后端
cd backend
mvn -q test
mvn -q -DskipTests package

# 测试完成后检查 target/site/jacoco/index.html；CI 保存为 backend-jacoco artifact。
# 当前用于趋势观察，不以历史代码的一次性阈值阻断构建。

# 真实持久化门禁必须设置 AGENT_DRIVE_JDBC_TEST_URL，并启用 db profile；未设置时集成用例会被跳过，不能记为完整通过。

# 前端
cd ..\frontend
npm run lint
npm test
npm run build

# Android JVM 单测
cd android
gradlew.bat testDebugUnitTest
```

复杂度和残留检查优先使用代码图工具；需要文本/配置搜索时使用 `rg`：

```powershell
rg -n "console\.|TODO|FIXME" frontend/src backend/src -g "*.ts" -g "*.tsx" -g "*.java" | rg -v test
```

生产 smoke 由 `scripts/deploy.ps1` 完成：它负责前端/Java 构建、静态资源原子替换、systemd unit 校验、API 重启和 health/readiness 检查。

## 3. 报告模板

```markdown
# 工程质量分析报告（YYYY-MM-DD）

## 基线结论
架构/技术栈：引用本节快照；无重大变更则不重复展开。

## 门禁结果
| 后端 | Maven test/package | ✅/❌ |
| 前端 | lint/vitest/build | ✅/❌ |
| Android | JVM 单测 | ✅/❌ |

## 增量发现
### 明确问题
给出文件、行号、复现证据和影响。
### 建议
区分风格、耦合、复杂度和测试缺口，不把建议写成 bug。
### 已达标
只列与本轮检查直接相关的结论。

## 下一步
按 P0/P1/P2 分组，等待用户选择后再修改。
```

## 4. 当前基线快照

> 审计基线：2026-08-22。重大架构或技术栈变更时更新此处，并同步 [`AGENTS.md`](../AGENTS.md)。

- **架构**：Java 21 + Spring Boot/WebFlux API、PostgreSQL/pgvector；Next.js 16 静态导出前端；Capacitor 7 Android 壳。当前没有 Java 任务 Worker，索引/视觉/向量由业务 API 直接执行。详见 [`architecture.md`](architecture.md)。
- **技术栈**：Java 21、Spring Modulith、LangChain4j、MyBatis-Plus、Flyway、PostgreSQL/pgvector、Tika、视觉模型/Jina；TypeScript 5、React 19、Tailwind 4、shadcn/ui、Vitest；AndroidX Security Crypto/WorkManager。
- **当前达标项**：backend_api 已按 catalog/router 和领域 handler 分层；API 请求元数据/完成日志和 WebFlux 阻塞执行边界已统一；聊天流已拆出事件、状态、帧、模型目录和文件引用模块；文件页上传队列已独立并保留请求代次保护；Java/Maven 工具链和 JaCoCo 报告已进入 Maven 生命周期；Agent 工具不捕获 JVM `Error`；锁序、原子发布、失败关闭和数据库集成测试已纳入门禁。
- **覆盖率基线**：2026-08-22 全量 PostgreSQL 门禁下 JaCoCo instruction 64.3%、branch 46.0%；先用 CI artifact 跟踪趋势，新增/变更行为仍要求聚焦回归，不用低价值测试追逐一次性数字。
- **主要复杂度热点**：文件存储、Agent runtime、文件页剩余业务编排、聊天会话编排和 Android `SyncEngine`。后续重构应以直接 hook/领域测试和行为不变量为前提，不在质量分析阶段直接改写。
- **生产状态**：Java API 已运行，生产入口为 nginx `13311`；旧 Python source/unit 和历史任务表不属于运行时，旧资料只在 fixture/cutover backup 中保留。
