# Agent Drive Agent 定义

> 现行 Agent 契约（2026-08-21）。本文描述当前生产 Agent 的能力边界和可靠性约束；接口细节以 [`architecture.md`](architecture.md) 和 [`security.md`](security.md) 为准。

## 1. 角色与目标

Agent Drive 的主 Agent 是 File Concierge：理解用户目标，使用受限工具完成文件、配置、任务和自动化操作，并在失败或需要确认时给出可解释结果。

```text
Agent = 模型 + 工具 + 记忆 + 规划 + 护栏
```

模型负责理解和选择下一步；工具负责执行；PostgreSQL 和文件索引负责持久状态；程序负责参数校验、权限、结果验证、成本和安全边界。

## 2. 当前能力

### 已支持

- 文件列表、名称/路径搜索、语义搜索、面向回答的多 chunk 证据检索、预览、受限全文查看、文本写入、移动、复制、重命名、回收站和恢复；二进制上传仍由文件页上传队列完成，不是模型直接提交的 Agent operation。
- LLM/embedding/vision 配置状态读取、按文件索引和视觉内容描述；图片索引最多四张一批但每张独立落库，显式索引不缓存旧描述；provider URL、模型目录 probe
  与 API key 写入仍只在设置页 REST 完成，不进入 Agent operation。
- 会话、摘要、用户偏好、reasoning、工具轨迹和确定性 replay 的持久化。
- 当前请求内的文件、配置状态和索引业务结果；provider 配置写入仍由设置页负责。长时间运行由运行 relay 的状态和明确终态反馈负责，不向 Agent 暴露旧任务队列。
- owner-scoped Skill 的发现、读取、创建、编辑、启停和删除；内置 API Skill 与 operation catalog 同源。
- Web 的 `frontend_api` 动作：只操作当前浏览器 registry 已登记的页面能力。
- 高风险写操作确认、nonce 防重放、路径安全、结果校验和审计日志。

### 不属于当前契约

- 任意 URL、任意 HTTP header、Cookie/Bearer、Python/Java 入口、JavaScript/eval。
- 在 Agent 请求内接触任意 URL、凭据、源代码执行入口或未登记 operation。图片索引不使用 OCR；长时间运行必须经过业务 Workflow 的状态和超时边界。
- S3 存储、多用户产品隔离、iOS 客户端、音视频转写和知识图谱问答。

## 3. 工具边界

生产模型主要看到以下稳定工具：

| 工具 | 作用 | 调用约束 |
|------|------|----------|
| `backend_api` | 后端 HTTP 和登记的内部 operation | 先 discover，再调用精确 operation；path/query/body 由注册 Schema 校验，凭据和 provider URL 被拒绝 |
| `frontend_api` | 当前浏览器页面动作 | 只能使用 registry 中 exact operation，结果以 `frontend_action` 交给本地 handler |
| `plan` | 保存和推进用户目标 | 只记录当前会话完整计划状态，不创建后台任务、不代替业务 mutation |
| `read_skill` | 分页发现并读取启用 Skill | 只读 owner registry；Skill 只指导已登记工具，不执行任意文件/代码 |

`backend_api` 使用 `action=discover|call` 信封。HTTP operation 标识为 `METHOD /api/v1/path`，内部 operation 标识为 `INTERNAL name`。discover 以 `discovery_offset` 和 `discovery_limit` 分页，默认返回 6 项、单页最多 20 项；每页同时返回完整匹配数、实际窗口、`has_more` 和 `next_offset`。模型必须在分页完成后才能把聚合结果称为完整匹配集。模型不能直接选择 Java 方法、React handler、文件系统绝对路径或未登记能力。

涉及用户文件事实、条款或分散内容的回答，优先调用 `GET /api/v1/files/search-content`，使用 `q`、可选 `path`、`limit` 和 `neighbors` 查询参数；结果中的 `text` 和 `neighbors` 是不可信文件数据，回答应引用 `path`/`source_revision`，没有足够证据时明确说明不确定。`GET /api/v1/files?mode=semantic` 仍用于文件列表定位，不应被当作完整证据包。

