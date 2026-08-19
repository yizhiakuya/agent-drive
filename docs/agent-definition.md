# Agent Drive Agent 定义

> 现行 Agent 契约（2026-08-19）。本文描述当前生产 Agent 的能力边界和可靠性约束；接口细节以 [`architecture.md`](architecture.md) 和 [`security.md`](security.md) 为准。

## 1. 角色与目标

Agent Drive 的主 Agent 是 File Concierge：理解用户目标，使用受限工具完成文件、配置、任务和自动化操作，并在失败或需要确认时给出可解释结果。

```text
Agent = 模型 + 工具 + 记忆 + 规划 + 护栏
```

模型负责理解和选择下一步；工具负责执行；PostgreSQL 和文件索引负责持久状态；程序负责参数校验、权限、结果验证、成本和安全边界。

## 2. 当前能力

### 已支持

- 文件列表、名称/路径搜索、语义搜索、预览、受限全文查看、上传、移动、复制、重命名、回收站和恢复。
- LLM provider、embedding 和 vision 配置、模型目录 probe、连接测试和按文件索引任务。
- 会话、摘要、用户偏好、reasoning、工具轨迹和确定性 replay 的持久化。
- 任务状态、进度、取消、重试、schedule、Worker 在线状态和自动化报告。
- Web 的 `frontend_api` 动作：只操作当前浏览器 registry 已登记的页面能力。
- 高风险写操作确认、nonce 防重放、路径安全、结果校验和审计日志。

### 不属于当前契约

- 任意 URL、任意 HTTP header、Cookie/Bearer、Python/Java 入口、JavaScript/eval。
- 在 Agent 请求内执行 OCR、embedding、vision 或长时间文件遍历；这些工作必须入队交给 Worker。
- S3 存储、多用户产品隔离、iOS 客户端、音视频转写和知识图谱问答。

## 3. 工具边界

生产模型主要看到以下稳定工具：

| 工具 | 作用 | 调用约束 |
|------|------|----------|
| `backend_api` | 后端 HTTP 和登记的内部 operation | 先 discover，再调用精确 operation；参数由 Schema 校验 |
| `frontend_api` | 当前浏览器页面动作 | 只能使用 registry 中 exact operation，结果以 `frontend_action` 交给本地 handler |
| `plan` | 保存和推进用户目标 | 只记录计划状态，不代替业务 mutation |
| `read_skill` | 按需加载技能说明 | 只读登记的 skill，不执行任意文件/代码 |

`backend_api` 使用 `action=discover|call` 信封。HTTP operation 标识为 `METHOD /api/v1/path`，内部 operation 标识为 `INTERNAL name`。模型不能直接选择 Java 方法、React handler、文件系统绝对路径或未登记能力。

## 4. 执行循环

```text
理解目标 → 选择 chat/task 路径 → discover 能力 → 调用工具
    ↑                                      ↓
重试/降级 ← 结构化错误 ← 参数校验/权限/结果验证
```

- Java runtime 以本轮是否产生工具轨迹标记 `chat`/`task`；任务请求走完整 tool loop，普通请求不产生工具步骤。会话 transcript、工具轨迹和 `last_trace` 用于持久化上下文与诊断，当前没有旧版短消息强制分流规则。
- Provider 原生 tool call 才能产生工具步骤；正文中的 DSML/XML 只是普通文本，不能触发工具，也不应被文档描述为另一套可执行协议。
- 工具返回结构化成功或失败；瞬时错误按 provider/工具策略退避，永久错误直接反馈原因。
- red operation 先保存待确认参数，确认后做确定性签名校验；非 red operation 以 session/tool/arguments replay，避免重试再次产生副作用。
- 每轮有步数、输出和上下文预算；流式输出结束、取消、断开和异常都进入明确终态。

## 5. 事件与记忆

Chat SSE 事件为 `text`、`reasoning`、`tool_start`、`tool_trace`、`frontend_action`、`done`、`error`，每个 data 都是 JSON object。reasoning 只有 provider 实际返回时才展示，前端默认折叠，且不注入下一轮 history。

持久状态分为：

| 层 | 内容 | 当前存储 |
|----|------|----------|
| 会话 | 消息、摘要、reasoning、tool trace、last trace | PostgreSQL `chat_sessions` / `chat_messages` |
| 偏好 | 语言、整理风格、命名规则 | PostgreSQL `agent_preferences` |
| 文件知识 | 文档、chunk、source revision、embedding fingerprint | owner 文件系统 + PostgreSQL/pgvector |

会话消息、摘要、工具轨迹、日志和 Agent memory 落库前脱敏；pending confirmation 的原始参数只保存在受保护的 replay/confirmation 存储中，用于签名校验和确定性重放。

## 6. 可靠性和安全不变量

| 维度 | 当前要求 |
|------|----------|
| 一致性 | 相同 session/tool/arguments 的非 red 重试不重复副作用；任务状态只经状态机迁移 |
| 鲁棒性 | 空目录、迟到响应、大文件、特殊路径、索引失败和 provider 暂时不可用都有明确分支 |
| 可预测性 | API 使用稳定 status/detail；工具、SSE、任务和审计都有终态记录 |
| 安全性 | owner 鉴权、路径边界、red 确认、secret 脱敏、文件原子发布和失败关闭 |

文件变更先失效旧索引，Worker 异步处理；图片描述写入前校验 source revision；工具执行只把 `Exception` 转成可恢复工具结果，JVM `Error` 必须终止外层流。

## 7. 维护重点

新增 Agent 能力必须同时更新：

1. operation catalog 和参数 Schema；
2. 权限/风险等级、错误语义和 replay/确认策略；
3. SSE/前端工具步骤或页面 registry；
4. Java/前端/Android 对应契约测试；
5. [`AGENTS.md`](../AGENTS.md)、架构/安全文档和部署说明。

当前已知的产品级后续是 URL 可分享路由、上传细粒度进度、可靠性指标/eval、修改密码 UI，以及 S3/iOS/音视频能力；它们是明确的产品 backlog，不是当前 Agent 已支持的功能。
