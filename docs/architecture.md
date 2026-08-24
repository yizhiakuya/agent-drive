# Agent Drive 架构

> 现行事实（2026-08-20）。生产后端已经是 Java 21；本文不描述已经删除的 Python 实现。迁移和切换证据见 [`java-migration-architecture.md`](java-migration-architecture.md)。

## 1. 运行拓扑

```text
公网 HTTPS :13311
      │ nginx
      ▼
Java API 127.0.0.1:8000 ───── PostgreSQL/pgvector
      │                              │
      └── frontend/out               └── 结构化状态、索引
                 │
                 └──（可选）Content Service 127.0.0.1:8010 ── Vision Provider
                 └──（可选）File Service 127.0.0.1:8020 ── owner storage
                 └──（待认证迁移）Identity Service 127.0.0.1:8030
                 └──（待索引迁移）Index Service 127.0.0.1:8040
```

当前 API 负责 HTTP、SSE、静态前端以及索引/视觉/向量业务编排。生产已配置 `AGENT_DRIVE_CONTENT_SERVICE_URL` 与内部令牌，视觉端口通过 loopback HTTP 调用 Content Service；未配置的开发环境仍使用 API 内本地实现。File Service 已完成 796 个文件的逐文件 MD5 镜像并 ready，但 `AGENT_DRIVE_FILE_SERVICE_URL` 保持为空，直到所有文件 mutation 具备原子镜像同步。后台任务、计划队列、outbox 和独立 Worker 已移除；当前 Agent 的 `plan` 仅是会话内可视化状态，不创建持久任务；历史任务表仅保留在已有数据库中，不再由运行时写入。

微服务演进边界、服务数据所有权和拆分顺序见 [`microservices-architecture.md`](microservices-architecture.md)。当前 API 仍是模块化单体，但 Content Service 已接入生产；File/Identity 只部署了迁移契约，尚未切换主 API 数据所有权。

## 2. 后端模块

源码位于 `backend/src/main/java/com/agentdrive`：

| 模块 | 职责 | 约束 |
|------|------|------|
| `api` | WebFlux controller、SSE、上传下载、请求元数据/完成日志、静态资源和 SPA fallback | 处理协议/鉴权入口；同步调用统一经 `ReactiveExecution` 移出 event loop，不直接拼 SQL 或决定领域规则 |
| `agent` | LangChain4j runtime、tool catalog、确认、replay、reasoning | owner、凭据和权限由运行时注入，不暴露给模型 |
| `auth` | 用户、Cookie/Bearer session、设备令牌、配对码、限速 | 认证数据异常失败关闭 |
| `config` | LLM、embedding、vision 配置、模型探测和会话认证的按需 Key 回显 | API key 加密存储；普通响应只给掩码，回显响应禁止缓存且不进入 Agent 工具目录 |
| `files` / `storage` | 文件用例、metadata、revision、真实版本快照、回收站、路径安全、原子发布 | 公共路径是 owner 内相对 POSIX 路径；`.versions` 仅由内部快照流程访问 |
| `devices` | 设备登记、撤销、心跳和同步状态 | 所有查询按 owner 限定 |
| `skills` | owner Skill registry、内置 provider、校验和分页 | 自定义 Skill 在 PostgreSQL；内置 Skill 由代码动态生成 |
| `index` | Tika 文档抽取、文本 chunk/Jina 向量、视觉内容语义描述/Jina 向量 | 由索引业务 API 直接执行；图片不走独立 OCR |
| `services/content-service` | 独立视觉内容理解 HTTP 服务 | 只接收受限原始图片和 owner provider 快照，不读主库/本地路径，不持久化描述 |
| `services/file-service` | 独立 owner 文件内容读取 HTTP 服务 | 只读取自己的 owner 分区；路径、符号链接、大小和 MD5 在服务边界重新校验 |
| `services/identity-service` | 独立 owner/session credential 服务 | 自有 identity schema；当前仅提供迁移契约，主 API 认证默认仍使用现有数据库 |
| `services/index-service` | 独立文档/chunk 索引服务 | 自有 index schema；当前提供正文迁移契约，pgvector 切换前主 API 仍使用本地 IndexStore；远程客户端仅在显式配置时创建 |
| `infrastructure` | MyBatis、Flyway、PostgreSQL、HTTP client、加密和启动适配器 | 为上层提供实现，不反向承载业务决策 |

