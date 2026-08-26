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

# Content Service
cd services/content-service
mvn -q test
mvn -q -DskipTests package

# File Service
cd services/file-service
mvn -q test
mvn -q -DskipTests package

# Identity Service
cd services/identity-service
mvn -q test
mvn -q -DskipTests package

# Index Service
cd services/index-service
mvn -q test
mvn -q -DskipTests package

# Agent/Chat State Service
cd services/agent-service
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
2. **推荐发布**：执行 `pwsh -File scripts/deploy.ps1 -Target frontend`、`-Target content`、`-Target file`、`-Target identity`、`-Target index`、`-Target agent`、`-Target backend` 或 `-Target all`。脚本从当前工作区构建，递增 Service Worker cache，使用 tar 全量上传并原子替换静态目录；各独立服务都执行 `systemd-analyze verify`、loopback health 和上一版本回滚。`identity/index/agent` 要求预置各自独立数据库和 0600 env；`all` 仍不隐式切换它们，避免未迁移数据时误切流。
3. **前端边界**：不要用 PowerShell 通配符 `out\*` scp，必须保留 `.well-known/assetlinks.json`；APK 只在 App 测试或发版时构建，日常部署保留已有 `out/app/agent-drive.apk`。服务器原地重建可用 `bash deploy/rebuild-out.sh`，但仍需先确认备份和当前分支。
4. **首次安装/unit 变更**：复制 API 和 `agent-drive-java-backup.service/.timer` 到 `/etc/systemd/system/`，执行 `systemd-analyze verify`、`daemon-reload` 和 `enable`；停用旧 Worker unit；`/etc/agent-drive/proxy.env` 从模板创建并 chmod 0600，只允许 HTTP(S) 代理。
5. **数据备份**：`agent-drive-java-backup.timer` 每日执行 `scripts/backup-java.sh`，把主库、独立 Index DB、Identity/Agent DB（若 env 存在）与 owner 文件根归档到 `/opt/agent-drive-java/backups/`，保留最近 7 份并生成 SHA-256 校验文件；仓库不再提供旧 Python/SQLite 定时备份入口。

**交付门禁**：代码或运行行为改动完成后，默认继续执行对应测试、构建、生产部署和 health/readiness 检查；`scripts/deploy.ps1 -Target all` 必须在 API/database/storage readiness 通过后才算发布成功，未收敛应触发回滚。文档-only 改动不重建 artifact，但必须通过文档一致性检查。只有用户明确要求暂不发布，或部署被外部条件阻断时，才停在本地验证并说明原因。

## 4. 关键约定与坑位（改动前必读）

