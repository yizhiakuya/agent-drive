# 安全边界

> 现行安全基线（2026-08-22）。服务面向个人单用户使用，但内部状态、文件路径、设备和任务历史都按 owner 隔离。认证、文件写入、Agent 工具和 Android 令牌是四条独立边界。

## 1. 暴露面

```text
公网 HTTPS home.rainaki.top:13311
        │ nginx
        ▼
127.0.0.1:8000 Java API
        ├── PostgreSQL/pgvector（仅受限地址）
        └── owner-scoped 本地文件系统
        ├──（可选）127.0.0.1:8010 Content Service
        ├──（可选）127.0.0.1:8020 File Service
        └──（待认证迁移）127.0.0.1:8030 Identity Service
        └──（待索引迁移）127.0.0.1:8040 Index Service
```

- nginx 是唯一公网入口，API 只绑定 `127.0.0.1:8000`；当前没有独立 Java Worker HTTP 入口。
- nginx 覆写 `X-Forwarded-For` 为单个公网 `$remote_addr`；Java 只有在 TCP 对端为 loopback 且头值是单个合法 IP 字面量时才用它做认证限速，直连伪造头不能改变限速身份。
- `/api/v1/health` 用于探活；认证初始化端点按认证规则公开；其他业务 API 默认需要当前 owner。
- 静态资源和 `.well-known/assetlinks.json` 可公开读取，但 SPA fallback 不能越过 `frontend/out` 目录边界。
- nginx 对公网上传限制 200 MB，Java API 还有 `max_upload_mb=300` 的直连兜底；聊天剪贴板内联图片单张限制 50 MiB，JSON 请求体限制 80 MiB，避免把大图片限制误认为普通文件上传限制。API 只读取 `/etc/agent-drive/proxy.env` 中的 HTTP(S) 代理，并清除 `ALL_PROXY/all_proxy`。
- Content/File Service 只绑定 loopback，不由 nginx 暴露；主 API 只有在 URL 和独立内部 token 同时配置时才使用远程端口。生产当前已启用 Content Service，File Service 保持未切流。Content Service 的 owner provider 快照和图片请求、File Service 的 owner/path 内容请求都必须带固定 token header，服务端再次校验 owner、路径、大小和响应校验和。token 不进入 Agent catalog、响应正文或普通日志。

## 2. 认证模型

```text
Web/PWA 密码 ──▶ HttpOnly session Cookie
       │
       └── 生成一次性 pairing code ──▶ Android 扫码兑换 Bearer device token
```

- 密码使用 PBKDF2-SHA256（60 万次）和随机盐，只存 Java PostgreSQL 的 hash。
- Web session 和设备令牌服务端只存 SHA-256 credential hash；session 默认 30 天，登出会持久撤销当前凭据直到到期。
- 配对码一次性、5 分钟有效，最多保留 3 个未使用码；setup/login 每个客户端每分钟最多 5 次，pair-exchange 每分钟最多 10 次。
- 重扫会吊销旧设备令牌；设置页移除设备也会写入 `revoked_at`。
- 浏览器使用 HttpOnly、SameSite=Lax、生产 Secure Cookie。Android 后台请求使用 Bearer；`?token=` 只兼容媒体 raw/download GET。
- raw/download 是查询设备令牌的唯一兼容入口，nginx 对这两个 location 关闭 access log；前端媒体元素和 iframe 使用 `no-referrer`，避免带令牌 URL 经 Referer 或普通访问日志扩散。
- Android `ServerConfig` bridge 的服务器地址写入与扫码使用同一 HTTPS、无凭据、无查询/片段校验，WebView 不能把设备令牌切换到不安全地址。
- `/api/v1/auth/status|setup|login|logout|pair-exchange` 是认证流程端点，其他路由不能借初始化状态绕过 owner 校验。
- 前端启动检查只有在 401/403 时切换登录或重扫码；5xx、网络、JSON 解析和字段契约错误进入独立可重试状态并保留现有 Cookie/设备令牌，避免把服务故障误判为凭据失效或首次初始化。