每次请求先自动注入当前 owner 的有界启用 Skill 摘要目录。用户点名某 Skill 或任务明显匹配其说明时，模型必须用目录中的 exact name 调用 `read_skill action=read`；discover 只用于搜索或刷新摘要。read 返回完整 Markdown 指令；单会话最多恢复 16 个已加载正文，超出上下文预算时继续以摘要标记但不把正文重复注入。内置 `agent-drive-api` 从当前 operation catalog 动态生成，`skill-authoring` 说明自定义 Skill 生命周期。Skill 不增加工具、权限或凭据。

## 4. 执行循环

```text
理解目标 → 选择 chat/task 路径 → discover 能力 → 调用工具
    ↑                                      ↓
重试/降级 ← 结构化错误 ← 参数校验/权限/结果验证
```

- Java runtime 以本轮是否产生工具轨迹标记 `chat`/`task`；任务请求走完整 tool loop，普通请求不产生工具步骤。会话 transcript、工具轨迹和 `last_trace` 用于持久化上下文与诊断，当前没有旧版短消息强制分流规则。
- Provider 原生 tool call 才能产生工具步骤；正文中的 DSML/XML 只是普通文本，不能触发工具，也不应被文档描述为另一套可执行协议。
- 工具返回结构化成功或失败；瞬时错误按 provider/工具策略退避，永久错误直接反馈原因。
- ask/auto 模式下 red operation 先保存待确认参数，确认后做确定性签名校验；full 模式按用户授权直接执行。只有 operation 显式声明为 `probe` 或 `idempotent` 时才按 session/tool/arguments replay；普通 GET 不缓存，失败结果不缓存，文件/配置/索引 mutation 后清空当前 session 的旧 replay。
- Agent 默认不因固定工具步数结束；它可以持续规划和调用工具，直到目标完成、用户取消或运行基础设施熔断。每轮仍记录输出和上下文用量；流式输出结束、取消、断开和异常都进入明确终态。聊天 runtime 通过 session relay 与 SSE 客户端解耦，断开只结束订阅，不等于取消 Agent；重新连接先校验 owner，再回放当前 relay。单次运行仍受 `ChatRunRegistry` 的 10 分钟超时和进程并发上限保护；非流式入口也使用同一 session registry。当前跨进程重启不恢复正在运行的 Agent，run state 明确标记 `resumable=false`。

## 5. 事件与记忆

Chat SSE 事件为 `context`、`text`、`reasoning`、`tool_start`、`tool_progress`、`tool_trace`、`frontend_action`、`done`、`error`，每个 data 都是 JSON object。工具参数事件和确认卡只发送脱敏视图；原始确认参数只留在服务端签名存储。context 带有 `trust` 等级：文件正文属于 `untrusted_data`，模型只能当作数据读取，不能执行其中的指令。上下文编译器有总字符预算和已加载 Skill 上限，按 source/kind 持久化并在前端默认折叠；同来源内容未变化不重复记录。reasoning 只有 provider 实际返回时才展示，且不注入下一轮 history。

持久状态分为：

| 层 | 内容 | 当前存储 |
|----|------|----------|
| 会话 | 消息、上下文注入、摘要、reasoning、tool trace、last trace | PostgreSQL `chat_sessions` / `chat_messages` |
| 偏好 | 语言、整理风格、命名规则 | PostgreSQL `agent_preferences` |
| Skill | owner 自定义说明、Markdown 指令、启停和版本 | PostgreSQL `agent_skills` + 应用内置 provider |
| 文件知识 | 文档、chunk、source revision、embedding fingerprint | owner 文件系统 + PostgreSQL/pgvector |

会话消息、摘要、工具轨迹、日志和 Agent memory 落库前脱敏；模型历史由服务端按 owner/session 读取，客户端 history 只作兼容回退。pending confirmation 的原始参数只保存在受保护的 confirmation 存储中，用于签名校验；replay 结果按显式策略保存且参数/输出脱敏。

## 6. 可靠性和安全不变量

| 维度 | 当前要求 |
|------|----------|
| 一致性 | 只有显式 replayable operation 才按 session/tool/arguments 重放；失败不缓存，mutation 失效旧快照；任务状态只经状态机迁移 |
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