跨模块写操作通过 application service 和 PostgreSQL 事务连接。带方法级 `@Transactional` 的持久化适配器保持可代理，当前 Spring 类代理模式要求实现类非 `final`。

## 3. 状态所有权

PostgreSQL 保存所有结构化运行状态，包括：

- `users`、`sessions`、`devices`、`pairing_codes`；
- `chat_sessions`、`chat_messages`（含 context source/kind）、`chat_tool_replays`；
- `files`、`file_revisions`、`trash_entries`、`upload_dedup`；
- 历史 `tasks`、`task_events`、`task_schedules`、`task_workers`、`outbox_events` 表（只供旧数据迁移/人工清理，不是当前运行时状态源）；
- `agent_skills`（owner 自定义 Skill、启停状态和版本）；
- `documents`、`document_chunks`、embedding metadata、owner-scoped `file_favorites`/`file_accesses`、`file_version_snapshots`、`agent_preferences` 和 provider 配置。

实际二进制文件以及用户可见的 `AGENT.md`、`USER.md`、`MEMORY.md` 仍在 owner-scoped 本地文件系统。`legacy-python-data/` 和服务器 `/opt/agent-drive-java/backups/` 只用于人工恢复或一次性迁移，不进入服务运行路径；旧 SQLite、JSON auth/device/upload index 不是生产真相源。

## 4. API 与 Agent 契约