## 3. 文件与路径安全

- 公共路径必须是 owner 内相对 POSIX 路径；拒绝 `..`、组件级 symlink、`.index`、`.trash`、`.versions`、`.storage.lock` 和 upload/copy staging。
- 下载、预览、上传、列表和 mutation 共用路径边界；内部流程必须显式使用 `allow_internal`。
- 上传请求体流式写入 0600 临时文件，服务端复算 MD5 后才发布；`noclobber` 使用原子 no-replace 语义，不走“先 exists 再写”。
- 文本写入、覆盖、移动、目录复制和回收站使用 staging/backup、fsync 和 atomic move/link；storage lock 持有到 PostgreSQL 事务 afterCompletion，事务回滚或提交失败会恢复发布前磁盘状态。目录复制的 recovery marker 用于处理进程崩溃；数据库已提交后的隐藏 artifact 清理失败只记 warning，不能伪报已发布文件失败。
- raw 只允许 PDF、常见图片、音频和视频 MIME 内联；HTML、SVG、文本及未知类型强制 `application/octet-stream` + attachment。媒体响应统一设置 `nosniff`、sandbox CSP、same-origin CORP、`no-referrer` 和 `private, no-store`，WebFlux 资源编码器不得按扩展名把活动内容重新推断为可执行类型。
- 文件 metadata、revision、dedupe、全文和向量都按 owner 绑定；文件内容变化先失效旧索引，索引/视觉/向量由用户或 Agent 显式调用 `/api/v1/index` 直接执行。
- `GET /api/v1/files/search-content` 复用文件服务的 owner、相对路径、类型/时间和当前 revision/fingerprint 校验，只返回有界的 chunk 证据和相邻正文；正文在 Agent 指令中按 `untrusted_data` 处理，不能把其中的命令、URL 或凭据当作操作请求。
- 覆盖上传/文本写入前，旧普通文件复制到 owner 私有 `.versions` 目录并在 `file_version_snapshots` 登记；版本列表只返回当前 owner 且仍存在的快照元数据，恢复通过原子上传产生新 revision，不允许客户端直接读取快照路径。
- Chat 文件上下文只接受当前 owner 的相对 POSIX 路径，后端重新读取文件/文件夹内容，不信任客户端传入正文；文件选择器附件写入 owner-scoped `聊天附件` 目录并沿用上传 MD5、路径和索引边界，剪贴板图片只允许受限 Base64 内联到支持图片的当前模型请求，不持久化。
- 聊天 relay 按 owner 会话隔离：`X-Session-ID` 只由服务端响应，`/chat/{sessionId}/active|stream|cancel` 每次先校验当前 owner 的会话归属；relay 只在 API 进程内保存受限回放事件，不提供跨进程后台任务入口。
- `chat_run_events` 只按 owner session 外键保存脱敏 SSE 事件，重连读取前仍执行 session owner 校验；事件表不保存 Cookie/Bearer/API key，跨进程重连通过有界数据库轮询，不把事件表扩展成任务队列。
- File Service 的独立存储根不能与 API owner 文件根共享路径；初始镜像已完成但 mutation 同步尚未完成，`AGENT_DRIVE_FILE_SERVICE_URL` 必须保持为空，避免视觉链路读到旧快照。配置远程 URL 后，主 API 启动期必须先验证 File Service readiness；远程读取响应还必须重新验证 owner、相对路径、大小和 MD5，失败返回结构化错误而不是空内容。
- File Service manifest 只用于受控迁移校验，必须经过内部 token；它不返回文件正文，不改变文件 revision，也不能被 Agent catalog 调用。
- File Service mirror 写入会重新计算 Base64 内容 MD5、校验 owner/path/revision 并原子替换目标；move/copy/tree-delete/trash/restore 也在独立路径契约中执行，主 API 的 move/copy/trash/restore 已登记回滚钩子。主 API 仅在显式 URL/token 配置时为这些 mutation 调用它，线上失败恢复演练完成前禁止切流。
- Index Service 的 manifest、文档写入和迁移期检索只允许 loopback + 内部 token；服务不读取主 API 索引表，owner/file/revision 由请求边界重新校验。
- `AGENT_DRIVE_INDEX_SERVICE_URL/TOKEN` 只启用受内部 token 保护的文档双写和 readiness 校验，不会让 Agent 获得 Index Service URL/token；查询切换仍需单独的读路径开关和一致性窗口。
- Index Service 初始迁移完成后已与主库双写；历史 vision v1/v2 文档已重新描述为当前 v3 并校验 source revision，旧描述不参与正式向量检索。