- **Capacitor 插件注册**必须在 `super.onCreate()` 之前（否则 JS 拿不到原生实现，表现是静默退回默认地址）
- **Android 服务器地址边界**：扫码和 `ServerConfig.setServer` bridge 必须共用 HTTPS、无 userInfo/query/fragment、有效 host 校验；配对码兑换禁止跟随重定向，响应体限制 64 KiB；相机权限拒绝必须给出可重试反馈。
- **BridgeActivity 生命周期约束**：`onResume` 是 final，回前台心跳走前端 `visibilitychange` → 插件 `heartbeat()`；`onDestroy` 覆写必须保持 public。MediaStore observer 用 Activity 字段持有、只注册一次，并在销毁时注销且清 debounce callback
- **keystore.properties 必须无 BOM**：PowerShell 写 Properties 用 `[System.IO.File]::WriteAllText` + `UTF8Encoding($false)`；反斜杠双写、冒号转义
- **上传接口约定**：`path` 是查询参数；`md5`（服务端必须复算验证）与 `noclobber`（同名自动序号）是表单字段；multipart 文件 part 名为 `file`。请求体按块写 0600 temp，禁止重新读回内存拼接。嵌套目标发布前必须在同一事务内逐级补齐 owner-scoped 父目录 metadata，避免物理目录存在但后续归属校验失败；事务失败时这些 metadata 同步回滚。
- **免传预检**：Android 先 `GET /files/dedupe?md5=...`；只允许 `verified=true` 且文件 revision 仍匹配的服务端实算条目命中。预检 GET 无副作用；真正上传始终复算 MD5，勿重新信任客户端 hash。发布成功后的去重索引登记是优化项：失败只记 warning、不得把已上传文件伪报为失败（否则客户端重试会经 noclobber 落成重复照片）
- **MediaStore DATE_ADDED 是秒级**：`lastSyncAt` 只推进到「整秒全部成功」的秒；同秒有失败/未取完挂 `pendingSecond+pendingMaxId`（_ID 连续水位）续传。首次失败后水位冻结，之后成功项靠秒传重试；第 201 行仅作截断哨兵、不上传；完整检查点一次 commit。勿改回严格 `> 检查点` + 单张推进
- **上传去重**：生产去重索引是 PostgreSQL 的 owner-scoped `upload_dedup(user_id, content_md5, path, file_revision, verified)`；命中必须同时校验 owner、metadata、物理普通文件和 revision，失效行在读取时自愈删除。文件写入、移动、复制、删除和覆盖都要让旧 revision 的 dedupe/全文/向量失效，不能恢复 JSON sidecar 索引或绕过数据库事务。
- **Spring 事务代理**：带实现方法级 `@Transactional` 的持久化适配器必须保持可代理；当前类代理模式下实现类不能声明为 `final`，新增事务 repository 需用类代理回归测试覆盖应用启动约束。
- **Spring 多构造器 Bean**：生产组件保留测试辅助构造器时，必须在真实依赖构造器上显式标注 `@Autowired`；否则 Spring 会按默认构造器推断并在生产启动时以 `No default constructor found` 失败。相关 Bean 要有构造注入契约回归。
- **WebFlux 阻塞边界**：Controller/principal resolver 内的 JDBC、文件、provider 或其他同步调用统一使用 `ReactiveExecution.blocking(...)`；已经返回 `Mono` 但源会阻塞时使用 `onBlockingScheduler(...)`。禁止在各 Controller 重新复制 `Mono.fromCallable(...).subscribeOn(boundedElastic())` 私有 helper，也禁止把文件 `transferTo` 等原生 reactive 阶段无故整体搬到 bounded-elastic。
- **任务系统状态**：任务、计划、outbox、Worker 和进度队列已从当前运行时移除；历史 PostgreSQL 表与迁移仅作为旧数据兼容记录，禁止新代码注入、写入或暴露这些接口。索引、视觉、向量和维护操作必须走各自业务 API，并在当前响应返回成功或结构化错误。
- **微服务迁移边界**：生产 API 仍是 Java 模块化边缘层，但 Content/File/Identity/Index/Agent Service 已独立部署并通过 Port/DTO + 内部 token 通信；各服务拥有独立 schema/database，禁止共享 owner 本地路径或直接读写其他服务数据库。ChatRunRegistry 的持久状态已外部化到 Agent Service；不得恢复历史任务/outbox 运行链路。
- **文件内容端口**：视觉和 Tika 抽取必须依赖 `FileContentPort.readBytes(owner_id, path, max_bytes)`，只接收受限原始字节；不得把本地绝对路径传给未来远程服务。`FileStorageService` 可作为本地实现的兼容适配器，但 File Controller 的完整文件 mutation 契约不等同于内容读取端口。
- **通用 Agent 工具**：生产注册 `backend_api`、`frontend_api`、只读 `read_skill` 和仅记录当前会话可视化进度的 `plan`；前两者只有稳定的 `discover/call` envelope，不为每个后端路由或 React handler 单独注册模型工具。`backend_api` 先发现 `METHOD /api/v1/path` 或 `INTERNAL name`，再调用精确 operation；discover 使用 `discovery_offset/discovery_limit` 稳定分页（默认 6、最大 20），响应必须包含 `total_matches/returned/offset/limit/has_more/next_offset`，每项附 `parameter_schema` 的参数位置和必填字段，call 会再次执行同一 Schema 校验。dispatcher 返回的业务 `{ok:false}` 必须提升到 envelope 顶层并补齐 `status/code/detail`，不能被嵌套结果吞成成功或排队状态；前端历史记录也必须识别旧版嵌套失败。provider 配置写入、模型目录探测和 API key 处理只走设置页 REST，不进入 Agent catalog；Agent 参数递归拒绝 `api_key/base_url/token/secret/authorization` 等字段。Agent 不得主动创建任务系统；`plan` 只能保存本轮完整步骤状态，不创建后台任务或队列。中文“后端/接口/操作”等领域词在目录层统一规范化。`frontend_api` 的能力来自当前浏览器 registry，discover 只返回匹配动作，call 只允许当前 registry 中的 exact operation，绝不接受 JavaScript 函数名、`eval`、任意 URL、请求头或凭据。绿色内部前端导航可直接执行，文件 mutation 和外部调用仍按风险/批准模式处理。前端动作成功后以 `frontend_action` SSE 事件交给本地 handler，前端再次按 registry、schema 和路径规则校验。HTTP 目录排除 auth/chat/health 和外部 URL；模型不能提供 Cookie、Bearer、Authorization、任意请求头、Python/Java 入口或未登记 operation。索引资源接口只接受 owner-relative 路径、当前认证 owner 和各自 Schema；`POST /api/v1/vision/describe` 只返回固定视觉描述。当前请求的认证 owner 由 Java runtime 注入工具上下文，模型不能获得 Cookie、Bearer、API key 或其他凭据。
- **Skill 系统**：V13 `agent_skills` 是 owner-scoped 自定义 Skill 真相源；名称统一为 1-64 位小写 slug，每 owner 最多 100 个，description ≤500、instructions ≤16000，保存递增 version。每次聊天请求注入当前启用 Skill 的有界摘要目录；已加载正文最多 16 个且受 runtime 字符预算约束。目录要求模型在用户点名或任务匹配时直接用 exact name 调 `read_skill action=read`，discover 只用于搜索/刷新摘要。会话已成功读取的 Skill 名称由服务端从 owner-scoped transcript 记录，后续轮次从当前 registry 重新注入最新正文并标记“已加载”，不得重复调用 `read_skill`；Skill 更新/停用时以当前 registry 为准。自定义创建/更新/启停/删除只走认证 `/api/v1/skills` 或 red `backend_api` operation，已知 key/Bearer 模式落库前不可逆脱敏。`agent-drive-api` 由当前 `OperationCatalog` 动态生成，`skill-authoring` 固化 CRUD/校验规则；两个内置 Skill 都只读且不占 owner 配额。Skill 指令只能编排已登记工具，不能引入任意 URL/header/credential/脚本或扩大 owner 权限。
- **文件统计**：目录计数优先走只读 `GET /api/v1/files/stats`，使用服务端递归返回的 `file_count/folder_count/total_size_bytes/snapshot_at`；禁止把未完成分页或未覆盖的目录列表手工累加成确定总数。若只能遍历列表，必须校验根目录直接子目录集合与已查询集合相等，且每页 `has_more=false`。
- **Agent 上下文注入**：V14 扩展 `chat_messages` 的 `context` role 和 source/kind 字段；生产请求至少装配规范系统提示、`Agent/AGENT.md` 与 `skill-catalog`，涉及整理、自动化、规则、偏好或记忆的请求再装配 `Agent/USER.md`、`Agent/MEMORY.md`。Agent 文档最多各读取 16 KiB、进入模型前做凭据模式脱敏；文件 context 标记为 `untrusted_data`，只作为数据而非指令；runtime 对历史+context 施加总字符预算。同 source 的正文未变化时不得重复落库或重复发送 `context` SSE。前端实时事件和历史 API 都渲染默认折叠的“上下文注入 · 来源”，模型可见内容必须可从 owner-scoped transcript 审计。
- **Agent 文件联动**：`ChatRequest.file_context` 只能是当前 owner 的相对文件/文件夹路径；后端按 owner 重新读取内容，客户端不得直接注入正文，进入模型时标记为 `untrusted_data`。`@` 候选中点击文件直接引用，点击文件夹进入 owner 目录浏览，目录项和当前目录头部必须提供显式“引用文件夹”动作；右侧 `FilePanel` 文件/文件夹项可通过受校验的 `application/x-agent-drive-file` 拖入 ChatPanel composer，复用 `file_context` 但不得重复上传/复制，composer 必须显示拖拽高亮并拒绝绝对路径、反斜杠和 `.`/`..` 组件；浏览返回用路径栈和请求代次隔离迟到响应。文件选择器附件先上传到 owner-scoped `聊天附件` 目录；剪贴板图片不上传、不进入 `file_context`，只作为受大小/数量限制的 `inline_images` Base64 随本轮请求传递，且仅在当前模型支持图片输入时发送。模型输出的 `[[file:path]]` / `[[folder:path]]` 只能被前端解析成固定内部 HTTPS 哨兵，再按 `frontend-actions` allowlist、schema 和路径规则派发 `files.open` / `files.open_folder`，普通外链不得被误转为文件动作。
- **剪贴板图片来源**：浏览器同时暴露 `clipboard.items` 与 `clipboard.files` 时，前端必须优先使用能读取图片的 `items`，只有 `items` 未读到图片才回退到 `files`；不能按不稳定的文件元数据合并两个来源，否则一次粘贴会重复附加同一张图片。单张内联图片上限为 50 MiB；后端 Base64 单图/总量预算与 WebFlux 请求体上限必须覆盖 50 MiB 原图的编码开销。
- **剪贴板图片预览**：`inline_images` 附加缩略图和发送后的当前用户消息都必须可通过键盘/鼠标打开原图预览；用户消息只保留浏览器内存中的预览数据，API 的 Base64 `data` 字段不得进入客户端 history 或服务端 transcript。预览层提供关闭按钮、遮罩点击和 Escape 收起，移除当前图片时同步清理预览状态，不得影响本轮发送载荷。
- **模型图片能力**：`POST /api/v1/config/models` 成功响应必须同时返回 `model_capabilities`；Provider 返回的明确图片/模态字段优先，缺失时仅按已知模型名保守兜底，未知模型默认不支持。聊天内联图片和视觉索引应用侧不得缩放或重编码，分别使用 `ImageContent.DetailLevel.HIGH` 和 OpenAI 兼容 `image_url.detail=high` 请求较高视觉细节；最终 Provider 的内部缩放/分块由其协议决定。Anthropic 仅允许已知 Claude 3/4 视觉系列，前端提示不能替代后端 `ChatRequest` 二次校验。
- **Code Graph RAG 索引**：codebase-memory 的规范项目名是 `D-ds-agent-drive-current`；旧 `D-ds-agent-drive` 的搜索/architecture 缓存已污染，禁止继续使用。MCP 的 `index_repository/update_repository` 必须同时传入 `.gitignore` 与 `.cgrignore` 的排除规则；完整重建当前不能可靠应用嵌套 `.gitignore`/`.cgrignore`，因此 Android build、Capacitor 生成目录和静态导出目录还必须在仓库根 `.gitignore` 重复声明。重建后必须检查 hotspots 不含 `frontend/android/app/build` 或 `app/src/main/assets/public`，任务存储残留为 0。当前 persistence artifact 会错误导出旧同路径命名空间，`artifact.json.project/nodes` 不匹配规范项目时禁止提交 `.codebase-memory/`。Tree-sitter grammar 或工具版本变化后，移走 `.cgr-hash-cache.json` 再做完整重建，不能把旧缓存当作完整索引。
- **通用 API 风险**：GET 和只读探测自动执行；实际写操作按 operation 动态为 yellow/red，ask/auto 模式下 red 必须经过签名确认和确定性重放，full 模式按用户明确授权直接执行但仍受 owner、allowlist 和 Schema 校验约束。Spring WebFlux/Bean Validation 负责路径、查询、JSON/form/multipart Schema 校验；响应统一脱敏，二进制只返回元数据，discovery 通过最多 20 项的单页上限保持在 Agent 工具输出预算内。
- **向量配置 API**：embedding/vision 配置写入与模型探测只走设置页 REST，Agent 只读取脱敏状态并调用已保存配置执行索引。索引任务先调用通用 `GET /index/missing` 按 `kind=document|vector` 和 `document_type=text|vision|all` 查询缺口，按 `has_more/next_offset` 继续分页，再把返回路径交给对应 operation；不凭空把整个目录当成待处理集合。文本文件索引先调用 `PUT /index/file` 抽取正文，再调用 `PUT /index/vectors` 写入向量；后者 `paths: []` 表示当前 owner 全量待处理 chunk。状态查询必须报告 embeddings 状态（configured/provider/model/api_key_masked），勿让模型凭空声称已配置。`POST /vision/describe` 最多接收 16 张图片，服务端按最多 4 张/批发送多图请求，每张图片返回一段独立综合描述；批量协议只保留 `image_id` 分隔标记，不要求模型生成复杂 JSON，不做独立 OCR，也不缓存旧描述。`PUT /index/vision` 对显式传入图片重新描述，不把旧描述当作描述缓存；视觉批次每批完成后立即写入文档和向量，进度通过 `tool_progress` 携带 `completed/total/succeeded/failed/skipped`。
- **readiness**：`GET /api/v1/ready` 返回数据库、owner 文件存储和备份摘要；ready 只由 DB 和存储决定，不依赖 Worker 心跳。
- **设置页密钥生命周期**：SettingsPage 的 LLM/视觉协议或 base_url 改变时必须清空对应 `api_key` 草稿，embedding 的 provider/base_url/model 任一改变也必须清空；保存成功及脱敏配置重载后从 React 状态销毁明文 key。视觉配置保存与模型目录探测的空 key 回退都只比较 provider/base_url，只改视觉模型可沿用已存 key。普通配置响应只给掩码；眼睛在配置边界仍匹配时通过三条 `POST .../api-key/reveal` 按需回显已存 key，这些端点只接受 `SESSION`、强制 `no-store` 且绝不登记到 Agent operation 目录。回显请求必须有代次保护，禁止迟到 key 写入新地址。模型目录探测只使用当前边界内的表单快照，禁止把旧草稿 key 发往新地址。
- **会话/记忆落库脱敏**：会话消息（user/assistant/tool_call）、meta.last_trace、每日笔记落库前过 `redact_text`/`redact_value`（core/logging，含裸 jina_/sk- 令牌模式）；审计层脱敏不等于会话层。例外：`pending_confirmation.arguments` 因签名校验+确定性重放必须保留原文（0600 私有），其 message 提示文案必须脱敏；yellow 工具不得携带密钥参数（last_trace 脱敏后会破坏参数匹配重放）
- **模型正文与工具调用**：Java runtime 只接受 Provider 原生 `toolExecutionRequests` 作为工具调用；正文中的 DSML/XML 片段按普通文本处理，不能触发工具。标题生成只采纳 user/assistant 的普通正文并忽略 reasoning；如果将来增加正文清洗或拦截，必须同步 Java chat 和前端 SSE/渲染契约测试。
- **工具结果展示**：Java runtime 必须使用完整工具输出解析结构化 `parsed`，不能先用摘要截断 JSON；前端对象型 `parsed` 渲染 pretty JSON，参数和确认卡使用 `maskSecretsJson`（键名含 key/token/secret/password/authorization 的值掩码）。工具步骤即使只有 `parsed`、没有文本 `output` 也必须可展开；真正没有返回体时显示明确的空结果状态。输出截断只能用于日志或兜底摘要，不能成为解析输入。
- **任务/聊天路由**：Java runtime 以本轮是否产生工具轨迹标记 `chat`/`task`；工具请求只能来自 Provider 原生 tool call。非流式和流式入口共用 owner session registry；模型历史优先从服务端 transcript 读取，客户端 history 只作兼容回退。SSE 事件同时写入 `chat_run_events`，无本地 relay 时重连按有界轮询回放跨进程事件；若调整续接语义，必须同步 runtime、会话存储和 ChatPanel 回归测试。
- **聊天重连**：`ChatRunRegistry` 按 owner 会话持有 runtime relay，`/chat/stream` 返回 `X-Session-ID`；浏览器断开、刷新或切换页面只结束 SSE 订阅，不得取消 runtime。进程内最多保留 8 个 active run，单次运行 10 分钟超时并自动释放资源。`GET /chat/{sessionId}/active`、`GET /chat/{sessionId}/stream`、`POST /chat/{sessionId}/cancel` 必须先做 owner 会话校验；前端 session ID 持久化并以 `no-store` 历史收敛。不存在跨进程后台任务入口。
- **对话面板常驻挂载**：page.tsx 对话主区用 CSS hidden 切换而非条件渲染——ChatPanel 卸载即丢消息流/工具步骤（remount 不自动重载会话）。工具步骤后追加回复/流结束/停止三处都要清掉发送时挂的空助手占位气泡，勿留空白气泡（回归见 ChatPanel.test.tsx）
- **Agent 运行策略**：生产 `AGENT_DRIVE_MAX_CHAT_STEPS=0` 表示 Agent 不因固定工具步数正常终止；目标完成、用户取消或 `ChatRunRegistry` 的 10 分钟超时/并发熔断负责收敛。正数只作为临时运维熔断，不应成为产品默认行为。运行结束、取消、断开和异常仍必须写入明确终态。
- **前端热点职责边界**：ChatPanel 的模型目录/图片能力归 `useModelCatalog`，模型文件引用渲染归 `AssistantMarkdown`，候选列表归 `FileMentionPicker`；FilePage 的上传引用、Abort、进度、取消和重试归 `useUploadQueue`，展示归 `UploadQueueBar`。上传成功后的智能摄入由 `lib/auto-index.ts` 统一编排，不能让索引 promise 阻塞上传成功态；配置切换必须让模型目录旧请求失效，组件卸载必须 Abort 在途上传；改这些边界需同时跑直接 hook 测试和 `ChatPanel.test.tsx`/`FilePage.test.tsx`。
- **工作区浮层层级**：`WorkspaceHeader` 必须保持高于右侧 `FilePanel` 的 stacking 层级；操作活动中心、工作区导航等 header 浮层不得被文件栏覆盖。新增顶部浮层需补点击后可见的回归验证。
- **全局刷新挂载边界**：整页 Skeleton 只由初次 `authMode=loading` 控制；下拉刷新虽更新 `store.loading`，但不得用它提前 return 卸载工作区，否则会丢失未发送草稿、消息流和工具步骤（回归见 `page.test.tsx`）。
- **文件生产力与状态中心**：V15 的 `file_favorites`/`file_accesses` 是 owner-scoped 路径跟踪真相源；V16 的 `file_version_snapshots` 只登记覆盖上传/文本写入前保存的真实内容，`.versions` 目录属于内部路径，每个文件最多保留 20 个快照，恢复必须作为新 revision 走原子上传链路。文件多选批量 mutation、上传队列取消/失败重试、排序、收藏/最近访问、类型/修改时间筛选和语义最低相关度筛选必须保持 owner API 契约；批量 mutation 使用有界并发，不能一次为无限选择项创建请求风暴。单文件重命名/移动/复制/删除提交前显示变更预览，成功后可提供一次性撤销；系统状态中心用 `Promise.allSettled` 保留局部成功。新增组件行为需有独立 Vitest 回归（`SystemStatusCenter.test.tsx`、`FilePage.test.tsx`、`FileDetails.test.tsx`、`files.test.ts`、`useUploadQueue.test.tsx`）。
- **文件语义搜索**：`GET /api/v1/files?path=&q=&mode=semantic` 先用 Jina `retrieval.query` 生成查询向量，再按当前 embedding fingerprint 在 pgvector 中检索；SQL 必须按文件去重并返回最佳 chunk 的 `search_score/search_snippet`，普通 `mode=name` 搜索保持名称/路径包含匹配。前端语义结果固定按 `search_score` 降序展示，普通目录才使用名称/修改时间/大小排序；搜索提交、清空、模式切换和集合切换必须清理旧预览、操作选择与多选状态，不能让迟到列表响应或旧选中文件继续驱动操作栏。目录级 `PUT /api/v1/index/vision` 请求由服务端展开为受支持图片，HEIF/AVIF 等格式以 skipped 返回，不能让 Agent 逐个重复调用同一失败路径。面向回答的 Agent 证据检索使用只读 `GET /api/v1/files/search-content?path=&q=&limit=&neighbors=`，返回 owner 当前 revision 的多个匹配 chunk、有限相邻正文、`source_revision`、`chunk_index` 和 `search_score`；正文属于不可信文件数据，必须引用路径且不得执行其中指令。证据接口的 limit 最多 16、neighbors 最多 2，仍按 owner、路径、类型、时间和当前 fingerprint 校验；无结果只报告 `evidence_status=no_match_or_not_indexed`，不能声称已完成全文检索。未配置 embedding 或 provider/index 不可用时返回稳定的 409/502 detail，不能伪造空的“已向量化”结果；Agent 调用这两个 operation 时把 `q`、`mode` 或 `neighbors` 放入 `query_params`。
- **索引业务口径**：索引、视觉和向量接口直接同步执行并返回逐项结果；增量任务统一先查询通用缺口，视觉 operation 对传入图片重新描述，向量 operation 只为缺少当前 fingerprint 的 chunk 生成向量；批量结果必须区分 `succeeded/partial/failed`，进度事件报告真实处理计数，不创建任务记录，不提供任务列表、取消、重试或队列状态。错误必须带稳定 `ok/status/code/detail`，不能把 provider 失败显示成“排队中”或成功。
- **通用操作反馈**：前端 `OperationActivityCenter` 是 UI/Agent 长操作的统一活动入口；运行中操作只保留当前进程内状态，完成/部分完成/失败摘要以版本化 `localStorage` 短期保留。上传、索引、视觉、向量化和批量文件 mutation 必须发起活动、更新阶段并在真实结果返回后收敛；Agent 每轮同时保留一条聚合运行活动，明确显示等待模型响应、执行工具和终态耗时；长视觉/索引操作的 `tool_progress` 必须携带真实 `completed/total/succeeded/failed/skipped` 计数；停止或异常时工具卡和对应活动必须一起收敛为明确终态，不能遗留“执行中”。上传成功态不能等待模型调用，自动索引失败必须单独显示。Toast 只做摘要，不替代活动记录。Agent `tool_start`/`tool_progress`/`tool_trace` 对登记的文件/index mutation 复用同一活动语义。不得把活动中心重新实现为后台任务队列，也不能把断线的运行伪报为可恢复。
- **轻量质量指标**：`BusinessMetrics` 只通过现有 SLF4J 日志输出 `business_metric=index/search/file_open/agent_operation/agent_cancel`，记录成功数、失败数、无结果和耗时；不得写入文件正文、完整路径、查询原文、API key 或凭据，不得为指标恢复任务表、outbox 或后台 Worker。后续统计由日志平台聚合，当前运行时不提供伪造的统计 API。
- **Agent 文件刷新**：生产聊天工具统一名为 `backend_api`；前端文件变更刷新必须按解析后的 `METHOD /api/v1/files|index` operation 判断，不能依赖已删除的旧工具名。后台运行的 detached session 也要广播 `filesChanged`，完成后文件页必须能看到 Agent 的 mutation。
- **向量有效性**：全文元数据记录 `source_revision + extractor_version`；向量 chunk 记录 `source_revision + embedding fingerprint + chunk_version`，统一保存在 PostgreSQL/pgvector。文档用 `retrieval.passage`、查询用 `retrieval.query`；revision、chunk 版本或 embedding fingerprint 不匹配时视为失效，不能参与语义检索。
- **同步检查点**：整秒完成后才推进 `lastSyncAt`；失败不阻塞整批但不得让更晚秒越过最早失败秒，Worker 按 lastError 退避重试；MediaStore query/Cursor/字段读取和 checkpoint commit 异常也必须保留当前/已有 pending，不能把部分成功当成已提交。每行先读 DATE_ADDED 并以此真实秒 `begin`，再读 _ID 等其余字段——字段异常必须落在该真实秒的 pending 上，不得把已完成的上一组误标失败。`lastSyncAt/pending*` 必须同一次加密 prefs commit。周期/快速/手动任务可能使用不同 unique work 名，`SyncEngine.sync()` 必须保持进程内串行
- **noclobber 原子独占**：Java 上传请求体先流式写入受控临时文件并 fsync，`publishUpload` 在 storage lock 内用独占移动发布；覆盖上传先把旧目标复制到 owner 私有 `.versions` 快照并移入隐藏 backup。上传、移动、复制、移入回收站和恢复的 storage lock 必须持有到 Spring 事务 afterCompletion：提交后清理 backup，回滚/提交失败恢复发布前磁盘状态并清理未提交快照；回收站永久/过期清理同样先提交数据库，再在 afterCompletion 删除 `.trash`/`.versions` artifact；无事务调用立即收尾。提交后的 artifact 清理失败只记 warning，不得把已发布文件伪报失败。覆盖移动和复制拒绝文件↔目录混型并返回 409；勿退回“先 exists 再普通写”的 TOCTOU
- **设备注册表写入**：生产设备 metadata、sync_state 与 revoked_at 写入 Java PostgreSQL 事务；本地 owner 文件发布仍使用 0600 temp + fsync + atomic replace，失败清理 staging。
- **原子文本、目录复制与回收站**：write/append 都是 temp+fsync+replace；append 的读-改-写由 RLock/flock 包围。Java 目录复制先在 owner 根下隐藏 `.copy.*.staging` 完整构建并 fsync，再用同根独占移动发布；覆盖前 durable 写 `.copy.*.txn.json`，旧目标暂存为 `.copy-old.*`，启动恢复未提交旧目录或清理已发布 backup。marker/backup 清理失败不得伪报已发布复制失败；无 marker 的 `.copy-old.*` 无法证明可删，必须保守保留。文件↔目录类型混型返回 409，目标不得是 owner 根目录。回收站每次删除有唯一 `trash_id`，恢复传 trash_id（兼容旧 path），孤儿 metadata 不展示并可清理
- **resolve 拒绝符号链接与内部路径**：组件级检查（业务从不产生 symlink），下载/预览/上传共用；`.index/.trash/.versions/.storage.lock` 与 `.upload/.copy` staging 的公共访问必须拒绝，不只是列表隐藏。内部流程使用显式 `allow_internal`，列表/摄入/任务变更必须跳过
- **设备令牌加密存储**：现行数据只写独立 `agent_drive_secure` EncryptedSharedPreferences（AES256-GCM/SIV，MasterKey 在 Keystore）+ `allowBackup=false`。升级识别旧 `agent_drive` 明文和 1.0.27 同文件密文：同键以 1.0.27 持续写入的密文为现行值，明文只作更早来源/清理残留；独立新密文若与 legacy 现行值冲突则保留双方并失败关闭。新密文 commit 成功或逐键确认相等后才清理旧业务数据，AndroidX keyset 永不 clear，清理失败下次幂等重试；初始化/迁移/commit 失败必须弹窗或 reject/Log+retry，绝不能吞异常或降级明文
- **显式编码**：Java 文件 API 使用 UTF-8 与 POSIX 路径语义；文本预览必须用 REPORT 严格尝试 UTF-8→GBK→ISO-8859-1，不能让替换字符使 UTF-8 伪成功；字节上限截断时只允许丢弃末尾未完成码点。用户可见文本与内部临时文件保持明确编码，跨平台发布固定换行与原子写入。
- **脚本按端归属**：后端一次性运维/诊断脚本放 `backend/scripts/`，统一使用 Java 21；前端构建/静态产物脚本放 `frontend/scripts/`，统一使用 JavaScript/TypeScript；跨前后端的部署、备份和 QA 编排才放仓库顶层 `scripts/`。
- **CI（GitHub Actions）**：backend = Java 21 + Maven 3.9 Enforcer、带 PostgreSQL/java-files profile 的 Maven test/package；test 生成 `backend/target/site/jacoco/index.html` 并上传 `backend-jacoco` artifact（14 天），当前不设历史代码一次性覆盖率阈值。frontend = `npm ci --ignore-scripts` + eslint + vitest + build；android 必须先构建 web assets、执行 Capacitor sync，再跑 gradle testDebugUnitTest（JVM 单测）。gradlew 可执行位已入库，本地可直接 ./gradlew；ESLint 配置忽略 android/ 构建产物，不得恢复全目录裸扫
- **Vitest ESM 配置**：使用 `vitest.config.mts` + `import.meta.url` 解析别名；勿改回含 ESM 语法的 `.ts`/CommonJS 加载方式（未来 Vite native config loader 不支持）
- **上传大小上限**：`max_upload_mb=300`（后端 413；公网闸门仍是 nginx 200m）——直连 8000 的滥用兜底
- **健康检查**：`/api/v1/health` 公开豁免（探活用，不泄露业务信息）
- **日志统一出口**：Java 使用统一 SLF4J/Logback 输出，敏感字段按 key/value 脱敏；`ApiRequestLoggingWebFilter` 为每个 `/api` 请求回写 `X-Request-ID`，完成日志只记 request_id/method/匹配路由模板/status/duration/client_ip/terminal，绝不记录 query value、路径参数、header 或 body。未分类 500 只给客户端通用 detail，真实原因用 `ChatLogSupport.safeThrowable` 脱敏后按 request_id 记 error。nginx 对 raw/download 关闭 access log，避免 `?token=` 落盘；`X-Forwarded-For` 必须由 nginx 覆写为单个 `$remote_addr`，Java 只在 TCP 对端为 loopback 时信任合法 IP 字面量。日志行为变更需补 Java API/Android 客户端测试。
- **聊天流日志链**：`WebRequestMetadata` 统一复用安全的 `X-Request-ID`/`X-Correlation-ID`/`traceparent`，缺失时生成 UUID；`ChatController` 使用同一 request_id 并只在服务端 `ChatRequest` 内传到 runtime 和 `backend_api`。聊天日志必须覆盖 stream start、provider/model resolution、model step、tool start/end、done/error/cancel/disconnect；参数只写键名/数量摘要，异常 message/cause 与 SSE error 先经 `SensitiveDataRedactor`，终态状态机不得把 done 误报成 cancel/error。生产按 `journalctl -u agent-drive-java.service | rg 'request_id=...'` 检索。
- **日志持久化**：Java API 统一使用 SLF4J/Logback 输出到 systemd journal；需要审计或聊天链路时按 request_id、时间和级别查询 API unit，不恢复旧 JSONL 审计文件轮转逻辑。
- **日志查询**：服务器使用 `journalctl -u agent-drive-java.service`，按时间与级别过滤，禁止输出敏感环境变量。
- **限速内存态**：当前 API 单进程限速只覆盖认证入口；新增聊天、索引或外部 provider 入口时必须补 owner/global 并发与速率预算，避免单用户公网入口耗尽 Provider 或 bounded-elastic 资源。
- **API Key 展示边界**：普通配置、状态、日志、会话和 Agent 工具结果只显前缀掩码；只有设置页专用的会话认证、禁止缓存回显端点可返回完整已存 key。provider key 只以 AES-GCM ciphertext 写入 PostgreSQL，master key 只在 0600 环境文件。
- **认证配置失败关闭**：Java PostgreSQL users/sessions/devices/pairing_codes 读取或迁移异常必须拒绝启动并保留数据库备份；密码与 credential 只存 hash，变更走事务，勿静默当作未初始化。
- **同步断网中止**：SyncEngine 连接失败/401/403/5xx 抛 AbortBatchException 整批中止（勿改回 200 张串行超时）；永久 4xx（400/413/415/416/422）按“跳过”推进连续水位、不设 lastError 不触发重试，其余 4xx 视为可能瞬时并冻结水位下轮重试；本地 `FileNotFoundException/SecurityException` 同样永久跳过，其他本地 I/O 冻结水位，显式 `InterruptedException` 或线程中断必须重设中断位并中止整批。中止时当前秒组同样挂 pending 续传。响应实体必须先 drain 再 disconnect，保持连接可复用。**HTTP 分类集中在 `classifySyncStatus(code, upload)`，本地分类集中在 `classifyLocalMediaFailure(error, interrupted)`，同秒续传 SQL 在 `buildResumeSelection()`——改分类/SQL 必同步补 SyncEngineTest 分支断言（注意 dedupe 仅 200 命中、upload 404 归可重试两处差异）
- **同步配置与观察者**：PhotoSync.configure 的 enabled/wifiOnly/interval/folder 必须一次加密 prefs commit；folder 校验必须与 Java 服务端拒绝 `.index/.trash/.versions/.storage.lock` 和 upload/copy staging 的公共路径集合保持一致。多个调用放入专用单线程执行器，从写入到 WorkManager Operation 入库结果全程串行，绝不能在桥接/UI 线程 `Future.get()`。调度失败保留已提交的期望状态、明确 reject，由下次启动 `ensureScheduled` 幂等收敛；禁止伪回滚未知 WorkManager 副作用。挂起的权限回调在 Activity 销毁或新请求到来时必须 reject/替换，不能留旧 PluginCall 解析。ContentObserver 保持 1 秒防抖，字段持有且 Activity 销毁时注销/清 callback，避免重建泄漏和重复快速同步
- **通知权限非致命**：相册权限判定只看 READ_MEDIA_IMAGES/READ_EXTERNAL_STORAGE（通知被拒不算失败）；PhotoSync 诊断提供 `openNotificationSettings` 直达系统设置
- **错误语义化**：Java file controller 将 404/409/403 与存储 I/O 故障映射为稳定 API detail/status；瞬时存储、数据库或 provider 故障不得伪装成客户端 4xx，Android 只跳过明确永久 4xx。
- **/api 404 保持 JSON**：FrontendSpaFallbackWebFilter 对 `api/` 前缀保留 JSON 404，前端路由 fallback 只处理非 API deep link。
- **SPA 静态文件边界**：FrontendResourceConfiguration 与 fallback 拒绝 `..` 越界路径，并要求 resolve 后仍在 frontend/out；越界返回 JSON 404。
- **extract 直接业务执行**：上传只持久化；用户或 Agent 显式调用索引 API 后，普通文档用 Tika，图片由视觉模型生成描述；单个文件抽取失败直接返回逐项错误，不得伪报成功；图片不使用 OCR。
- **生产代理边界**：API 读取 `/etc/agent-drive/proxy.env` 的 HTTP(S) proxy；systemd 用 `UnsetEnvironment=ALL_PROXY all_proxy` 阻止 SOCKS 继承。Jina 在服务器直连会超时，勿移除
- **systemd unit 语法**：`StartLimitIntervalSec/StartLimitBurst` 放 `[Unit]`；systemd 不支持指令值后的行尾注释（会把注释当值并忽略安全项）。unit 变更部署前必须跑 `systemd-analyze verify`，Agent Drive unit 不得出现 warning
- **前端 GET 去重与身份隔离**：cache key 必须含 API base + credential generation + cache generation + path；凭据代次与缓存代次分离，旧 in-flight 不得写入新身份/新缓存或派发迟到 401。每个非 GET 在请求开始和结束（成功、HTTP 错误、网络异常、Abort）都独立失效，交错写不能跳过结束清理
- **前端启动错误语义**：`/auth/status` 或 `/config/status` 只有 401/403 才进入 web 登录/原生重扫码；5xx、网络、JSON 解析、布尔字段契约及其他服务错误进入可重试的 `server-error`，不得清除 Cookie/设备令牌或伪装成 AI 未配置。重试必须重新执行完整启动检查。
- **原生登出语义**：先清 EncryptedSharedPreferences 再清进程令牌；安全存储清理失败必须停留并报错。离线/5xx 本地退出后只提示“服务端吊销状态未知”，401/403 视为凭据已不可用，勿误报旧令牌仍有效
- **chat SSE 解析**：前端 `chatStream` 必须处理 LF/CRLF/CR、换行跨 chunk、UTF-8 码点跨 chunk、多行 data 和无终止空行的尾事件；401 与普通 API/上传共用 `EV.unauthorized`，错误保留 Java 后端 detail。**线上契约：每个 SSE data 必须是 JSON 对象**（前端解析器拒绝裸字符串）——text/reasoning 为 `{"text": str}`，context 为 `{"source": str, "kind": str, "content": str}`，Java `ChatSseEvents`/`ChatSseEncoder` 负责保持对象契约；流内错误还必须携带服务端已确认的 `session_id`，前端据此保留失败消息并继续复用同一会话，Java runtime 同时把脱敏错误消息和最后工具轨迹写入 transcript。回归见 `ChatControllerContractTest`、`ChatControllerAuthContractTest`、`ChatContractTest` 和前端 `chat.test.ts`/`ChatPanel.test.tsx`。
- **对话思考等级/过程**：输入区的思考等级由 `auto/low/medium/high` 组成，默认 `auto` 以保持普通模型兼容；显式等级通过 `ChatRequest.thinking_level` 传到 Provider。OpenAI-compatible 请求固定 `parallel_tool_calls=false`，避免 Sub2API 多工具流在模型已产生首字后延迟关闭；Provider 输出的 reasoning 必须走独立 SSE 事件并存入 assistant 消息的 `reasoning` 字段，ChatPanel 用原生 `details` 默认收叠、允许用户展开；同步工具执行必须通过 `tool_start` 的业务阶段、`tool_progress` 的耗时心跳和最终 `tool_trace.started_at/elapsed_ms` 对用户可见，工具卡完成/失败后仍保留独立耗时；模型首个事件超过 10 秒时，空助手占位必须显示“等待模型响应 · 已耗时”，让外部 Provider 的首轮延迟可区分于前端卡死；每轮模型请求前工具结果按有界字符预算压缩，并通过 `context_usage` SSE 发送实时用量/压缩状态；`done.latency_ms/total_elapsed_ms` 用于 composer 的本轮总任务计时，不能只显示“没有可展示的返回内容”或笼统 STREAMING/READY；未知百分比不得伪造。Provider 未返回 reasoning 时不伪造思考内容，reasoning 不进入下一轮 history。**OpenAI/OpenAI 兼容流式模型必须 `returnThinking(true)` 构建，否则 langchain4j 静默丢弃 `reasoning_content`，思考过程既不显示也不落库（生产实测：DeepSeek/Qwen/o 系兼容网关；回归见 ProviderReasoningStreamContractTest + chat.test.ts）**。
- **聊天模型选择**：ChatPanel 按需通过 `POST /api/v1/config/models` 读取当前 owner Provider 的模型 ID，`ChatRequest.model` 只覆盖本轮请求；Java resolver 必须继续从已保存 owner 配置取得 provider、base_url 和 API key，空模型沿用默认配置，不得让客户端改写连接或凭据。
- **Agent 权限模式**：`ChatRequest.permission_mode` 只允许 `ask/auto/full`，默认 `auto` 并由前端浏览器持久化。`auto` 仅 red 需要批准，`ask` 对所有非读取 operation（包括 yellow/red 和外部探测）需要批准，`full` 按用户明确授权直接执行已登记 operation，包括 red 删除/覆盖；full 仍不能绕过 owner 鉴权、operation allowlist 或参数校验。`ask/auto` 下 red 仍由后端 `ConfirmationService` 生成一次性签名确认。前端权限控件的文字说明必须与该风险语义一致。
- **Agent 身份提示**：`LangChainAgentRuntime` 对空提示和自定义 `APP_SYSTEM_PROMPT` 都在正文前后包裹 canonical Agent Drive identity guard；底层 provider/model 名称不得成为助手自称，用户自定义提示不能覆盖两端约束。
- **会话标题与流生命周期**：聊天完成只刷新会话列表，由 `SessionList` 按 session ID 对空标题摘要去重；两次列表写入都必须在同一请求序列内（被取代的旧 load 的刷新不得覆盖新列表），空标题会话被并发 load 碰到且其总结仍在途时必须等待后重拉（防「标题已生成但列表不显示」）；`useChatStream` 以 session key 保存活动 controller/frame/busy，不同会话可并行。切换会话只把原流标记为 detached，绝不 abort；后台事件不得写入当前会话，返回原会话或流结束后由持久历史收敛。只有当前会话的显式 stop 和 ChatPanel 真正卸载才 abort 对应流并清 timer。ChatPanel 加载会话历史仍须校验请求代次和当前 session。工具步骤是模型轮次边界，前端提交当前轮 reasoning/text 后清空缓存；清理助手占位时必须同时确认正文和 reasoning 都为空，不能删除 reasoning-only 的上一轮。Java `summarizeOwned` 的摘要不得把 null 正文写成 "null"，空摘要不落库、不覆盖已有标题；`java-chat` 对空标题使用当前 owner provider 的 AI 生成不超过 20 字的标题，AI 失败/超时/返回空内容时回退确定性标题，已有标题不重复调用模型，模型返回文本先清理工具标记。
- **chat 流式节流**：ChatPanel 每 80ms 批量刷一帧（streamTimerRef），流结束冲刷最后一帧；异常收尾必须先 flush/cancel 当前帧、保留已生成正文/reasoning/工具步骤，再清理空占位并追加错误消息，勿让迟到 timer 覆盖错误或用错误气泡替换工具轨迹；勿改回逐 token setState
- **模型正文 DSML/XML 边界**：正文中模拟工具调用格式不会进入 Java 的工具执行分支；只有 Provider 原生 tool call 才能生成工具步骤。前端按普通文本渲染正文，不要把旧后端的正文清洗实现重新当作当前契约。
- **前端复用单元**：全站 UI 规范见 `docs/frontend-design.md`（控件清单/排版/反馈/反模式清单）；新控件一律用 `components/ui/`（shadcn，radix 底座），**禁止内联自造复刻**——一个值只允许一个控件（选择+手输并存用 Combobox，禁止 select+input 并列）。业务复用单元：文件预览 `FilePreview`、时间格式化 `fmtTime`、原生重扫 `useRescan`、协议/预设枚举 `lib/llm-options.ts`（新增协议需同步 backend `ProviderType`）、聊天流式发送 `useChatStream`（请求编排在 hook，`busy` 也只由 hook 持有，事件契约/消息状态/80ms 帧/事件分发分别复用 `chat-stream-events`、`chat-stream-state`、`chat-stream-frame`、`chat-stream-dispatch`，ChatPanel 勿内联重建）；ChatPanel、SettingsPage 和 Onboarding 的模型目录探测必须以请求代次隔离，协议、接口地址或 API key 变化时使旧请求失效，Onboarding 还必须销毁旧边界的 key 草稿；会话列表每条记录必须显示完整会话 ID，长 ID 允许换行；对话工作区的会话列表和文件栏布局统一由 `app/page.tsx` 管理：完整侧栏只在 `xl` 宽桌面显示，较窄视口使用会话抽屉，收缩/拖拽状态按 `workspace-layout-v1` 版本化持久化，分隔轨道逻辑统一复用 `components/workspace/PanelResizeHandle.tsx`
- **聊天 Markdown 排版**：`.markdown-body` 必须覆盖父气泡的 `whitespace-pre-wrap` 为 `white-space: normal`，让 ReactMarkdown 生成的块级标签控制换行；段落、列表和列表项使用紧凑 margin，首尾 margin 清零，长词允许换行，代码块继续保留自身预格式化和横向滚动语义。
- **聊天输入区**：ChatPanel 输入区保持紧凑，外层不得绘制横跨页面的 `border-t` 或边框，只保留居中的 composer 容器边界；composer 在现有刘海屏安全区之外保留约 8px 底部呼吸空间，不能贴住视口底边；外层 composer 只跟随聊天文本框聚焦，Select 触发器使用自身的轻量键盘焦点反馈，避免下拉菜单关闭后外层输入框残留强焦点态；聊天模型 Combobox 位于视口底部，候选层固定 `side="top"` 并在可用高度内滚动，不能向下压缩成窄条；上下文窗口控件必须紧跟推理等级并使用原生 `<details>` 就地展开，Java runtime 优先传真实 `TokenUsage`、缺失时保守估算，禁止伪报 0；点击其他失焦区域或 Escape 必须自动收起，禁止恢复独占整行的底部状态条；底部快捷操作按钮保持移除；阅读位置提示固定在 composer 上沿中线，使用圆形下箭头和 tooltip，不得恢复右下角孤立的文字胶囊；聚焦反馈只能使用不占布局空间的主题边框/外环；调整间距或尺寸时必须保持自动增高、Enter 发送、Shift+Enter 换行、停止流和思考等级选择行为不变
- **聊天滚动**：切换/加载会话完成后必须定位到历史底部；Agent 运行期间正文、reasoning、工具步骤和计划变化必须自动跟随底部；非运行状态用户主动上滑后保持阅读位置，只显示 composer 上沿的“回到最新消息”入口，不能在用户阅读旧消息时强行抢回。
- **文件页请求生命周期**：`FilePage` 的列表、选中文件详情、完整文本、索引刷新和回收站列表都必须使用请求代次/当前路径校验；迟到响应不得覆盖新目录、新选中项、已关闭回收站或卸载后的状态。只有仍属当前代次的详情/回收站失败才发 toast，过期失败必须静默。目录/文件切换要使旧内容与索引请求失效，文件变更事件负责统一刷新，勿在同一 mutation 后再手动重复 `load`
- **其他前端请求生命周期**：`FilePanel` 的目录列表/文件详情、`SettingsPage` 的配置刷新都必须使用请求代次校验；快速点击、筛选切换、全局刷新或卸载后的迟到响应不得写入当前状态。对应竞态必须有 deferred response 回归测试。
- **Skill 管理请求生命周期**：`SkillsManager` 的列表和详情使用独立请求代次；搜索、分页、全局刷新、切换 Skill、新建和卸载必须让旧响应失效。内置 Skill 只读且始终启用；自定义 Skill 的保存、启停和删除完成后统一重拉当前查询，迟到详情不得覆盖当前编辑器。
- **移动端预览面板**：FilePage 预览/回收站移动端为全屏覆盖层（`fixed inset-0 z-40 lg:static`），勿改回 `hidden lg:flex`
- **移动端文件工具栏**：`<640px` 保持 3×2、44px 高触控网格；`<360px` 顶栏只视觉隐藏 Agent Drive 文字（保留无障碍文本），320/407px 必须无横向滚动
- **文件选择操作栏**：FilePage 的“已选”区域必须始终保留固定高度；桌面端单行，移动端横向滚动不换行，详情异步加载不能导致文件列表再次下移
- **Next viewport**：Next.js 16 在 `layout.tsx` 用独立 `export const viewport: Viewport`；勿放回 `metadata.viewport`（构建会警告并可能被忽略）
- **版本号**：每次发版 `frontend/android/app/build.gradle` 的 versionCode/versionName 同步 +1；当前 Android 发版为 versionCode 31 / versionName 1.0.31
- **PowerShell/SSH 转义坑**：ssh 内嵌 curl 的 JSON 用 stdin 管道（`... | ssh megumin "curl --data-binary @-"`），不要 `\"` 转义；需要执行包含 heredoc、`$()`、SQL 或带空格 header 的远端 Bash 时，先在本地 base64 编码脚本，再用 `ssh megumin "echo <base64> | base64 -d | bash"`，避免 PowerShell/OpenSSH 二次拆词。
- **工程质量分析 agent**：任何质量/可维护性分析按 `docs/quality-analysis.md` 协议执行——架构与技术栈属基线（记录在快照，不频繁重审），每次只做增量检查（风格一致性/耦合重复/门禁/测试缺口/复杂度热点/死代码/文档同步）；分析阶段只读，完成后先出报告等用户确认，只有用户明示“直接修”才动手。禁止“边分析边改”
- **模型列表端点**：POST /api/v1/config/models 只读探测、不落盘；该端点和视觉模型 probe 只供设置页 REST 调用，不进入 Agent catalog。api_key 留空仅当表单 type+base_url 与已存配置一致才回退已存 key（不得把已存 key 发给新填的陌生地址）；20s 超时。两个端点都只返回模型 ID，不落盘、不回显 key。Provider 统一实现 list_models()（OpenAI 系 GET /models、Anthropic GET /v1/models；Ollama 等非标准 /models 形状会返回错误提示，用户手动填写）
- **配置保存 key 回退**：设置页 POST /config 与 /config/models 语义一致——api_key 留空仅当 type+base_url 与已存配置一致才沿用已存 key，改协议/地址必须重填（防旧 key 打向新地址）。这些配置 operation 不得重新加入 Agent catalog。勿退回"无条件回退已存 key"
- **温度不入配置**：模型请求从不携带 temperature（按各服务商默认），LLMConfig 不存温度、设置页无温度输入——勿恢复温度字段或 UI
- **shadcn 主题映射**：globals.css `:root` 品牌 token 是唯一主题源，shadcn 语义变量全部映射到它；`--card` 是品牌填充色 #f0f1f5（shadcn Card 组件显式用 bg-panel）；`@theme` 里 `--color-accent→accent-brand`、`--color-muted→muted-text`——勿改回直连语义变量，勿在组件写死颜色。`SelectItem/ComboboxItem` 的选中/键盘高亮必须用 `accent-soft + text-text`，禁止 `bg-accent + text-accent-foreground`（两者当前都是深色品牌色，会造成文字不可读）
- **事件总线**：`agent-drive:refresh`（下拉刷新）、`agent-drive:files-changed`、`agent-drive:toast`、`agent-drive:unauthorized`（401 全局拦截）

