# AGENTS.md — Agent Drive 项目维护手册

> 给本仓库的编码 Agent 与维护者：这是一份"项目级 skill"。修改代码前先读本文件；
> 改动完成后按「修改检查单」自查，保持文档一致。

## 0. 铁律：文档与 skill 实时同步

**任何代码/行为/流程变更，必须与代码同一次提交内更新：**

1. 相关文档：README、docs/architecture、docs/android、docs/security（按改动范围选择，原则是"文档与实现永不脱节"）
2. 本文件（AGENTS.md）：新增的坑位、变更的流程、新的约定——即时记录，而不是事后补
3. 历史快照类文档（quality-report/review-*）除外，它们按日期存档不随实现更新

违反此条 = 提交不合格。禁止"先改代码，文档下次再说"。

## 1. 项目定位与文档地图

Agent-first 私人网盘：Java 21 + Spring Boot 后端 + Next.js 16 前端（静态导出）+ Capacitor 7 安卓原生壳。

| 文档 | 内容 |
|------|------|
| `README.md` | 总览/快速开始/项目结构 |
| `docs/product.md` | 产品定位、用户流程、功能地图和当前边界 |
| `docs/product-design.md` | 面向设计模型的产品与业务说明，不规定 UI/UX 方案 |
| `docs/README.md` | 文档索引、现行说明与历史快照边界 |
| `docs/architecture.md` | 分层架构、模块职责、扩展点 |
| `docs/security.md` | 认证模型（密码/会话/设备令牌/扫码配对）、暴露面、运维 |
| `docs/android.md` | 安卓原生壳方案、构建发布、同步机制 |
| `docs/frontend-architecture.md` | Next.js 前端分层、状态、请求生命周期和验证 |
| `docs/frontend-design.md` | 现行 UI/UX 设计规范（控件清单/排版/反馈/反模式，shadcn 主题映射） |
| `docs/agent-definition.md` | Agent 设计规范 |
| `docs/java-migration-architecture.md` | Java 后端现行边界及已完成迁移/切换记录 |
| `docs/archive/quality-report*.md` / `review-*.md` | 历史快照，只读 |
| `docs/quality-analysis.md` | 工程质量分析 Agent 协议（只读分析→先汇报→确认后修改） |

## 2. 常用命令

```bash
# Java 后端
cd backend
mvn -q test
mvn -q -DskipTests package

# 生产 smoke 通过 systemd units、API health 和 nginx health 验证；legacy-python-data/ 只供显式 migrate/人工恢复，不进入生产运行时

# 前端
cd frontend
npm run lint
npm test
npm run build                  # TS 类型检查 + 静态导出 out/

# 安卓 APK（仅测试 App 业务或发版时构建；日常功能迭代不打包）
# 本机环境：JAVA_HOME=Temurin 21，ANDROID_HOME=C:\Android\Sdk，Gradle 8.14.3
cd frontend/android && gradlew.bat assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（keystore.properties 就位则自动签名）
```

## 3. 部署与发布流程

1. **固化版本**：先检查工作区并提交可复现的变更；禁止把未核对的历史迁移改动与当前修复混在一次提交中。部署脚本不会自动 `commit` 或 `push`。
2. **推荐发布**：执行 `pwsh -File scripts/deploy.ps1 -Target frontend` 或 `-Target all`。脚本从当前工作区构建，递增 Service Worker cache，使用 tar 全量上传并原子替换静态目录；`all` 还会安装 Java artifact/unit、运行 `systemd-analyze verify`、按 API → Worker 重启并检查 health。
3. **前端边界**：不要用 PowerShell 通配符 `out\*` scp，必须保留 `.well-known/assetlinks.json`；APK 只在 App 测试或发版时构建，日常部署保留已有 `out/app/agent-drive.apk`。服务器原地重建可用 `bash deploy/rebuild-out.sh`，但仍需先确认备份和当前分支。
4. **首次安装/unit 变更**：复制 API、Worker 和 `agent-drive-java-backup.service/.timer` 到 `/etc/systemd/system/`，执行 `systemd-analyze verify`、`daemon-reload` 和 `enable`；`/etc/agent-drive/proxy.env` 从模板创建并 chmod 0600，只允许 HTTP(S) 代理。
5. **数据备份**：`agent-drive-java-backup.timer` 每日执行 `scripts/backup-java.sh`，把 PostgreSQL dump 与 owner 文件根归档到 `/opt/agent-drive-java/backups/`，保留最近 7 份并生成 SHA-256 校验文件；仓库不再提供旧 Python/SQLite 定时备份入口。若仍需读取 legacy SQLite，只能经 SQLite backup API 生成一致快照，禁止直接打包活动中的 WAL 三件套。

**交付门禁**：代码或运行行为改动完成后，默认继续执行对应测试、构建、生产部署和 health 检查；文档-only 改动不重建 artifact，但必须通过文档一致性检查。只有用户明确要求暂不发布，或部署被外部条件阻断时，才停在本地验证并说明原因。

## 4. 关键约定与坑位（改动前必读）

