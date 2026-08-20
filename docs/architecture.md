# Agent Drive 架构

> 现行事实（2026-08-20）。生产后端已经是 Java 21；本文不描述已经删除的 Python 实现。迁移和切换证据见 [`java-migration-architecture.md`](java-migration-architecture.md)。

## 1. 运行拓扑

```text
公网 HTTPS :13311
      │ nginx
      ▼
Java API 127.0.0.1:8000 ───── PostgreSQL/pgvector
      │                              │
      └── frontend/out               └── 结构化状态、任务、索引

Java Worker ─────────────────────── PostgreSQL leases/events/outbox
      │
      └── owner 文件系统、Tika/Tesseract、embedding/vision provider
```

API 和 Worker 是同一个模块化单体的两种进程模式：API 负责 HTTP、SSE、静态前端和轻量请求编排；Worker 负责租约任务、计划调度、文件抽取和索引。生产 API 不内嵌 Worker，Worker 不监听公网端口。

## 2. 后端模块

源码位于 `backend/src/main/java/com/agentdrive`：

| 模块 | 职责 | 约束 |
|------|------|------|
| `api` | WebFlux controller、SSE、上传下载、静态资源和 SPA fallback | 处理协议/鉴权入口，不直接拼 SQL 或决定领域规则 |
| `agent` | LangChain4j runtime、tool catalog、确认、replay、reasoning | owner、凭据和权限由运行时注入，不暴露给模型 |
| `auth` | 用户、Cookie/Bearer session、设备令牌、配对码、限速 | 认证数据异常失败关闭 |
| `config` | LLM、embedding、vision 配置和模型探测 | API key 加密存储，只返回掩码 |
| `files` / `storage` | 文件用例、metadata、revision、回收站、路径安全、原子发布 | 公共路径是 owner 内相对 POSIX 路径 |
| `devices` | 设备登记、撤销、心跳和同步状态 | 所有查询按 owner 限定 |
| `tasks` | 状态机、租约、事件、schedule、outbox 和 Worker handler | PostgreSQL 是任务唯一真相源 |
| `skills` | owner Skill registry、内置 provider、校验和分页 | 自定义 Skill 在 PostgreSQL；内置 Skill 由代码动态生成 |
| `index` | Tika/Tesseract 抽取、全文、chunk、embedding、vision | 只在 Worker 执行，不进入上传请求路径 |
| `infrastructure` | MyBatis、Flyway、PostgreSQL、HTTP client、加密和启动适配器 | 为上层提供实现，不反向承载业务决策 |

跨模块写操作通过 application service、PostgreSQL 事务和 owner-scoped outbox 连接。Spring Modulith 用于验证模块依赖，部署形态仍是单体 API + 单体 Worker。

## 3. 状态所有权

PostgreSQL 保存所有结构化运行状态，包括：

- `users`、`sessions`、`devices`、`pairing_codes`；
- `chat_sessions`、`chat_messages`、`chat_tool_replays`；
- `files`、`file_revisions`、`trash_entries`、`upload_dedup`；
- `tasks`、`task_events`、`task_schedules`、`task_workers`、`outbox_events`；
- `agent_skills`（owner 自定义 Skill、启停状态和版本）；
- `documents`、`document_chunks`、embedding metadata、`agent_preferences` 和 provider 配置。

实际二进制文件以及用户可见的 `AGENT.md`、`USER.md`、`MEMORY.md` 仍在 owner-scoped 本地文件系统。`legacy-python-data/` 和服务器 `/opt/agent-drive-java/backups/` 只用于人工恢复或一次性迁移，不进入服务运行路径；旧 SQLite、JSON auth/device/upload index 不是生产真相源。

## 4. API 与 Agent 契约