- 所有业务接口保持 `/api/v1` 前缀。`/api/v1/health` 和认证初始化接口按规则公开，其余业务接口按当前 owner 鉴权。
- `/api/v1/ready` 是公开的最小 readiness 探针，只返回数据库和存储可用性，并附带不参与 ready 判定的最近备份摘要；系统状态中心把它与 owner-scoped 的配置、设备/同步和文件磁盘信息合并展示。
- 所有 `/api` 响应由 `ApiRequestLoggingWebFilter` 复用或生成 `X-Request-ID`，并在请求终止时记录 method、匹配后的路由模板、status、duration、可信 client IP 和 terminal；不记录 query value、header、body 或路径参数。只有 loopback TCP 对端可以使用 nginx 覆写的单值合法 `X-Forwarded-For`。
- `/api/v1/files/favorites` 和 `/api/v1/files/recent` 提供 owner-scoped 收藏/最近访问集合；文件列表支持 `type`、`modified_after`、`modified_before` 和语义 `min_score`，服务端负责校验范围并返回 `has_more`。`GET /api/v1/files/search-content` 是 Agent 专用的只读证据入口，返回多个匹配 chunk、相邻 chunk、source revision、chunk index 和相关度，不改变普通列表的每文件最佳结果语义。
- `/api/v1/files/stats` 提供 owner-scoped 的递归文件统计；结果返回 `file_count`、`folder_count`、`total_size_bytes`、`complete` 和 `snapshot_at`，Agent 统计目录数量时优先使用该服务端快照，不能把不完整的列表遍历汇总成确定总数。
- `/api/v1/files/versions` 列出当前文件的真实内容快照，`POST /api/v1/files/versions/restore` 把快照作为新 revision 原子发布；快照元数据按 owner 绑定，内容位于 owner 私有 `.versions` 目录，公共路径解析拒绝该目录；每个文件最多保留 20 个快照，超出部分在数据库提交后回收。
- Web 使用 HttpOnly Cookie，Android 使用 Bearer 设备令牌；查询参数 `?token=` 只允许 raw/download 媒体 GET。
- Chat SSE 使用 `event: <name>` + `data: <JSON object>`，事件包括 context、text、reasoning、tool_start、tool_progress、tool_trace、frontend_action、done、error。`tool_progress` 是长工具的实时心跳，携带业务阶段和耗时，前端更新当前 running Activity；同步业务没有可靠百分比时只展示阶段和 elapsed，不伪造进度。context 携带 source/kind/content/trust，并与历史 API 的 context 消息使用同一展示结构；工具参数和 pending confirmation 只发送脱敏视图。流内异常保持 HTTP 200，error 携带脱敏消息和服务端已确认的 session ID，runtime 同时持久化脱敏错误与最后 trace。`ChatRequest.model` 可指定本轮模型，空值沿用 owner 默认模型。
- 前端工作区头部提供全局 `OperationActivityCenter`。文件索引/视觉索引/向量化和批量文件 mutation 发出运行阶段、耗时、成功/失败计数和终态；Agent 的文件/index mutation 从 Chat SSE 工具事件映射到同一活动列表。运行态不跨进程恢复，完成记录只在浏览器本地保留有限窗口；活动中心是反馈层，不是任务存储或队列。
- 聊天 runtime 由 owner session 级 `ChatRunRegistry` relay 持有，不绑定单个 SSE 客户端；浏览器刷新、切换页面或网络断开不会主动取消运行。Agent 默认不因固定工具步数结束，目标完成、用户取消或运行基础设施熔断才终止；进程内最多保留 8 个 active run，单次运行 10 分钟超时。初始流返回 `X-Session-ID`，`GET /api/v1/chat/{sessionId}/active`、`GET /api/v1/chat/{sessionId}/stream` 和 `POST /api/v1/chat/{sessionId}/cancel` 先做 owner 会话校验后提供状态、回放重连和显式停止，非流式入口也通过同一 registry。relay 是当前 API 进程内的运行态，进程重启后 run state 标记为不可恢复，不提供跨进程后台任务入口。
- `ChatRequest.file_context` 只接收 owner 内相对路径列表；后端在当前认证 owner 下读取文件/文件夹摘要后再以 `untrusted_data` 边界注入模型，正文中的命令不会获得指令权限。文件选择器附件先上传到 owner 的 `聊天附件` 目录；剪贴板图片只作为受限 `inline_images` Base64 随本轮请求发送，单张原图上限 50 MiB，应用侧保持原始字节并以视觉 detail `HIGH` 请求较高质量；视觉索引同样原样发送图片 bytes，并在 OpenAI 兼容请求中设置 `image_url.detail=high`，后端在模型不支持图片时拒绝，不写入会话或文件系统。模型输出的 `[[file:path]]` / `[[folder:path]]` 由前端转换成 allowlist 内的 `files.open` / `files.open_folder` 动作，不能触发任意 URL。
- Skill 目录每轮注入摘要；成功读取过的 Skill 名称从当前会话的 owner-scoped `read_skill` transcript 轨迹恢复，下一轮由当前 registry 重新注入正文并避免重复工具调用。Skill 更新或停用不信任旧正文，始终以 registry 当前版本为准。
- 索引资源由独立 `IndexDomainService` 和 `/api/v1/index` 提供 owner-scoped CRUD：读取状态/写入文本或视觉文档、直接向量化、清空向量、清理失效索引和重建全文。文本索引与向量化是两个同步 operation；批量调用返回 `succeeded/partial/failed` 及逐项错误，provider/路径错误返回结构化 `ok/status/code/detail`；Agent 不暴露任务创建接口。
- 设置页的 `POST /api/v1/config/models` 返回模型目录及 `model_capabilities`，但该配置/探测 operation 不进入 Agent catalog；Agent 只能读取脱敏配置状态并使用已保存的 owner provider。Provider 明确声明的图片/模态能力优先，未知模型默认拒绝；Anthropic 只按已知 Claude 3/4 视觉系列放行。前端能力提示仅用于交互，聊天后端仍按当前配置重新校验 `inline_images`。
- 模型只看到稳定的 `backend_api`、`frontend_api`、`read_skill` 及当前会话 `plan` 辅助工具。上下文编译器始终装配规范系统提示、owner 的 `Agent/AGENT.md` 和启用 Skill 摘要目录，并对已加载正文施加数量和字符预算；涉及整理、自动化、规则、偏好或记忆时再装配 `USER.md`、`MEMORY.md`，简单只读文件请求不重复发送无关个人文档。同来源正文未变化时不重复写 transcript。目录明确要求名称/说明匹配时先按 exact name 读取 Skill；正文只通过 `read_skill` 按需进入工具结果。`plan` 的每次调用必须返回完整步骤数组，仅用于当前会话 UI，不创建后台任务。业务能力仍须使用精确登记 operation，Skill 不能新增工具或权限。模型不能提供任意 URL、请求头、凭据、JavaScript 或 Java 类名。
- 只有显式声明为 `probe`/`idempotent` 的 operation 才按 `session_id + tool + arguments` 使用持久 replay；普通 GET 不缓存，失败结果不缓存，mutation 后清空 session replay。ask/auto 模式下 red 写操作使用签名确认和一次性 nonce，客户端只回传 nonce/签名元数据；full 模式按用户授权直接执行。工具执行只把 `Exception` 编码为可恢复结果，JVM `Error` 交给外层终止流。
- provider 的 `thinking_level` 为 `auto/low/medium/high`，不发送 temperature。`model` 只覆盖当前请求，动态 resolver 始终从 owner 已保存配置取得 Provider 地址和 API key。reasoning 只在 provider 返回时通过独立 SSE 事件展示和持久化，不进入下一轮 history。