## 5. Java 后端现行约定

- **API 与服务运行模式**：`backend/` 是 Java 21 API 主目录；生产由 `agent-drive-java.service` 运行 `--app.mode=api`，并由独立 Content/File/Identity/Index/Agent units 承载对应服务边界。Index 生产读模式为 `remote` 并保留 local fallback；File Service 接管原始内容读取和物理 mutation 镜像；Agent Service 持有 session/transcript/replay/confirmation/run/event 状态，`runtime.append_user` 必须保存用户正文，刷新或重连历史不能退化为空气泡。任务/Worker unit 已移除，禁止运行时调用 Python API；legacy data/system 只在显式 `migrate` profile 下作为受控迁移/人工恢复输入。
- **数据库真相源**：结构化运行状态统一进入 PostgreSQL/pgvector；`tasks.sqlite3`、认证/设备/上传索引 JSON 不再是生产真相源。实际二进制文件以及用户可见 Agent 文档暂留 owner-scoped 本地文件系统。
- **Megumin 迁移数据库**：使用独立 `agent-drive-java-postgres`（`pgvector/pgvector:pg16`）容器和 `/opt/agent-drive-java/postgres` 卷，宿主仅绑定 `127.0.0.1:15433`；数据库凭据与随机 AES-GCM keys 在 0600 的 `/etc/agent-drive-java/java.env`，不得复用其他业务 PostgreSQL 或进 git。
- **客户端契约**：当前实现必须保持 `/api/v1`、SSE JSON 事件、Cookie/Bearer/设备令牌、Android 同步协议和前端工具步骤的一致性；任何契约变更先补 Java/前端/Android 测试，再更新专题文档。
- **Java chat/auth profile**：`ChatController` 和 Agent runtime 只在 `java-chat` profile 启用；`java-auth`/`java-chat` 负责 Cookie/Bearer 认证、owner 会话、设备令牌和一次性配对码。客户端 `device_id` 保持 snake_case；setup/login 每个客户端每分钟最多 5 次，pair-exchange 每分钟最多 10 次；配对码有效期 5 分钟且最多保留 3 个未使用码。Chat stream 使用 `event: <name>` + `data: <JSON object>`，保持 `text/event-stream`、`no-cache` 和 `X-Accel-Buffering=no`，流内异常发 `error` 事件并保持 HTTP 200。配置写入/模型 probe 只经设置页 REST；Agent catalog 中的文件、设备、会话、索引和 `INTERNAL write_text` 都必须经过 owner resolver，参数 Schema 和凭据字段在 dispatcher 再校验。API key 只以 AES-GCM 密文落库，probe 不跟随重定向且不回显 key。完整 key 回显额外要求 `SESSION` 凭据，设备 Bearer 即使属于同一 owner 也必须返回 403。`AGENT_DRIVE_CONFIRMATION_KEY` 与 `AGENT_DRIVE_LLM_CONFIG_KEY` 必须是 base64 32-byte 密钥。
- **模块化单体**：当前只运行 API 单进程；跨模块一致性通过 application service 和 PostgreSQL 事务处理。
- **详细方案**：技术栈、模块边界、PostgreSQL schema、Agent tool calling、切换记录和验收门禁以 `docs/java-migration-architecture.md` 为准。
- **LangChain4j 工具 Schema**：模型可见参数名必须通过 `@P(name = ...)` 显式声明；Jackson `@JsonProperty` 不负责 LangChain4j tool schema 命名。每个工具必须用 `ToolSpecifications` 断言工具数量和参数字段，避免 camelCase 契约悄然泄漏。
- **Java provider thinking**：OpenAI `low/medium/high` 走原生 `reasoningEffort`，且 `StreamingModelFactory.openAi` 构建的 `OpenAiStreamingChatModel` 必须 `.returnThinking(true)`（构建期属性，默认 false——langchain4j 只有开启后才把流式 `reasoning_content` 转成 `onPartialThinking`/`AiMessage.thinking()`，否则思考过程被静默丢弃）；Anthropic 走 `thinkingBudgetTokens=1024/4096/8192` + `sendThinking/returnThinking`（走请求参数，由 `AnthropicChatRequestFactory` 对显式等级开启）；`auto` 不显式发送，任何请求禁止恢复 temperature。
- **Java tool replay**：只有显式 `probe`/`idempotent` 的 `backend_api` call 按 `session_id + tool + arguments` 重放既有成功结果并标记 `replayed=true`；普通 GET、失败结果和 mutation 后的旧快照不得重放。discover 没有 operation 风险定义，每次读取当前目录。owner-scoped session service 与 `MybatisChatRuntimeStateStore` 通过 Mapper XML 将脱敏参数/结果持久化到内部 `chat_tool_replays`，将 user/assistant/context/reasoning/tool trace 写入脱敏的 `chat_messages`，把最后 trace 写入 `chat_sessions.last_trace`，并把 SSE 事件写入 `chat_run_events` 供进程重启后回放。context 使用同 source 最新正文比较的单 SQL 插入，未变化返回 false。`InMemoryToolReplayStore` 只允许测试使用；默认构造器只允许测试使用，profile runtime 必须注入持久 state store。模型调用 backend dispatcher 时，owner UUID 只能由 runtime 内部透传到 dispatcher，不能加入 LangChain4j tool schema 或由模型提供。
- **Java device/session vertical**：`java-auth`/`java-chat` 提供 `/api/v1/devices` 与 `/api/v1/sessions` 的 list/get/delete/register 路由；设备元数据和会话消息只能按 resolver 返回的 owner UUID 查询，设备移除同时写 `revoked_at`，认证令牌只存 hash。
- **会话 ID 诊断**：用户给出 Agent Drive 会话 UUID 时，直接运行 `java backend/scripts/SessionView.java <SESSION_ID>` 快速查看 PostgreSQL 的 `chat_sessions.id` 元数据和最近消息；需要完整消息链时加 `--full`。深入排查时再检查 `chat_tool_replays`、`last_trace`/`pending_confirmation`，最后按 `updated_at` 时间窗口查 `journalctl -u agent-drive-java.service`。只有用户明确给出 Codex 任务 ID 时才使用 Codex thread 工具。最后一条 user 没有 assistant 记录不一定是故障，先确认是否由用户主动停止流。
- **Java index vertical**：`java-auth`/`java-chat` 提供 `/api/v1/index` owner-scoped CRUD；文本索引使用 Tika，图片使用 VisionDescriptionService 以最多四张一批生成 `vision-description-v3` 综合文字描述后交给 Jina，按 `document_type=text|vision` 写入 pgvector，描述前校验 source revision。显式图片索引不复用旧描述；批量视觉响应必须逐项校验 `image_id`，协议失败可降级为逐图请求；Content Service/视觉客户端兼容 Chat Completions 与 Responses 的字符串、分段数组和 `output_text` 文本响应。远程 Index Service 的待向量 chunk 查询必须返回 `file_id`、`source_revision`、`chunk_index`，三者缺失时应失败而不能伪报成功。provider、持久化或路径失败必须在当前响应返回明确错误；Agent 不获得任务创建接口。`migrate` profile 默认 dry-run，只用于受控 legacy 恢复。
- **Java 文档注释**：`backend/src/main/java` 下所有类、接口、枚举、构造器和方法都必须使用简洁中文标准 Javadoc（`/** ... */`）；类写清职责与边界，方法写清用途、关键行为和副作用，按实际情况补齐 `@param`、`@return`、`@throws`。新增或修改 Java 符号不得省略注释，也不得用“执行方法/返回结果”等空泛占位文案。
- **Java file vertical**：`java-files`/`java-auth`/`java-chat` profile 注册 `/api/v1/files`；公共路径必须是 owner 内相对 POSIX 路径，物理文件按 owner UUID 分目录。文件页通过 `q` 搜索、`/info` 查看 revision 和全文/向量状态、`/content` 读取受限完整文本；`?token=` 只允许 raw/download，列表、状态和 mutation 一律 Cookie/Bearer。上传必须服务端复算 MD5，`noclobber` 必须在 `.storage.lock` 内原子发布，文件 mutation 同步更新 metadata、revision、dedupe。
- **文件列表性能与 Agent 异常边界**：Java name search 最多保留 1000 条候选，使用有界 top-k 集合避免对整个文件树执行无界 `sorted()` 缓存；列表只查询现有 metadata，并把缺失/变化项一次批量 upsert，不能逐项写数据库。`LangChainAgentRuntime.executeTool` 只捕获 `Exception` 作为可恢复工具错误，`Error` 必须交给外层终止流，不能转成可 replay 的普通结果