- 所有业务接口保持 `/api/v1` 前缀。`/api/v1/health` 和认证初始化接口按规则公开，其余业务接口按当前 owner 鉴权。
- Web 使用 HttpOnly Cookie，Android 使用 Bearer 设备令牌；查询参数 `?token=` 只允许 raw/download 媒体 GET。
- Chat SSE 使用 `event: <name>` + `data: <JSON object>`，事件包括 text、reasoning、tool_start、tool_trace、frontend_action、done、error。流内异常保持 HTTP 200 并发送脱敏 error 事件；前端收到传输异常时先冲刷并取消当前 80ms 帧，再保留已生成正文/工具轨迹并追加错误提示。`ChatRequest.model` 可指定本轮模型，空值沿用 owner 默认模型。
- 模型只看到稳定的 `backend_api`、`frontend_api`、`read_skill` 及 plan 辅助工具。业务能力必须先 discover，再用精确的 `METHOD /api/v1/path` 或 `INTERNAL name` 调用；backend discover 使用 offset/limit 分页并返回 total/has_more/next_offset，单页最多 20 项。`read_skill` 只读当前 owner 已启用 registry，Skill 指令不能新增工具或权限。模型不能提供任意 URL、请求头、凭据、JavaScript 或 Java 类名。
- 非 red 工具按 `session_id + tool + arguments` 使用持久 replay；red 写操作使用签名确认和一次性 nonce。工具执行只把 `Exception` 编码为可恢复结果，JVM `Error` 交给外层终止流。
- provider 的 `thinking_level` 为 `auto/low/medium/high`，不发送 temperature。`model` 只覆盖当前请求，动态 resolver 始终从 owner 已保存配置取得 Provider 地址和 API key。reasoning 只在 provider 返回时通过独立 SSE 事件展示和持久化，不进入下一轮 history。

## 5. 文件、上传与索引

文件 mutation 使用 owner 目录、组件级路径校验、symlink 拒绝、`.storage.lock`、0600 staging/隐藏 backup、fsync 和原子 move/link。上传由服务端流式复算 MD5；`noclobber` 原子发布，不使用“先 exists 再写”的 TOCTOU 流程。上传、移动、复制、移入回收站和恢复把 storage lock 持有到 PostgreSQL 事务 afterCompletion：提交后清理 backup，回滚或提交失败恢复旧磁盘状态；提交后的 artifact 清理失败只记录 warning。文本预览用严格 UTF-8、GBK、ISO-8859-1 顺序解码，截断只丢弃末尾未完成码点。

文件列表的 name 搜索最多保留 1000 个 top-k 候选，并只批量同步缺失或变化的 metadata。semantic 搜索使用 Jina `retrieval.query` 和当前 embedding fingerprint 的 pgvector chunk，按文件去重并返回最佳 `search_score/search_snippet`。

写入、移动、复制或删除先使旧全文/向量失效，再由 outbox 入队 `index.file`。Worker 负责 Tika/Tesseract 抽取、chunk 和 embedding；单文件抽取失败标记为 skipped，不阻断全量 rebuild。显式 `index.embed/index.vision` 的 provider、持久化或中断失败进入任务 fail/retry，不能用 `vectorized=false` 伪报成功；全文任务只允许在 `embedding_not_configured` 时降级。视觉任务全部文件失败时不会调用 embedding，部分失败保留逐文件结果。`force` 通过 UUID 游标读取当前 chunk，provider 成功后逐条覆盖旧向量，不预先清空。`index.vision` 在写入图片描述前校验 source revision，避免旧结果覆盖新文件。

## 6. 任务与 Worker

任务通过 PostgreSQL 状态机和租约运行：