- **Capacitor 插件注册**必须在 `super.onCreate()` 之前（否则 JS 拿不到原生实现，表现是静默退回默认地址）
- **BridgeActivity 生命周期约束**：`onResume` 是 final，回前台心跳走前端 `visibilitychange` → 插件 `heartbeat()`；`onDestroy` 覆写必须保持 public。MediaStore observer 用 Activity 字段持有、只注册一次，并在销毁时注销且清 debounce callback
- **keystore.properties 必须无 BOM**：PowerShell 写 Properties 用 `[System.IO.File]::WriteAllText` + `UTF8Encoding($false)`；反斜杠双写、冒号转义
- **上传接口约定**：`path` 是查询参数；`md5`（服务端必须复算验证）与 `noclobber`（同名自动序号）是表单字段；multipart 文件 part 名为 `file`。请求体按块写 0600 temp，禁止重新读回内存拼接
- **免传预检**：Android 先 `GET /files/dedupe?md5=...`；只允许 `verified=true` 且文件 revision 仍匹配的服务端实算条目命中。预检 GET 无副作用；真正上传始终复算 MD5，勿重新信任客户端 hash。发布成功后的去重索引登记是优化项：失败只记 warning、不得把已上传文件伪报为失败（否则客户端重试会经 noclobber 落成重复照片）
- **MediaStore DATE_ADDED 是秒级**：`lastSyncAt` 只推进到「整秒全部成功」的秒；同秒有失败/未取完挂 `pendingSecond+pendingMaxId`（_ID 连续水位）续传。首次失败后水位冻结，之后成功项靠秒传重试；第 201 行仅作截断哨兵、不上传；完整检查点一次 commit。勿改回严格 `> 检查点` + 单张推进
- **上传去重**：生产去重索引是 PostgreSQL 的 owner-scoped `upload_dedup(user_id, content_md5, path, file_revision, verified)`；命中必须同时校验 owner、metadata、物理普通文件和 revision，失效行在读取时自愈删除。文件写入、移动、复制、删除和覆盖都要让旧 revision 的 dedupe/全文/向量失效，不能恢复 JSON sidecar 索引或绕过数据库事务。
- **持久任务库**：Java PostgreSQL V7-V11（owner-scoped tasks/schedules/outbox、Worker 在线心跳、失败隔离 + 0600 credentials）是唯一生产任务状态源；生产 API 使用 `--app.mode=api` 且不内嵌 Worker，由独立 `agent-drive-java-worker.service` 执行；Worker 每 2 秒刷新 `task_workers`，API 只把最近 10 秒有心跳的进程计为在线；legacy SQLite 只作受控迁移/回滚输入
- **Worker 在线状态**：任务租约的 `tasks.heartbeat_at` 用于防止长任务过期；进程在线状态独立记录在 `task_workers`，Worker 启动后每 2 秒刷新，正常关闭时删除登记，异常退出则由 API 的 10 秒窗口自动过期。
- **Worker 失败隔离**：每个 tick 的 schedule、outbox、task 三阶段必须独立捕获运行时异常，前一阶段失败不得跳过后续阶段。schedule 写入先严格校验 `cron/interval/daily`、正整数 interval、`HH:mm` daily 和 ZoneId，5 字段 cron 规范为 Spring 6 字段并按计划时区计算真实下一次命中；裁剪 `task_type/lane`、把空白 lane 规范为 `default`，再计算首次 `next_run_at`。派发必须在任务入队前计算下一次时间，并把 schedule 的 `priority/max_attempts` 写入任务。历史非法计划写 `last_error` 并禁用，不能反复占据到期队首。
- **任务状态机**：只经 `TaskStore`/`TaskWorkerStore` 做 queued/running/retry_wait/cancelling/terminal 迁移；用户 cancel/retry 返回 `TaskStore.TransitionResult(task, changed, reason)`，Mapper 必须先做带状态条件的 UPDATE 再查询失败原因，HTTP 与 Agent 入口统一映射不存在/不可重试；终态或已 cancelling 的重复取消保持幂等，不刷新时间戳、不重复写事件。running 一旦 `cancel_requested`，进度、续租和 succeed SQL 必须拒绝，handler 立即停止并由 fail 收敛到 cancelled，不能继续做重任务或落成 succeeded。领取使用租约+心跳，租约过期必须遵守 max_attempts；停机 release 遇到 cancel_requested 必须落为 cancelled，勿留下不可领取的 queued 任务
- **任务去重与进度**：活跃 dedupe_key 由 PostgreSQL owner-scoped partial unique index `(user_id, dedupe_key)` 保证；`INSERT ... ON CONFLICT` 必须使用同一列和谓词，不得让不同 owner 相互去重。Worker 对全文索引按文件、embedding 按批次报告 `progress_current/progress_total/progress_message`，进度更新必须与当前租约续期同一条原子 SQL 完成，并按 250ms 左右节流写入 `progress` 事件；相同进度不重复写事件。无游标 SSE 从事件尾部订阅，勿回放全库。终态历史每日保留至少最近 2000 条并清理 30 天前旧记录；子任务仍保留时不得先删父任务
- **outbox 死信**：`file.changed` 只接受有效 owner/数据库 ID、对象 payload、`upsert/delete/move/copy` action 和非空安全路径；不可恢复事件累加 `failure_count`、写 `last_error/dead_lettered_at` 并从 pending 隔离，瞬时入队异常只记录失败并保留重试。任务成功入队后才能 `published_at`，禁止坏 JSON 降级为空 payload、空路径任务或未知事件静默发布。
- **通用 Agent 工具**：生产注册 `backend_api`、`frontend_api` 和只读 `read_skill`（另有 plan 辅助工具）；前两者只有稳定的 `discover/call` envelope，不为每个后端路由或 React handler 单独注册模型工具。`backend_api` 先发现 `METHOD /api/v1/path` 或 `INTERNAL name`，再调用精确 operation；discover 使用 `discovery_offset/discovery_limit` 稳定分页（默认 6、最大 20），响应必须包含 `total_matches/returned/offset/limit/has_more/next_offset`，只要 `has_more=true` 就不能把当前页表述为完整目录。中文“后端/接口/操作”等领域词在目录层统一规范化。`frontend_api` 的能力来自当前浏览器 registry，discover 只返回匹配动作，call 只允许当前 registry 中的 exact operation，绝不接受 JavaScript 函数名、`eval`、任意 URL、请求头或凭据。前端动作成功后以 `frontend_action` SSE 事件交给本地 handler，前端再次按 registry 和路径规则校验。HTTP 目录排除 auth/chat/health 和外部 URL；模型不能提供 Cookie、Bearer、Authorization、任意请求头、Python/Java 入口或未登记 operation。`POST /api/v1/tasks/embed-index` 的 `body.files` 必须是相对文件路径 list，最多 1000 项，调用只入队 `index.embed`，不得在 Agent 请求内串行抽取或向量化；`POST /api/v1/tasks/vision-index` 同样只接受最多 100 个 owner 内相对图片路径并入队 `index.vision`，`POST /api/v1/vision/describe` 只返回固定视觉描述。当前请求的认证 owner 由 Java runtime 注入工具上下文，模型不能获得 Cookie、Bearer、API key 或其他凭据。
- **Skill 系统**：V13 `agent_skills` 是 owner-scoped 自定义 Skill 真相源；名称统一为 1-64 位小写 slug，每 owner 最多 100 个，description ≤500、instructions ≤16000，保存递增 version。`read_skill` 只分页发现和读取当前 owner 已启用 Skill，不参与 replay、不执行任意代码；自定义创建/更新/启停/删除只走认证 `/api/v1/skills` 或 red `backend_api` operation，已知 key/Bearer 模式落库前不可逆脱敏。`agent-drive-api` 由当前 `OperationCatalog` 动态生成，`skill-authoring` 固化 CRUD/校验规则；两个内置 Skill 都只读且不占 owner 配额。Skill 指令只能编排已登记工具，不能引入任意 URL/header/credential/脚本或扩大 owner 权限。
- **Code Graph RAG 索引**：MCP 的 `index_repository/update_repository` 必须同时传入 `.gitignore` 与 `.cgrignore` 的排除规则；当前仓库的 Android 静态导出目录由 `.cgrignore` 排除，避免构建 bundle 污染死代码结果。Tree-sitter grammar 或工具版本变化后，移走 `.cgr-hash-cache.json` 再做完整重建，不能把旧缓存当作完整索引。
- **通用 API 风险**：GET 和只读探测自动执行；实际写操作按 operation 动态为 yellow/red，red 必须经过签名确认和确定性重放。Spring WebFlux/Bean Validation 负责路径、查询、JSON/form/multipart Schema 校验；响应统一脱敏，二进制只返回元数据，discovery 通过最多 20 项的单页上限保持在 Agent 工具输出预算内。
- **向量配置 API**：`backend_api` 发现并调用 `PUT /config/embeddings`，语义与设置页一致——provider 仅 jina；api_key 留空仅当 provider/base_url/model 与已存配置一致时才沿用已存 key（改配置必须重填）；保存后测试连接，成功则 refresh_embedder + 入队 index.rebuild。需要指定文件时调用已登记的 `POST /tasks/embed-index`，body 使用 `files: string[]` 和可选 `force`，任务会按最多 64 个 chunk 一批持续处理。状态查询必须报告 embeddings 状态（configured/provider/model/api_key_masked），勿让模型凭空声称已配置。视觉模型使用独立的 `GET/PUT /config/vision`；`POST /vision/describe` 最多接收 16 张图片并返回 `image-description-v1`，`POST /tasks/vision-index` 最多接收 100 个图片路径，成功后才由 Worker 写入当前 revision 的文档和向量。
- **设置页密钥生命周期**：SettingsPage 的 LLM/视觉协议或 base_url 改变时必须清空对应 `api_key` 草稿，embedding 的 provider/base_url/model 任一改变也必须清空；保存成功及脱敏配置重载后从 React 状态销毁明文 key。模型目录探测只使用当前边界内的表单快照，禁止把旧草稿 key 发往新地址。
- **会话/记忆落库脱敏**：会话消息（user/assistant/tool_call）、meta.last_trace、每日笔记落库前过 `redact_text`/`redact_value`（core/logging，含裸 jina_/sk- 令牌模式）；审计层脱敏不等于会话层。例外：`pending_confirmation.arguments` 因签名校验+确定性重放必须保留原文（0600 私有），其 message 提示文案必须脱敏；yellow 工具不得携带密钥参数（last_trace 脱敏后会破坏参数匹配重放）
- **模型正文与工具调用**：Java runtime 只接受 Provider 原生 `toolExecutionRequests` 作为工具调用；正文中的 DSML/XML 片段按普通文本处理，不能触发工具。标题生成只采纳 user/assistant 的普通正文并忽略 reasoning；如果将来增加正文清洗或拦截，必须同步 Java chat 和前端 SSE/渲染契约测试。
- **工具结果展示**：Java runtime 必须使用完整工具输出解析结构化 `parsed`，不能先用摘要截断 JSON；前端对象型 `parsed` 渲染 pretty JSON，参数和确认卡使用 `maskSecretsJson`（键名含 key/token/secret/password/authorization 的值掩码）。输出截断只能用于日志或兜底摘要，不能成为解析输入。
- **任务/聊天路由**：Java runtime 以本轮是否产生工具轨迹标记 `chat`/`task`；工具请求只能来自 Provider 原生 tool call。当前没有旧版短消息强制分流规则；若调整续接语义，必须同步 runtime、会话存储和 ChatPanel 回归测试。
- **对话面板常驻挂载**：page.tsx 对话主区用 CSS hidden 切换而非条件渲染——ChatPanel 卸载即丢消息流/工具步骤（remount 不自动重载会话）。工具步骤后追加回复/流结束/停止三处都要清掉发送时挂的空助手占位气泡，勿留空白气泡（回归见 ChatPanel.test.tsx）
- **全局刷新挂载边界**：整页 Skeleton 只由初次 `authMode=loading` 控制；下拉刷新虽更新 `store.loading`，但不得用它提前 return 卸载工作区，否则会丢失未发送草稿、消息流和工具步骤（回归见 `page.test.tsx`）。
- **索引任务链**：Java storage service 在文件写/移动/复制/删除后通过 owner-scoped `file.changed` outbox 触发 `index.file`、`index.rebuild` 或 `index.cleanup`；`index.file` 成功后由 Worker 继续完成该文件 embedding。显式 `index.embed/index.vision` 的 `vectorized=false` 必须进入任务 fail/retry，配置存在后的 provider/持久化失败也不能让 `index.file/index.rebuild` 伪报成功；只有 `embedding_not_configured` 可作为全文成功的可选降级。视觉全失败不得把空路径传给 embedding（空列表表示全盘），逐文件容错不得吞中断；部分失败保留逐文件结果。`force` 使用 UUID 游标读取并在 provider 成功后逐条覆盖，禁止先清空旧向量。`POST /tasks/embed-index` 入队带 `files` 列表的 `index.embed`，`POST /tasks/vision-index` 入队 `index.vision`，图片描述必须绑定处理时仍匹配的 source revision；全量重建是 `index.rebuild` 父任务 + index lane 子任务，禁止在上传请求或 Agent 工具内串行跑 OCR/embedding/vision。
- **文件语义搜索**：`GET /api/v1/files?path=&q=&mode=semantic` 先用 Jina `retrieval.query` 生成查询向量，再按当前 embedding fingerprint 在 pgvector 中检索；SQL 必须按文件去重并返回最佳 chunk 的 `search_score/search_snippet`，普通 `mode=name` 搜索保持名称/路径包含匹配。未配置 embedding 或 provider/index 不可用时返回稳定的 409/502 detail，不能伪造空的“已向量化”结果；Agent 调用同一 operation 时把 `q`、`mode` 放入 query_params。
- **任务中心口径**：列表/状态计数只算顶层任务，子任务汇总进父任务；列表接口通过多取一条返回 `has_more`，前端不能按“返回条数等于 limit”猜测是否还有记录；`vector_stats` 是全盘扫描，任务总览必须保留 15 秒缓存，文件变更或 embedding 指纹变化时在本进程失效；`POST /api/v1/tasks/prune-history` 只供自动维护，按服务端固定策略清理 30 天前的终态历史并至少保留最近 2000 条；任务页的 `POST /api/v1/tasks/clear-terminal` 清理当前 owner 全部可安全回收的终态记录，`DELETE /api/v1/tasks/{taskId}` 只允许删除终态任务，父任务存在活动后代时必须保护，安全删除父任务时可连同终态子任务一起删除；运行中/等待中的任务不可清理
- **向量有效性**：全文元数据记录 `source_revision + extractor_version`；向量 chunk 记录 `source_revision + embedding fingerprint + chunk_version`，统一保存在 PostgreSQL/pgvector。文档用 `retrieval.passage`、查询用 `retrieval.query`；revision、chunk 版本或 embedding fingerprint 不匹配时视为失效，不能参与语义检索。
- **同步检查点**：整秒完成后才推进 `lastSyncAt`；失败不阻塞整批但不得让更晚秒越过最早失败秒，Worker 按 lastError 退避重试；MediaStore query/Cursor/字段读取和 checkpoint commit 异常也必须保留当前/已有 pending，不能把部分成功当成已提交。每行先读 DATE_ADDED 并以此真实秒 `begin`，再读 _ID 等其余字段——字段异常必须落在该真实秒的 pending 上，不得把已完成的上一组误标失败。`lastSyncAt/pending*` 必须同一次加密 prefs commit。周期/快速/手动任务可能使用不同 unique work 名，`SyncEngine.sync()` 必须保持进程内串行
- **noclobber 原子独占**：Java 上传请求体先流式写入受控临时文件并 fsync，`publishUpload` 在 storage lock 内用独占移动发布；覆盖上传先把旧目标移入 owner 内隐藏 backup。上传、移动、复制、移入回收站和恢复的 storage lock 必须持有到 Spring 事务 afterCompletion：提交后清理 backup，回滚/提交失败恢复发布前磁盘状态；无事务调用立即收尾。提交后的 artifact 清理失败只记 warning，不得把已发布文件伪报失败。覆盖移动和复制拒绝文件↔目录混型并返回 409；勿退回“先 exists 再普通写”的 TOCTOU
- **设备注册表写入**：生产设备 metadata、sync_state 与 revoked_at 写入 Java PostgreSQL 事务；本地 owner 文件发布仍使用 0600 temp + fsync + atomic replace，失败清理 staging。
- **原子文本、目录复制与回收站**：write/append 都是 temp+fsync+replace；append 的读-改-写由 RLock/flock 包围。Java 目录复制先在 owner 根下隐藏 `.copy.*.staging` 完整构建并 fsync，再用同根独占移动发布；覆盖前 durable 写 `.copy.*.txn.json`，旧目标暂存为 `.copy-old.*`，启动恢复未提交旧目录或清理已发布 backup。marker/backup 清理失败不得伪报已发布复制失败；无 marker 的 `.copy-old.*` 无法证明可删，必须保守保留。文件↔目录类型混型返回 409，目标不得是 owner 根目录。回收站每次删除有唯一 `trash_id`，恢复传 trash_id（兼容旧 path），孤儿 metadata 不展示并可清理
- **resolve 拒绝符号链接与内部路径**：组件级检查（业务从不产生 symlink），下载/预览/上传共用；`.index/.trash/.storage.lock` 与 `.upload/.copy` staging 的公共访问必须拒绝，不只是列表隐藏。内部流程使用显式 `allow_internal`，列表/摄入/任务变更必须跳过
- **设备令牌加密存储**：现行数据只写独立 `agent_drive_secure` EncryptedSharedPreferences（AES256-GCM/SIV，MasterKey 在 Keystore）+ `allowBackup=false`。升级识别旧 `agent_drive` 明文和 1.0.27 同文件密文：同键以 1.0.27 持续写入的密文为现行值，明文只作更早来源/清理残留；独立新密文若与 legacy 现行值冲突则保留双方并失败关闭。新密文 commit 成功或逐键确认相等后才清理旧业务数据，AndroidX keyset 永不 clear，清理失败下次幂等重试；初始化/迁移/commit 失败必须弹窗或 reject/Log+retry，绝不能吞异常或降级明文
- **显式编码**：Java 文件 API 使用 UTF-8 与 POSIX 路径语义；文本预览必须用 REPORT 严格尝试 UTF-8→GBK→ISO-8859-1，不能让替换字符使 UTF-8 伪成功；字节上限截断时只允许丢弃末尾未完成码点。用户可见文本与内部临时文件保持明确编码，跨平台发布固定换行与原子写入。
- **脚本按端归属**：后端一次性运维/诊断脚本放 `backend/scripts/`，统一使用 Java 21；前端构建/静态产物脚本放 `frontend/scripts/`，统一使用 JavaScript/TypeScript；跨前后端的部署、备份和 QA 编排才放仓库顶层 `scripts/`。
- **CI（GitHub Actions）**：backend = Maven test/package；frontend = eslint + vitest + build；android = gradle testDebugUnitTest（JVM 单测）。gradlew 可执行位已入库，本地可直接 ./gradlew；ESLint 配置忽略 android/ 构建产物，不得恢复全目录裸扫
- **Vitest ESM 配置**：使用 `vitest.config.mts` + `import.meta.url` 解析别名；勿改回含 ESM 语法的 `.ts`/CommonJS 加载方式（未来 Vite native config loader 不支持）
- **上传大小上限**：`max_upload_mb=300`（后端 413；公网闸门仍是 nginx 200m）——直连 8000 的滥用兜底
- **健康检查**：`/api/v1/health` 公开豁免（探活用，不泄露业务信息）
- **日志统一出口**：Java 使用统一 SLF4J/Logback 输出，敏感字段按 key/value 脱敏；health 只输出必要探活信息，Authorization、Cookie、设备 token 和 query credential 不进日志。nginx 对 raw/download 关闭 access log，避免 `?token=` 落盘；`X-Forwarded-For` 必须由 nginx 覆写为单个 `$remote_addr`，Java 只在 TCP 对端为 loopback 时信任合法 IP 字面量。日志行为变更需补 Java API/Worker 测试。
- **聊天流日志链**：`ChatController` 复用安全的 `X-Request-ID`/`X-Correlation-ID`/`traceparent`，缺失时生成 UUID，并把 request_id 只在服务端 `ChatRequest` 内传到 runtime 和 `backend_api`。聊天日志必须覆盖 stream start、provider/model resolution、model step、tool start/end、done/error/cancel/disconnect；参数只写键名/数量摘要，异常 message/cause 与 SSE error 先经 `SensitiveDataRedactor`，终态状态机不得把 done 误报成 cancel/error。生产按 `journalctl -u agent-drive-java.service | rg 'request_id=...'` 检索。
- **日志持久化**：Java API/Worker 统一使用 SLF4J/Logback 输出到 systemd journal；需要审计或聊天链路时按 request_id、时间和级别查询对应 Java unit，不恢复旧 JSONL 审计文件轮转逻辑。
- **日志查询**：服务器使用 `journalctl -u agent-drive-java.service` 或 `journalctl -u agent-drive-java-worker.service`，按时间与级别过滤，禁止输出敏感环境变量。
- **限速内存态**：仅适用单 API 进程部署（独立任务 Worker 不承载 HTTP）；check_rate 已做过期 key 清理（>1000 触发全量清扫）
- **API Key 掩码只显前缀**（绝不回显尾部）；provider key 只以 AES-GCM ciphertext 写入 PostgreSQL，master key 只在 0600 环境文件。
- **认证配置失败关闭**：Java PostgreSQL users/sessions/devices/pairing_codes 读取或迁移异常必须拒绝启动并保留数据库备份；密码与 credential 只存 hash，变更走事务，勿静默当作未初始化。
- **同步断网中止**：SyncEngine 连接失败/401/403/5xx 抛 AbortBatchException 整批中止（勿改回 200 张串行超时）；永久 4xx（400/413/415/416/422）按“跳过”推进连续水位、不设 lastError 不触发重试，其余 4xx 视为可能瞬时并冻结水位下轮重试；本地 `FileNotFoundException/SecurityException` 同样永久跳过，其他本地 I/O 冻结水位，显式 `InterruptedException` 或线程中断必须重设中断位并中止整批。中止时当前秒组同样挂 pending 续传。响应实体必须先 drain 再 disconnect，保持连接可复用。**HTTP 分类集中在 `classifySyncStatus(code, upload)`，本地分类集中在 `classifyLocalMediaFailure(error, interrupted)`，同秒续传 SQL 在 `buildResumeSelection()`——改分类/SQL 必同步补 SyncEngineTest 分支断言（注意 dedupe 仅 200 命中、upload 404 归可重试两处差异）
- **同步配置与观察者**：PhotoSync.configure 的 enabled/wifiOnly/interval/folder 必须一次加密 prefs commit；多个调用放入专用单线程执行器，从写入到 WorkManager Operation 入库结果全程串行，绝不能在桥接/UI 线程 `Future.get()`。调度失败保留已提交的期望状态、明确 reject，由下次启动 `ensureScheduled` 幂等收敛；禁止伪回滚未知 WorkManager 副作用。挂起的权限回调在 Activity 销毁或新请求到来时必须 reject/替换，不能留旧 PluginCall 解析。ContentObserver 保持 1 秒防抖，字段持有且 Activity 销毁时注销/清 callback，避免重建泄漏和重复快速同步
- **通知权限非致命**：相册权限判定只看 READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE（通知被拒不算失败）
- **错误语义化**：Java file controller 将 404/409/403 与存储 I/O 故障映射为稳定 API detail/status；瞬时存储、数据库或 provider 故障不得伪装成客户端 4xx，Android 只跳过明确永久 4xx。
- **/api 404 保持 JSON**：FrontendSpaFallbackWebFilter 对 `api/` 前缀保留 JSON 404，前端路由 fallback 只处理非 API deep link。
- **SPA 静态文件边界**：FrontendResourceConfiguration 与 fallback 拒绝 `..` 越界路径，并要求 resolve 后仍在 frontend/out；越界返回 JSON 404。
- **extract 不进请求路径**：上传只持久化并入队；Java index.file handler 在 Worker 中异步处理 Tika/Tesseract，单个文件抽取失败必须记录 skipped，不得阻断全量 rebuild。
- **生产代理边界**：API/Worker 都读 `/etc/agent-drive/proxy.env` 的 HTTP(S) proxy；systemd 用 `UnsetEnvironment=ALL_PROXY all_proxy` 阻止 SOCKS 继承。Jina 在服务器直连会超时，勿移除
- **systemd unit 语法**：`StartLimitIntervalSec/StartLimitBurst` 放 `[Unit]`；systemd 不支持指令值后的行尾注释（会把注释当值并忽略安全项）。unit 变更部署前必须跑 `systemd-analyze verify`，Agent Drive unit 不得出现 warning
- **前端 GET 去重与身份隔离**：cache key 必须含 API base + credential generation + cache generation + path；凭据代次与缓存代次分离，旧 in-flight 不得写入新身份/新缓存或派发迟到 401。每个非 GET 在请求开始和结束（成功、HTTP 错误、网络异常、Abort）都独立失效，交错写不能跳过结束清理
- **前端启动错误语义**：`/auth/status` 或 `/config/status` 只有 401/403 才进入 web 登录/原生重扫码；5xx、网络、JSON 解析、布尔字段契约及其他服务错误进入可重试的 `server-error`，不得清除 Cookie/设备令牌或伪装成 AI 未配置。重试必须重新执行完整启动检查。
- **原生登出语义**：先清 EncryptedSharedPreferences 再清进程令牌；安全存储清理失败必须停留并报错。离线/5xx 本地退出后只提示“服务端吊销状态未知”，401/403 视为凭据已不可用，勿误报旧令牌仍有效
- **chat SSE 解析**：前端 `chatStream` 必须处理 LF/CRLF/CR、换行跨 chunk、UTF-8 码点跨 chunk、多行 data 和无终止空行的尾事件；401 与普通 API/上传共用 `EV.unauthorized`，错误保留 Java 后端 detail。**线上契约：每个 SSE data 必须是 JSON 对象**（前端解析器拒绝裸字符串）——text/reasoning 事件形状均为 `{"text": str}`，Java `ChatSseEvents`/`ChatSseEncoder` 负责保持对象契约，回归见 `ChatControllerContractTest`、`ChatContractTest` 和前端 `chat.test.ts`。
- **对话思考等级/过程**：输入区的思考等级由 `auto/low/medium/high` 组成，默认 `auto` 以保持普通模型兼容；显式等级通过 `ChatRequest.thinking_level` 传到 Provider。Provider 输出的 reasoning 必须走独立 SSE 事件并存入 assistant 消息的 `reasoning` 字段，ChatPanel 用原生 `details` 默认收叠、允许用户展开；Provider 未返回 reasoning 时不伪造思考内容，reasoning 不进入下一轮 history。**OpenAI/OpenAI 兼容流式模型必须 `returnThinking(true)` 构建，否则 langchain4j 静默丢弃 `reasoning_content`，思考过程既不显示也不落库（生产实测：DeepSeek/Qwen/o 系兼容网关；回归见 ProviderReasoningStreamContractTest + chat.test.ts）**。
- **聊天模型选择**：ChatPanel 按需通过 `POST /api/v1/config/models` 读取当前 owner Provider 的模型 ID，`ChatRequest.model` 只覆盖本轮请求；Java resolver 必须继续从已保存 owner 配置取得 provider、base_url 和 API key，空模型沿用默认配置，不得让客户端改写连接或凭据。
- **Agent 身份提示**：`LangChainAgentRuntime` 对空提示和自定义 `APP_SYSTEM_PROMPT` 都在正文前后包裹 canonical Agent Drive identity guard；底层 provider/model 名称不得成为助手自称，用户自定义提示不能覆盖两端约束。
- **会话标题与流生命周期**：聊天完成只刷新会话列表，由 `SessionList` 按 session ID 对空标题摘要去重；两次列表写入都必须在同一请求序列内（被取代的旧 load 的刷新不得覆盖新列表），空标题会话被并发 load 碰到且其总结仍在途时必须等待后重拉（防「标题已生成但列表不显示」）；`useChatStream` 是流式 `busy` 的唯一状态源，stop/abort/卸载必须递增 stream generation、清理节流 timer，并阻止旧流回写。ChatPanel 加载会话历史时必须校验请求代次和当前 session，迟到响应只能丢弃。工具步骤是模型轮次边界，前端提交当前轮 reasoning/text 后清空缓存；清理助手占位时必须同时确认正文和 reasoning 都为空，不能删除 reasoning-only 的上一轮。Java `summarizeOwned` 的摘要不得把 null 正文写成 "null"，空摘要不落库、不覆盖已有标题；`java-chat` 对空标题使用当前 owner provider 的 AI 生成不超过 20 字的标题，AI 失败/超时/返回空内容时回退确定性标题，已有标题不重复调用模型，模型返回文本先清理工具标记。
- **chat 流式节流**：ChatPanel 每 80ms 批量刷一帧（streamTimerRef），流结束冲刷最后一帧；异常收尾必须先 flush/cancel 当前帧、保留已生成正文/reasoning/工具步骤，再清理空占位并追加错误消息，勿让迟到 timer 覆盖错误或用错误气泡替换工具轨迹；勿改回逐 token setState
- **模型正文 DSML/XML 边界**：正文中模拟工具调用格式不会进入 Java 的工具执行分支；只有 Provider 原生 tool call 才能生成工具步骤。前端按普通文本渲染正文，不要把旧后端的正文清洗实现重新当作当前契约。
- **前端复用单元**：全站 UI 规范见 `docs/frontend-design.md`（控件清单/排版/反馈/反模式清单）；新控件一律用 `components/ui/`（shadcn，radix 底座），**禁止内联自造复刻**——一个值只允许一个控件（选择+手输并存用 Combobox，禁止 select+input 并列）。业务复用单元：文件预览 `FilePreview`、时间格式化 `fmtTime`、原生重扫 `useRescan`、协议/预设枚举 `lib/llm-options.ts`（新增协议需同步 backend `ProviderType`）、聊天流式发送 `useChatStream`（请求编排在 hook，`busy` 也只由 hook 持有，事件契约/消息状态/80ms 帧/事件分发分别复用 `chat-stream-events`、`chat-stream-state`、`chat-stream-frame`、`chat-stream-dispatch`，ChatPanel 勿内联重建）；模型目录探测必须以请求代次隔离，协议、接口地址或 API key 变化时使旧请求失效；会话列表每条记录必须显示完整会话 ID，长 ID 允许换行；对话工作区的会话列表和文件栏布局统一由 `app/page.tsx` 管理，收缩/拖拽状态按 `workspace-layout-v1` 版本化持久化，分隔轨道逻辑统一复用 `components/workspace/PanelResizeHandle.tsx`
- **聊天输入区**：ChatPanel 输入区保持紧凑，外层不得绘制横跨页面的 `border-t` 或边框，只保留居中的 composer 容器边界；外层 composer 只跟随聊天文本框聚焦，Select 触发器使用自身的轻量键盘焦点反馈，避免下拉菜单关闭后外层输入框残留强焦点态；聚焦反馈只能使用不占布局空间的主题边框/外环；调整间距或尺寸时必须保持自动增高、Enter 发送、Shift+Enter 换行、停止流和思考等级选择行为不变
- **文件页请求生命周期**：`FilePage` 的列表、选中文件详情、完整文本、索引刷新和回收站列表都必须使用请求代次/当前路径校验；迟到响应不得覆盖新目录、新选中项、已关闭回收站或卸载后的状态。只有仍属当前代次的详情/回收站失败才发 toast，过期失败必须静默。目录/文件切换要使旧内容与索引请求失效，文件变更事件负责统一刷新，勿在同一 mutation 后再手动重复 `load`
- **其他前端请求生命周期**：`FilePanel` 的目录列表/文件详情、`TaskPage` 的任务筛选列表/展开详情、`SettingsPage` 的配置刷新都必须使用请求代次校验；快速点击、筛选切换、全局刷新或卸载后的迟到响应不得写入当前状态。任务详情通过 `GET /api/v1/tasks/{taskId}` 读取 payload/result/error/children，结构化展示统一经 `formatJson` 脱敏。对应竞态必须有 deferred response 回归测试。
- **Skill 管理请求生命周期**：`SkillsManager` 的列表和详情使用独立请求代次；搜索、分页、全局刷新、切换 Skill、新建和卸载必须让旧响应失效。内置 Skill 只读且始终启用；自定义 Skill 的保存、启停和删除完成后统一重拉当前查询，迟到详情不得覆盖当前编辑器。
- **移动端预览面板**：FilePage 预览/回收站移动端为全屏覆盖层（`fixed inset-0 z-40 lg:static`），勿改回 `hidden lg:flex`
- **移动端文件工具栏**：`<640px` 保持 3×2、44px 高触控网格；`<360px` 顶栏只视觉隐藏 Agent Drive 文字（保留无障碍文本），320/407px 必须无横向滚动
- **文件选择操作栏**：FilePage 的“已选”区域必须始终保留固定高度；桌面端单行，移动端横向滚动不换行，详情异步加载不能导致文件列表再次下移
- **Next viewport**：Next.js 16 在 `layout.tsx` 用独立 `export const viewport: Viewport`；勿放回 `metadata.viewport`（构建会警告并可能被忽略）
- **版本号**：每次发版 `frontend/android/app/build.gradle` 的 versionCode/versionName 同步 +1
- **PowerShell/SSH 转义坑**：ssh 内嵌 curl 的 JSON 用 stdin 管道（`... | ssh megumin "curl --data-binary @-"`），不要 `\"` 转义；需要执行包含 heredoc、`$()`、SQL 或带空格 header 的远端 Bash 时，先在本地 base64 编码脚本，再用 `ssh megumin "echo <base64> | base64 -d | bash"`，避免 PowerShell/OpenSSH 二次拆词。
- **工程质量分析 agent**：任何质量/可维护性分析按 `docs/quality-analysis.md` 协议执行——架构与技术栈属基线（记录在快照，不频繁重审），每次只做增量检查（风格一致性/耦合重复/门禁/测试缺口/复杂度热点/死代码/文档同步）；分析阶段只读，完成后先出报告等用户确认，只有用户明示“直接修”才动手。禁止“边分析边改”
- **模型列表端点**：POST /api/v1/config/models 只读探测、不落盘；api_key 留空仅当表单 type+base_url 与已存配置一致才回退已存 key（不得把已存 key 发给新填的陌生地址）；20s 超时。视觉模型另有 POST /api/v1/config/vision/models，使用视觉配置自己的 key 回退规则和 OpenAI 兼容 GET /models；两个端点都只返回模型 ID，不落盘、不回显 key。Provider 统一实现 list_models()（OpenAI 系 GET /models、Anthropic GET /v1/models；Ollama 等非标准 /models 形状会返回错误提示，用户手动填写）
- **配置保存 key 回退**：POST /config 与 /config/models 语义一致——api_key 留空仅当 type+base_url 与已存配置一致才沿用已存 key，改协议/地址必须重填（防旧 key 打向新地址）。勿退回"无条件回退已存 key"
- **温度不入配置**：模型请求从不携带 temperature（按各服务商默认），LLMConfig 不存温度、设置页无温度输入——勿恢复温度字段或 UI
- **shadcn 主题映射**：globals.css `:root` 品牌 token 是唯一主题源，shadcn 语义变量全部映射到它；`--card` 是品牌填充色 #f0f1f5（shadcn Card 组件显式用 bg-panel）；`@theme` 里 `--color-accent→accent-brand`、`--color-muted→muted-text`——勿改回直连语义变量，勿在组件写死颜色。`SelectItem/ComboboxItem` 的选中/键盘高亮必须用 `accent-soft + text-text`，禁止 `bg-accent + text-accent-foreground`（两者当前都是深色品牌色，会造成文字不可读）
- **事件总线**：`agent-drive:refresh`（下拉刷新）、`agent-drive:files-changed`、`agent-drive:tasks-changed`、`agent-drive:toast`、`agent-drive:unauthorized`（401 全局拦截）