## 5. 文件、上传与索引

文件 mutation 使用 owner 目录、组件级路径校验、symlink 拒绝、`.storage.lock`、0600 staging/隐藏 backup、fsync 和原子 move/link。上传由服务端流式复算 MD5；`noclobber` 原子发布，不使用“先 exists 再写”的 TOCTOU 流程。覆盖上传/文本写入在发布前复制旧普通文件到 owner 私有 `.versions` 并登记 `file_version_snapshots`；版本恢复通过同一上传事务产生新 revision。嵌套上传在发布事务内逐级同步父目录 metadata，使物理目录与 owner 归属记录一致。上传、移动、复制、移入回收站和恢复把 storage lock 持有到 PostgreSQL 事务 afterCompletion：提交后清理 backup，回滚或提交失败恢复旧磁盘状态并回滚 metadata；提交后的 artifact 清理失败只记录 warning。文本预览用严格 UTF-8、GBK、ISO-8859-1 顺序解码，截断只丢弃末尾未完成码点。

文件列表的 name 搜索最多保留 1000 个 top-k 候选，并只批量同步缺失或变化的 metadata；列表可按文件类型和修改时间筛选，并附 owner-scoped 收藏标记。semantic 搜索使用 Jina `retrieval.query` 和当前 embedding fingerprint 的 pgvector chunk，按文件去重并返回最佳 `search_score/search_snippet`。Agent 需要回答文件原文时调用 `search-content`，数据库在同一 owner/current revision/fingerprint 范围内返回全局多 chunk 候选，并按每文件最多三个匹配 chunk 做多样性限制；每个匹配可带两侧以内相邻 chunk，结果受 limit/上下文预算约束。`file_favorites` 与 `file_accesses` 记录只保存相对路径，收藏/访问集合重新校验物理路径后才展示；移动/重命名在同一事务内迁移源/目标路径前缀。

写入、移动、复制或删除会使旧全文/向量失效；索引业务由 `/api/v1/index` 直接执行。普通文档使用 Tika、chunk 和 Jina 文本向量；图片显式索引时按最多四张一批发送到视觉模型，每张图片返回一段 `vision-description-v3` 综合描述，再由同一 Jina embedding 生成 `vision` 向量。该方案不缓存旧描述，也不启用独立 OCR；图片中的精确逐字文本不属于当前检索保证范围。provider、持久化或 source revision 错误直接返回明确错误，不能伪报成功；`documents.document_type` 明确区分 `text` 和 `vision`。

## 6. 业务错误与执行边界