- `FOR UPDATE SKIP LOCKED` 领取 queued/retry_wait 任务；lease 和 heartbeat 防止 Worker 崩溃后永久卡住。
- 用户触发的 cancel/retry 由 `TaskStore.TransitionResult` 统一表达任务快照、是否实际迁移和稳定原因。Mapper 先执行带状态条件的 UPDATE：不存在的 retry 返回 404，不可重试返回 409；重复取消保持幂等 200，不刷新终态时间戳，也不重复写 `cancel_requested` 事件。running 任务收到取消后不再接受进度、续租或成功迁移，handler 停止后由 fail 收敛为 cancelled。HTTP 与 `backend_api` 使用同一结果语义。
- `(user_id, dedupe_key)` partial unique index 保证每个 owner 内的活跃 dedupe，不同 owner 可使用相同 key；Worker 按阶段/文件/embedding 批次节流写入 `progress_current`、`progress_total`、`progress_message`，进度更新与租约续期同一条原子 SQL 完成，并通过 `progress` 事件通知前端；相同进度不重复写事件。API 只统计顶层任务，子任务进度汇总到父任务。
- Worker 的 schedule、outbox、task 三个 tick 阶段分别隔离异常。schedule 写入先校验类型、表达式和时区；5 字段 cron 转为 Spring 6 字段并按计划时区计算真实命中，interval/daily 保持各自语义。任务类型和 lane 会裁剪、空白 lane 规范为 `default`；派发先计算下次运行，再把 priority/max_attempts 传给任务入队。历史非法计划写入 `last_error` 后禁用，不阻塞同批其他计划。
- `outbox_events` 可靠投递文件变更和索引任务；不可恢复的坏类型、payload、action 或路径记录失败次数、最后错误和 `dead_lettered_at`，瞬时入队失败保留 pending 重试，只有入队成功才标记发布。Worker 每 2 秒刷新 `task_workers`，API 以最近 10 秒心跳判断在线。
- 任务列表接口通过多取一条返回 `has_more`，前端任务页按此加载更多记录；任务事件从尾部订阅，不回放全库；终态历史保留最近记录并由维护任务清理过期数据。
- 自动维护通过 owner-scoped 的 `POST /api/v1/tasks/prune-history` 按固定策略清理 30 天前的终态任务，至少保留最近 2000 条；它不代表用户手动清理的语义。
- 任务页的 `POST /api/v1/tasks/clear-terminal` 清理当前 owner 全部可安全回收的完成、失败和已取消记录；`DELETE /api/v1/tasks/{taskId}` 删除单条终态任务，删除终态父任务时会一并删除已结束子任务。数据库递归保护活动任务及其祖先，父任务存在活动后代时返回冲突，避免留下执行中的孤立任务。

当前任务类型包括 `index.file`、`index.embed`、`index.vision`、`index.rebuild`、`index.cleanup`、`maintenance.daily` 和 `automation.run`。HTTP 请求只入队，不串行执行 OCR、embedding 或 vision。

## 7. 前端与 Android

Next.js 16 使用静态导出，生产由 Java API 托管 `frontend/out`。前端分为认证门控、Chat、文件、任务、会话、设置、Skill 和设备/同步页面；API client 统一处理身份、GET 缓存隔离、401 和事件总线。

文件页的列表、详情、全文、索引和回收站刷新使用独立请求代次与当前路径校验，只有当前请求失败才显示 toast。ChatPanel 保持常驻挂载；初次 `authMode=loading` 才显示整页 Skeleton，下拉刷新不得因 store.loading 卸载工作区。`useChatStream` 作为流式 `busy` 的唯一状态源，负责 Abort、流代次和 80ms 帧节流。ChatPanel 的会话历史、聊天模型目录，以及 SettingsPage 的 LLM/视觉模型目录请求都必须在响应提交前校验请求代次和当前配置边界，避免切换会话或接口后迟到响应污染界面。UI 控件和主题遵循 [`frontend-design.md`](frontend-design.md)。

Android 是 Capacitor 7 原生壳：ServerConfig/PhotoSync 插件接入扫码配对、加密令牌、WorkManager、MediaStore 和通知。相册同步使用秒级 checkpoint、pending second/id、服务端 dedupe 预检和 MD5 校验；本地文件消失/权限拒绝永久跳过，其他本地 I/O 冻结水位，线程中断保留中断位并终止本批。同步配置写入独立 EncryptedSharedPreferences，失败关闭，不降级明文。

## 8. 生产运行与验证

- API：`deploy/agent-drive-java.service`，`127.0.0.1:8000`，`--app.mode=api`。
- Worker：`deploy/agent-drive-java-worker.service`，`--app.mode=worker`，无 HTTP 监听。
- artifact：`/opt/agent-drive-java/agent-drive-backend.jar`；数据根：`/opt/agent-drive-java/data`。
- 密钥：`/etc/agent-drive-java/java.env`，权限 0600；外部 HTTP(S) 代理来自 `/etc/agent-drive/proxy.env`，systemd 清除 SOCKS 环境变量。
- 公网：nginx `13311` → API `8000`；生产部署优先使用 `scripts/deploy.ps1`，它负责构建、原子替换、unit 校验、API → Worker 重启和 health 检查。

详细认证和暴露面规则见 [`security.md`](security.md)，生产切换证据见 [`java-migration-architecture.md`](java-migration-architecture.md)。

## 9. 当前边界

当前只支持 owner-scoped 本地文件系统，不提供 S3；认证模型面向个人单用户，虽已按 owner 设计数据边界；没有 iOS 客户端和音视频转写。上述能力不应在文档或 Agent 契约中被描述为已实现。