## 5. Java 后端现行约定

- **单后端与运行模式**：`backend/` 是唯一 Java 21 后端目录；API 与 Worker 共用一个 Maven artifact，通过 `--app.mode=api|worker` 运行，生产分别由 `agent-drive-java.service` 和 `agent-drive-java-worker.service` 托管。禁止运行时调用 Python API；Python source/unit 已删除，legacy data/system 只在显式 `migrate` profile 下作为受控迁移/人工恢复输入。
- **数据库真相源**：结构化运行状态统一进入 PostgreSQL/pgvector；`tasks.sqlite3`、认证/设备/上传索引 JSON 不再是生产真相源。实际二进制文件以及用户可见 Agent 文档暂留 owner-scoped 本地文件系统。
- **Megumin 迁移数据库**：使用独立 `agent-drive-java-postgres`（`pgvector/pgvector:pg16`）容器和 `/opt/agent-drive-java/postgres` 卷，宿主仅绑定 `127.0.0.1:15433`；数据库凭据与随机 AES-GCM keys 在 0600 的 `/etc/agent-drive-java/java.env`，不得复用其他业务 PostgreSQL 或进 git。
- **客户端契约**：当前实现必须保持 `/api/v1`、SSE JSON 事件、Cookie/Bearer/设备令牌、Android 同步协议和前端工具步骤的一致性；任何契约变更先补 Java/前端/Android 测试，再更新专题文档。
- **Java chat/auth profile**：`ChatController` 和 Agent runtime 只在 `java-chat` profile 启用；`java-auth`/`java-chat` 负责 Cookie/Bearer 认证、owner 会话、设备令牌和一次性配对码。客户端 `device_id` 保持 snake_case；setup/login 每个客户端每分钟最多 5 次，pair-exchange 每分钟最多 10 次；配对码有效期 5 分钟且最多保留 3 个未使用码。Chat stream 使用 `event: <name>` + `data: <JSON object>`，保持 `text/event-stream`、`no-cache` 和 `X-Accel-Buffering=no`，流内异常发 `error` 事件并保持 HTTP 200。provider config/test/models、embedding、vision、文件、设备、任务、会话和 `INTERNAL write_text` 都必须经过 owner resolver；API key 只以 AES-GCM 密文落库，空 key 仅在 provider/base_url 一致时复用，probe 不跟随重定向且不回显 key。`AGENT_DRIVE_CONFIRMATION_KEY` 与 `AGENT_DRIVE_LLM_CONFIG_KEY` 必须是 base64 32-byte 密钥。
- **模块化单体**：API 与 Worker 可以是两个 Java 进程，但不提前拆成网络微服务；跨模块一致性通过 application service、PostgreSQL 事务和 outbox 事件处理。
- **详细方案**：技术栈、模块边界、PostgreSQL schema、Agent tool calling、切换记录和验收门禁以 `docs/java-migration-architecture.md` 为准。
- **LangChain4j 工具 Schema**：模型可见参数名必须通过 `@P(name = ...)` 显式声明；Jackson `@JsonProperty` 不负责 LangChain4j tool schema 命名。每个工具必须用 `ToolSpecifications` 断言工具数量和参数字段，避免 camelCase 契约悄然泄漏。
- **Java provider thinking**：OpenAI `low/medium/high` 走原生 `reasoningEffort`，且 `StreamingModelFactory.openAi` 构建的 `OpenAiStreamingChatModel` 必须 `.returnThinking(true)`（构建期属性，默认 false——langchain4j 只有开启后才把流式 `reasoning_content` 转成 `onPartialThinking`/`AiMessage.thinking()`，否则思考过程被静默丢弃）；Anthropic 走 `thinkingBudgetTokens=1024/4096/8192` + `sendThinking/returnThinking`（走请求参数，由 `AnthropicChatRequestFactory` 对显式等级开启）；`auto` 不显式发送，任何请求禁止恢复 temperature。
- **Java tool replay**：非 red 的 `backend_api` call 按 `session_id + tool + arguments` 重放既有结果并标记 `replayed=true`，不得再次触发副作用；discover 没有 operation 风险定义，每次读取当前目录。owner-scoped session service 与 `MybatisChatRuntimeStateStore` 通过 Mapper XML 将精确参数/结果持久化到内部 `chat_tool_replays`，将 user/assistant/reasoning/tool trace 写入脱敏的 `chat_messages`，并把最后 trace 写入 `chat_sessions.last_trace`。`InMemoryToolReplayStore` 只允许测试使用；默认构造器只允许测试使用，profile runtime 必须注入持久 state store。模型调用 backend dispatcher 时，owner UUID 只能由 runtime 内部透传到 dispatcher，不能加入 LangChain4j tool schema 或由模型提供。
- **Java device/session vertical**：`java-auth`/`java-chat` 提供 `/api/v1/devices` 与 `/api/v1/sessions` 的 list/get/delete/register 路由；设备元数据和会话消息只能按 resolver 返回的 owner UUID 查询，设备移除同时写 `revoked_at`，认证令牌只存 hash。
- **会话 ID 诊断**：用户给出 Agent Drive 会话 UUID 时，直接运行 `java backend/scripts/SessionView.java <SESSION_ID>` 快速查看 PostgreSQL 的 `chat_sessions.id` 元数据和最近消息；需要完整消息链时加 `--full`。深入排查时再检查 `chat_tool_replays`、`last_trace`/`pending_confirmation`，最后按 `updated_at` 时间窗口查 `journalctl -u agent-drive-java.service`。只有用户明确给出 Codex 任务 ID 时才使用 Codex thread 工具。最后一条 user 没有 assistant 记录不一定是故障，先确认是否由用户主动停止流。
- **Java task/index vertical**：`java-auth`/`java-chat` 提供 `/api/v1/tasks` list/summary/detail/SSE、受限 rebuild/embed-index/vision-index/cleanup enqueue、cancel/retry；`embed-index` 和 `vision-index` 的 body 都是 owner 内相对路径 `files` list，任务 dedupe key 对文件顺序稳定，API 不接受任意 task type/payload。`MybatisTaskWorkerStore` 使用 `FOR UPDATE SKIP LOCKED`、lease/heartbeat 和明确状态迁移；`MybatisOutboxStore` 负责幂等 pending/publish；`JavaTaskWorker` 消费 schedule 和 owner-scoped `file.changed`，执行 `index.file`/`index.embed`/`index.vision`/`index.rebuild`/`index.cleanup`。IndexingService 使用 Tika/Tesseract 抽取正文，Jina embedding 按 fingerprint 写入 pgvector；VisionDescriptionService 写入 `image-description-v1` 前校验 source revision。`automation.run`、owner-scoped 自动化报告、generic backend_api 和 `INTERNAL write_text` 已接入；`migrate` profile 默认 dry-run，只用于受控 legacy 恢复。
- **Java 文档注释**：`backend/src/main/java` 下所有类、接口、枚举、构造器和方法都必须使用简洁中文标准 Javadoc（`/** ... */`）；类写清职责与边界，方法写清用途、关键行为和副作用，按实际情况补齐 `@param`、`@return`、`@throws`。新增或修改 Java 符号不得省略注释，也不得用“执行方法/返回结果”等空泛占位文案。
- **Java file vertical**：`java-files`/`java-auth`/`java-chat` profile 注册 `/api/v1/files`；公共路径必须是 owner 内相对 POSIX 路径，物理文件按 owner UUID 分目录。文件页通过 `q` 搜索、`/info` 查看 revision 和全文/向量状态、`/content` 读取受限完整文本；`?token=` 只允许 raw/download，列表、状态和 mutation 一律 Cookie/Bearer。上传必须服务端复算 MD5，`noclobber` 必须在 `.storage.lock` 内原子发布，文件 mutation 同步更新 metadata、revision、dedupe 和 `file.changed` outbox。
- **文件列表性能与 Agent 异常边界**：Java name search 最多保留 1000 条候选，使用有界 top-k 集合避免对整个文件树执行无界 `sorted()` 缓存；列表只查询现有 metadata，并把缺失/变化项一次批量 upsert，不能逐项写数据库。`LangChainAgentRuntime.executeTool` 只捕获 `Exception` 作为可恢复工具错误，`Error` 必须交给外层终止流，不能转成可 replay 的普通结果