所有 Controller 使用统一的 `status/code/detail/ok` 错误结构；参数错误、资源不存在、冲突和 provider 不可用返回稳定领域说明，未分类 500 只向客户端返回通用 detail，真实异常以脱敏 throwable 和 request ID 写入 journal。Agent 的 `backend_api` 会把 dispatcher 的业务失败提升到 envelope 顶层并保留完整 `result`，模型和前端不能把失败误当成普通成功返回；历史旧 envelope 的嵌套失败也按失败展示。Agent 视觉配置/模型探测省略地址时沿用当前 owner 已保存地址，直接设置 API 的默认地址行为不变。索引/视觉/向量 API 同步返回真实执行结果，provider 前置检查失败时不写入任何任务记录。

## 7. 前端与 Android

Next.js 16 使用静态导出，生产由 Java API 托管 `frontend/out`。前端分为认证门控、Chat、文件、会话、设置、Skill 和设备/同步页面；API client 统一处理身份、GET 缓存隔离、401 和事件总线。

文件页的列表、详情、全文、索引和回收站刷新使用独立请求代次与当前路径校验，只有当前请求失败才显示 toast；上传状态机和展示分别由 `useUploadQueue` / `UploadQueueBar` 管理。ChatPanel 保持常驻挂载；模型目录、文件引用 Markdown 和候选列表分别由 `useModelCatalog`、`AssistantMarkdown`、`FileMentionPicker` 管理，主组件集中处理聊天会话编排。初次 `authMode=loading` 才显示整页 Skeleton，下拉刷新不得因 store.loading 卸载工作区。`useChatStream` 按 session key 保存活动 controller/frame/busy，切换会话只隔离 UI 写入而不中止原流，不同会话可并行；首次流通过 `X-Session-ID` 收养服务端会话，页面把活动 session ID 写入 localStorage，重新进入时先 `no-store` 读取最新历史并查询 active relay，运行中自动重连、跟随底部，显式停止才调用 cancel。显式停止或组件卸载只 Abort 浏览器订阅，不把普通刷新误当成取消。ChatPanel 的会话历史/模型目录、SettingsPage 的 LLM/视觉模型目录，以及 Onboarding 的首次模型目录请求都必须在响应提交前校验请求代次和当前配置边界。UI 控件和主题遵循 [`frontend-design.md`](frontend-design.md)。

Android 是 Capacitor 7 原生壳：ServerConfig/PhotoSync 插件接入扫码配对、加密令牌、WorkManager、MediaStore 和通知。相册同步使用秒级 checkpoint、pending second/id、服务端 dedupe 预检和 MD5 校验；本地文件消失/权限拒绝永久跳过，其他本地 I/O 冻结水位，线程中断保留中断位并终止本批。同步配置写入独立 EncryptedSharedPreferences，失败关闭，不降级明文。

## 8. 生产运行与验证

- API：`deploy/agent-drive-java.service`，`127.0.0.1:8000`，`--app.mode=api`。
- artifact：`/opt/agent-drive-java/agent-drive-backend.jar`；数据根：`/opt/agent-drive-java/data`。
- 密钥：`/etc/agent-drive-java/java.env`，权限 0600；外部 HTTP(S) 代理来自 `/etc/agent-drive/proxy.env`，systemd 清除 SOCKS 环境变量。
- 公网：nginx `13311` → API `8000`；生产部署优先使用 `scripts/deploy.ps1`，它负责构建、原子替换、unit 校验、API 重启并验证 `/api/v1/ready`，失败自动回滚到上一版。
- 构建：后端要求 Java 21 + Maven 3.9，Maven Enforcer 在生命周期前段校验；`mvn test` 生成 `backend/target/site/jacoco/index.html`。

详细认证和暴露面规则见 [`security.md`](security.md)，生产切换证据见 [`java-migration-architecture.md`](java-migration-architecture.md)。

## 9. 当前边界

当前只支持 owner-scoped 本地文件系统，不提供 S3；认证模型面向个人单用户，虽已按 owner 设计数据边界；没有 iOS 客户端和音视频转写。上述能力不应在文档或 Agent 契约中被描述为已实现。