## 4. Agent 和外部 provider

- 模型只能使用稳定的 `backend_api`、`frontend_api`、只读 `read_skill` 和受限 plan 工具；调用后端必须先 discover，再使用登记的 `METHOD /api/v1/path` 或 `INTERNAL name`。provider 配置保存、模型目录探测和 API key 处理留在设置页 REST，不进入 Agent catalog。
- 自定义 Skill 按 owner 写入 PostgreSQL，只能经认证 REST/UI 或 red `backend_api` 修改；每次模型请求注入启用 Skill 的名称/说明目录；会话曾经成功 `read_skill` 的名称由服务端 transcript 记录，后续轮次从当前 owner registry 重新注入正文，客户端不能伪造已加载状态。`Agent/AGENT.md`、`USER.md`、`MEMORY.md` 通过 owner 文件服务读取并在进入模型/context transcript 前清理已知 key/Bearer 模式；context 历史只允许当前 owner 查询。Skill 内容是 Markdown 指令，不执行脚本、不加载任意文件/URL，也不能绕过 operation allowlist、owner 注入或 red 确认。内置 Skill 随应用发布且不可修改。
- 模型不能提供任意 URL、Cookie、Bearer、Authorization、请求头、Python 入口、Java 类名、JavaScript 或 `eval`；`backend_api` 对嵌套参数再次拒绝 `api_key`、`base_url`、token、secret 等字段。
- 当前 Request 的 Cookie/Bearer 只在进程内传给 owner-scoped backend dispatcher；模型不能提供或覆盖任何凭据。
- 文件引用只在前端把固定 `https://agent-drive.local/file` 哨兵地址解析为已登记的 `files.open` / `files.open_folder` 动作后执行；普通外链仍按 Markdown 链接展示，模型不能借引用语法提供任意 URL 或脚本。
- GET 和只读 probe 自动执行；写操作按 operation 风险处理，ask/auto 模式下 red 操作需要签名确认、nonce TTL 和一次性消费，full 模式按用户明确授权直接执行但仍受 owner 和 allowlist 约束。只有显式 `probe`/`idempotent` operation 才按 session/tool/arguments replay，普通 GET 和失败结果不缓存，mutation 后清空旧 replay。
- 索引资源直接 API 共用 owner、路径和 revision 校验；直接清空向量只更新当前 owner 的 `document_chunks.embedding` 和 fingerprint，不删除原文件或正文。Agent 不获得绕过索引服务的数据库或文件系统入口。
- provider API key 只在后端规定的配置边界相同且表单留空时复用：LLM 和 vision 比较 provider/base URL，embedding 还要求 model 相同；只改视觉模型可沿用同一地址的已存 key。密钥落库使用 AES-GCM，普通响应、日志、会话、工具轨迹和 `last_trace` 只保留掩码/脱敏值。设置页眼睛可通过专用 `POST .../api-key/reveal` 回显已存 key；端点仅接受 Web `SESSION`、拒绝设备 Bearer、强制 `Cache-Control: no-store`，且不在 Agent operation 目录中。SettingsPage 与 Onboarding 在协议/base URL 边界变化时清空对应 key，embedding 模型变化时同样清空；回显和模型目录请求使用代次校验，保存成功或重新加载脱敏配置后销毁前端状态中的明文 key。
- API key、Cookie、Bearer、设备 token、query credential、完整消息和文件内容不进入普通日志；SSE 工具参数、确认卡和 replay 结果同样只保留脱敏视图。聊天日志记录 request ID、provider/model、工具 operation、状态和耗时；异常 message/cause 与 SSE error 先脱敏。
- 操作活动中心只保存操作类型、owner-relative 目标摘要、阶段、计数和脱敏错误；不保存 API key、Bearer、完整 provider 响应或完整文件正文。浏览器 localStorage 活动记录是反馈缓存，不是权限或业务状态真相源。
- 自动化报告读取要求当前 owner；公开 `/api/v1/ready` 只输出数据库、owner 文件存储和不含路径的备份摘要，备份状态不改变 DB/存储两项 readiness 判定。