## 6. 安全红线（勿破坏）

- 除 `/api/v1/health` 与 `auth/status|setup|login|logout|pair-exchange` 外，**全部 /api/v1 走 owner resolver 鉴权**；Cookie/Bearer 可全站，设备 `?token=` 只允许 raw/download GET，禁止扩到列表/状态/写接口
- 密码 PBKDF2 只存哈希；设备令牌/配对码服务端只存 SHA-256；配对码一次性 5 分钟；logout 必须服务端吊销所携 session/device credential，不能只删 Cookie
- Java PostgreSQL `users.password_hash` 是认证真相源；legacy auth snapshot 只作为恢复资料，禁止把删除快照当作重置生产认证；8000 端口必须只绑 127.0.0.1（见 deploy/agent-drive-java.service）
- 密钥不进 git：*.keystore、keystore.properties、keystore 密码（仓库外 D:\ds\agent-drive-keystore\）
- 移除设备 = 吊销令牌；重扫配对 = 吊销旧令牌换新

## 7. 修改检查单

- [ ] 后端改动 → `cd backend && mvn -q test && mvn -q -DskipTests package`；前端改动 → `npm run lint && npm test && npm run build && npm run verify:build`；原生改动 → `cd frontend/android && .\\gradlew.bat lintDebug testDebugUnitTest`（发版再构建 APK）；部署优先使用 `scripts/deploy.ps1`
- [ ] 全量门禁：Maven test/package + frontend lint/vitest/build + Android JVM tests 按改动范围全绿再提交
- [ ] 版本号 +1 / APK 构建（仅测试 App 业务或发版时；日常功能迭代跳过打包）
- [ ] 同步文档 + 本 skill：README / docs/* 相应小节 / AGENTS.md（铁律 §0，同一次提交内完成）
- [ ] 整理并提交变更 → 按发布目标执行 `scripts/deploy.ps1` → API health/readiness；脚本不自动 commit/push，是否推送由发布者单独决定

## 8. 环境事实

- 本机（Windows）：JDK 21（Temurin）、Android SDK `C:\Android\Sdk`（build-tools 35 + platform 35）、Gradle `C:\Android\gradle-8.14.3`
- 服务器：`ssh megumin`，Java 服务 `agent-drive-java.service`（127.0.0.1:8000），nginx 13311 单入口；HTTP 代理 127.0.0.1:7890；旧 Python/task Worker unit/source 已删除，rollback archive 位于 `/opt/agent-drive-java/backups/`
- keystore：服务器 `/root/agent-drive-android/agentdrive.keystore`（密码在本地 `D:\ds\agent-drive-keystore\password.txt`）