## 6. 安全红线（勿破坏）

- 除 `/api/v1/health` 与 `auth/status|setup|login|logout|pair-exchange` 外，**全部 /api/v1 走 owner resolver 鉴权**；Cookie/Bearer 可全站，设备 `?token=` 只允许 raw/download GET，禁止扩到列表/状态/写接口
- 密码 PBKDF2 只存哈希；设备令牌/配对码服务端只存 SHA-256；配对码一次性 5 分钟；logout 必须服务端吊销所携 session/device credential，不能只删 Cookie
- Java PostgreSQL `users.password_hash` 是认证真相源；legacy auth snapshot 只作为恢复资料，禁止把删除快照当作重置生产认证；8000 端口必须只绑 127.0.0.1（见 deploy/agent-drive-java.service）
- 密钥不进 git：*.keystore、keystore.properties、keystore 密码（仓库外 D:\ds\agent-drive-keystore\）
- 移除设备 = 吊销令牌；重扫配对 = 吊销旧令牌换新

## 7. 修改检查单

- [ ] 后端改动 → `cd backend && mvn -q test && mvn -q -DskipTests package`；前端改动 → `npm run lint && npm test && npm run build`；原生改动 → APK 构建验证；部署优先使用 `scripts/deploy.ps1`
- [ ] 全量门禁：Maven test/package + frontend lint/vitest/build + Android JVM tests 按改动范围全绿再提交
- [ ] 版本号 +1 / APK 构建（仅测试 App 业务或发版时；日常功能迭代跳过打包）
- [ ] 同步文档 + 本 skill：README / docs/* 相应小节 / AGENTS.md（铁律 §0，同一次提交内完成）
- [ ] 整理并提交变更 → 按发布目标执行 `scripts/deploy.ps1` → API/Worker health；脚本不自动 commit/push，是否推送由发布者单独决定

## 8. 环境事实

- 本机（Windows）：JDK 21（Temurin）、Android SDK `C:\Android\Sdk`（build-tools 35 + platform 35）、Gradle `C:\Android\gradle-8.14.3`
- 服务器：`ssh megumin`，Java 服务 `agent-drive-java.service`（127.0.0.1:8000）+ `agent-drive-java-worker.service`（无监听端口），nginx 13311 单入口；HTTP 代理 127.0.0.1:7890；旧 Python unit/source 已删除，rollback archive 位于 `/opt/agent-drive-java/backups/`
- keystore：服务器 `/root/agent-drive-android/agentdrive.keystore`（密码在本地 `D:\ds\agent-drive-keystore\password.txt`）