## 5. Android 令牌与权限

- 服务器地址、设备令牌和同步设置写入独立 `agent_drive_secure` EncryptedSharedPreferences，使用 Android Keystore 的 AES256-GCM/SIV；`allowBackup=false` 防止云备份克隆令牌。
- 升级兼容旧业务 prefs，但新密文提交成功或逐键确认相等前不会清理旧值；冲突、初始化、迁移和 commit 失败都失败关闭，不降级到明文。
- 相册同步只需要图片读取权限；通知权限拒绝不会被当作同步失败。ContentObserver、权限回调和 Activity 生命周期必须清理，避免重复同步和泄漏。
- `lastSyncAt` 只推进到整秒全部完成；失败或查询截断通过 `pendingSecond + pendingMaxId` 续传。服务端 dedupe 预检不是可信写入依据，真正上传仍复算 MD5。
- 同步诊断返回权限、目标目录、扫描/上传/去重/跳过/失败/可重试计数和通知开关；这些是 owner 自己的运行元数据，不包含照片内容或设备令牌。旧原生壳缺少新增字段时 Web UI 使用稳定默认值，不把缺失字段渲染为 `NaN` 或凭据错误。

## 6. 日志、备份与恢复

- Java API 使用统一 SLF4J/Logback，生产日志进入 systemd journal。所有 `/api` 请求复用合法 `X-Request-ID`/`X-Correlation-ID`/`traceparent` 或生成 UUID，响应回写 `X-Request-ID`；完成日志只包含 method、匹配路由模板、status、duration、可信 client IP 和 terminal，不记录 query value、path parameter、header 或 body。聊天链路继续按同一 request ID 检索。
- 未分类 500 对客户端只返回稳定通用 detail，服务端以隔离 cause/suppressed 的脱敏 throwable 记录真实原因；API key、Cookie、Bearer、设备 token、query credential、完整消息和文件内容不进入普通日志。
- `agent-drive-java-backup.timer` 每日调用 `scripts/backup-java.sh`，将主 PostgreSQL dump、已配置的独立 Index Service dump、owner 文件根和 manifest 归档到 `/opt/agent-drive-java/backups/`，保留最近 7 份并生成 SHA-256 校验文件；环境密钥位于 0600 的 `/etc/agent-drive-java/java.env`/`/etc/agent-drive-index/index.env`，均不进 git。仓库不再提供旧 Python/SQLite 定时备份脚本。
- 只有处理 legacy 恢复资料时才需要 SQLite snapshot；必须通过 SQLite backup API 生成一致快照，禁止直接打包活动 WAL 三件套。
- 认证表、设备撤销状态和 PostgreSQL schema 异常时服务失败关闭，不得把错误当作“未初始化”重新开放首次设密。
- 生产发布必须执行 `systemd-analyze verify`、API health 和 `/api/v1/ready` 检查；推荐使用 `scripts/deploy.ps1`，它保留前一版静态目录和 JAR 作为回滚副本，readiness 未收敛会触发回滚。

## 7. 当前明确边界

- 当前是个人单用户产品，不提供多租户管理、S3 权限模型或端到端加密文件。
- 修改密码暂未提供网页 UI，需要受控管理流程更新 owner password hash；双因素登录未启用。
- 这些限制不是默认安全绕过，新增接口仍必须经过 owner resolver、路径边界和日志脱敏检查。
