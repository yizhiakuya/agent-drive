# 🏗️ Agent Drive 架构设计 v2.0

> 更新于 2026-08-14：前端迁移 Next.js 16；全站认证、设备注册表、上传去重、Capacitor 安卓原生壳落地；
> 同日新增 SQLite WAL 持久任务系统、独立 Worker、任务中心，以及带源版本/模型指纹/原子发布的向量索引生命周期。
> 认证设计见 docs/security.md，安卓方案见 docs/android.md。

## 一、架构分层（严格单向依赖）

```
┌─────────────────────────────────────────────────┐
│  Presentation 表现层                              │
│  frontend (Next.js 16 · Tailwind 4 · TS · zustand · shadcn/ui)  │
│  静态导出 out/ 由 backend 单服务托管               │
├─────────────────────────────────────────────────┤
│  Interface 接口层（含应用编排）                    │
│  api/v1 (FastAPI) · schemas (Pydantic)           │
│  职责: 协议转换 · 参数校验 · 版本管理 · 用例编排    │
├─────────────────────────────────────────────────┤
│  Domain 领域层                                    │
│  agent/ (loop · tools · memory · prompt)         │
│  tasks/ (service · registry · runner · handlers) │
│  auth/ (密码·令牌·配对码) · devices/ (注册表)      │
│  llm/  (providers · manager)                     │
│  storage/ (local · upload_index)                 │
│  ingest/ (pipeline)                               │
│  职责: 核心业务逻辑，不依赖 HTTP/框架               │
├─────────────────────────────────────────────────┤
│  Infrastructure 基础设施层                        │
│  core/ (config · logging · errors · container)   │
│  tasks/store.py (SQLite WAL) · 本地文件系统         │
│  职责: 技术支撑，被上层依赖                       │
└─────────────────────────────────────────────────┘
依赖规则: 上层依赖下层，下层绝不依赖上层；同层可依赖。
```

## 二、模块职责边界

| 模块 | 职责 | 禁止 |
|------|------|------|
| `core/config.py` | 全部配置（env + 默认值），单例 | 业务逻辑 |
| `core/container.py` | 依赖注入，对象组装 | 具体实现细节 |
| `api/v1/*` | HTTP 协议层 | 业务逻辑 |
| `schemas/*` | 请求/响应模型 | 框架耦合 |
| `agent/` | Agent 决策循环、工具、记忆 | HTTP、存储细节 |
| `llm/` | LLM 协议适配 | 业务逻辑 |
| `storage/` | 文件持久化（接口+实现） | 决策逻辑 |
| `auth/` | 密码（PBKDF2）、会话令牌（HMAC）、设备令牌、配对码（扫码即授权）、限速 | HTTP 细节（由 api 层承载） |
| `devices/` | 设备登记/心跳（JSON 持久化） | 认证逻辑 |
| `storage/upload_index.py` | 上传去重索引（秒传：md5→路径） | 业务决策 |
| `ingest/` | 文本/PDF/OCR 提取、全文 sidecar、版本化向量和检索 | HTTP、任务调度 |
| `tasks/service.py` | 受限业务入队、存储变更转索引任务、默认计划 | 任意用户代码执行 |
| `tasks/store.py` | SQLite schema、状态迁移、租约、事件、计划、Worker 心跳 | 文件/LLM 业务逻辑 |
| `tasks/runner.py` / `handlers.py` | lane 并发、重试/取消、内置任务执行 | HTTP 协议 |

## 三、关键设计决策

### 3.1 依赖注入（core/container.py）
所有服务通过 Container 组装，显式声明依赖，便于测试替身：
```python
container = Container(settings)
agent_service = container.agent_factory()
```
禁止：模块级全局单例、循环导入、隐式状态。

### 3.2 配置管理（core/config.py）
- `Settings` (pydantic-settings)：env 优先，`.env` 支持，默认值兜底
- 敏感信息（API Key）只存 `system/agent-config.json`（agent 自管理），不进 env
- 环境变量统一前缀 `AGENT_DRIVE_`，例如 `AGENT_DRIVE_APP_ENV=dev|test|prod`

### 3.3 日志系统（core/logging.py）
- 统一 logger，结构化 JSON 输出（prod）/ 可读输出（dev）
- 三流分离：app 日志 · 审计日志（audit）· 会话记录（sessions）
- 审计日志追加模式，含 ts/event/result

### 3.4 异常体系（core/errors.py）
```
AppError (基类)
├── ConfigError      # 配置缺失/非法
├── AuthError        # 凭据问题
├── NotFoundError    # 文件/会话不存在
├── PermissionError  # 越权/越界
├── ToolError        # 工具执行失败（结构化，Agent 可读）
└── LLMError         # LLM 调用失败（含类型：timeout/protocol/quota）
```
API 层统一转换为 HTTP 响应；Agent 层转换为工具结果。

### 3.5 LLM 抽象（llm/）
- `base.py`: Provider 协议（chat/test_connection/list_models——设置页模型列表探测用）
- `providers/`: 每协议一个文件，互不感知
- `manager.py`: 配置读写 + 工厂 + 测试
- embedding provider 为独立工厂；任务执行前可热刷新配置，模型切换会改变索引指纹

### 3.6 存储抽象（storage/）
- `base.py`: 仅历史说明 docstring（Storage 协议抽象已移除——全仓无消费者，勿重建死抽象）
- `local.py`: 本地文件系统实现。路径拒绝穿越和任一 symlink 组件；临时文件 0600，发布用 `dirfd + O_NOFOLLOW + os.replace/os.link`，父组件校验与写入不再有 symlink TOCTOU；复合写由进程内锁 + Linux `flock` 串行化
- 覆盖/创建/文本追加均先写临时文件、`fsync` 后原子发布；文件以 `link/replace` 为可见性提交点，提交后的目录 `fsync` 或临时链接清理失败不会把已发布内容伪报为失败。追加锁覆盖“读旧值→追加→替换”全过程，避免并发追加丢内容。目录复制先在隐藏 staging 目录完整构建并 fsync，再用 Linux `renameat2` 原子 no-replace/exchange 一次发布；不支持原子系统调用时仅在 mutation lock 内做 no-replace/可回滚重命名（协作进程间安全，对不遵守 flock 的外部写入者仍有竞态窗口；生产部署为 Linux renameat2），并在挪动旧目标前 durable 写 recovery marker：重启会恢复尚未提交的旧目录或清理已提交后的 backup。提交点后的清理失败不会伪报复制失败；无 marker 的 `.copy-old.*` 因无法证明可删而保守保留
- `.index`、`.trash`、`.storage.lock` 和在途 `.upload/.copy` 命名空间在公共 `resolve`/文件 API 层直接拒绝，仅内部流程可显式访问
- 回收站 `.trash` 的输入路径和 metadata 内路径都重新校验；每次删除生成唯一 `trash_id`，同一路径多个历史版本可独立恢复；元数据也原子发布。恢复 API 正式使用 `trash_id`，暂兼容旧 `path`；孤儿 metadata 不展示并由清空操作回收
- `upload_index.py`: 秒传去重索引（md5→path，双向映射）；sidecar `0600` 文件锁下每次 reload→modify→fsync→replace，支持 Linux 多进程实例。只有服务端实算 hash 的 `verified` 条目可用于免传，且必须绑定发布 revision，外部/并发覆盖后 lookup 自动失效
- 索引生命周期自动同步：`container` 组装时 `storage.attach_index()` 反向注入，rename/move/删除/回收站/覆盖写等内容变更自动递归 `forget_path` 失效对应条目；存储与索引统一采用 storage→index 锁序，发布覆盖期间不会暴露陈旧秒传命中。上传发布成功后的 `record` 登记是优化项，登记失败只记 warning 不使上传报错（避免客户端重试经 noclobber 落成重复照片）；verified 查询靠 exists+revision 自愈陈旧条目
- 搜索索引生命周期：`storage.attach_change_listener(tasks.handle_storage_change)` 在内容变更后同步失效旧 sidecar，再按文件/目录入队增量索引或批量重建；内部命名空间和 staging 永不触发递归任务
- 业务代码直接依赖 `local.LocalStorage`（当前唯一实现，由 container 统一组装）

### 3.7 Agent 工具注册（agent/tools/）
- 每个工具 = 独立模块文件（files.py / system.py / analytics.py）
- 工具注册表：spec(JSON Schema+doc) + fn + level + validator
- 安全分级 green/yellow/red 由注册表统一管理

### 3.8 持久后台任务（tasks/）

```
API / 文件写入 / 定时计划
          │ 受限 TaskService 入队
          ▼
 system/tasks.sqlite3 (WAL)
 jobs + job_events + schedules + workers
          │ claim(lane, lease)
          ▼
 独立 Worker ──▶ handler ──▶ 进度/结果/重试
```

- **状态机**：`queued → running → succeeded|failed|cancelled`，瞬态错误进入 `retry_wait`；运行中取消先到 `cancelling`，由处理器协作检查
- **可靠领取**：`BEGIN IMMEDIATE` 串行化 claim；Worker 持续刷新任务租约和自身在线心跳，空闲等待兼容 Python 3.10 的 `asyncio.TimeoutError`。进程崩溃后租约过期自动恢复，达到 `max_attempts` 则失败，不会无限循环
- **去重**：活跃任务的 `dedupe_key` 使用 SQLite 部分唯一索引；索引键包含路径、源版本和 embedding 指纹，同一版本只执行一次
- **lane**：`index`、`orchestration`、`maintenance`、`automation` 独立并发；批量重建是 orchestration 父任务，拆成 `index.file` 子任务，避免父任务占住索引执行位
- **总览与覆盖率**：列表和状态计数只展示顶层任务，批量子任务汇总到父任务进度，避免重复计数；索引覆盖率需要扫描文件树，API 缓存 15 秒以限制大网盘上的刷新开销
- **事件与保留**：相同进度不重复落事件；SSE 无游标连接从当前尾部开始。每日维护保留至少最近 2000 条终态任务，并清理超过 30 天的旧历史；仍保留子任务时不会先删父任务，避免子任务漂成顶层记录
- **运行形态**：dev 默认 API 内嵌 Worker；prod API 禁用内嵌执行，由 `python -m app.tasks.worker` / `agent-drive-worker.service` 单独运行

### 3.9 向量索引一致性（ingest/）

- 全文元数据保存 `source_revision(size:mtime_ns)` 与 `extractor_version`；向量元数据再保存 provider/model/base URL 生成的 fingerprint、维度与 `chunk_version`
- 文档向量调用 `retrieval.passage`，查询向量调用 `retrieval.query`；模型或切块策略变化后旧向量自动失效
- `.npy` 与 `.vector.json` 均通过同目录临时文件 + `os.replace` 原子发布；检索必须同时通过源版本、指纹、维度和块数检查
- 上传请求只写文件并入队；PDF/OCR 提取在线程池执行。重命名、移动、删除、覆盖会先失效旧索引，读路径不会返回陈旧向量

### 3.10 版本化 API
- `/api/v1/*`，版本路由聚合在 `api/v1/router.py`
- 错误语义化：存储层异常映射 404/409/403、裸 OSError（磁盘/IO 故障）映射 500 重试（files.py `_friendly`），未匹配的 `/api` 路径返回 JSON 404（SPA fallback 不吐 HTML）；客户端把永久 4xx 当可跳过照片，服务端瞬时故障绝不能落入 4xx
- 上传端点：multipart 文件按 1MiB 分块流入数据根内 0600 临时文件，同时实算 MD5 和执行 413 限幅；声明 MD5 不一致即拒绝并清理。成功后原子发布、条件写 verified/revision 索引并返回后台索引任务 ID，不在请求事件循环执行 ingest
- `GET /files/dedupe?md5=...` 是无副作用的 verified-only 免传预检；未命中 404。Android 先预检，真正上传仍由服务端复算，永不信任客户端 hash
- 任务端点：`/tasks` 列表/详情/SSE，受限的重建和清理命令，以及取消/重试；不提供任意 task type/payload 创建接口
- 破坏性变更升 v2，不破坏客户端

## 四、目录结构（目标态）

```
backend/
├── app/
│   ├── main.py              # 入口：组装 Container + 启动
│   ├── core/                # 基础设施
│   │   ├── config.py  logging.py  errors.py  container.py
│   ├── api/
│   │   ├── v1/              # 版本化路由
│   │   │   ├── router.py  auth.py  chat.py  config.py  files.py
│   │   │   ├── sessions.py  automation.py  devices.py  tasks.py
│   │   └── deps.py          # 依赖获取 + get_owner 统一鉴权
│   ├── schemas/             # Pydantic 模型
│   │   ├── chat.py  config.py  files.py  sessions.py
│   ├── agent/               # 领域：Agent（单一职责拆分）
│   │   ├── loop.py           #   编排引擎（_execute 统一生成器）
│   │   ├── prompt.py         #   提示词工程
│   │   ├── context.py        #   上下文管理（token 预算截断）
│   │   ├── confirm.py        #   高风险操作确认判定
│   │   ├── router.py         #   意图路由（闲聊/任务）
│   │   ├── skills.py         #   技能包注册表
│   │   ├── tools/  registry.py  files.py  system.py  analytics.py
│   │   ├── memory/  preferences.py  sessions.py
│   │   ├── onboarding.py  prompt.py  router.py  skills.py
│   │   ├── scheduler.py        # M3: 规则自动执行(每天 03:30)
│   │   └── tools/  files.py  system.py  analytics.py
│   │               plan.py  memory.py  registry.py
│   ├── auth/               # 认证（纯标准库，零新依赖）
│   │   └── store.py          #   密码/会话令牌/设备令牌/配对码/限速
│   ├── devices/             # 设备注册表 registry.py
│   ├── llm/
│   │   ├── base.py  manager.py  embeddings.py
│   │   └── providers/  openai_compat.py  responses.py  anthropic.py
│   ├── storage/  base.py  local.py（原子写+符号链接拒绝） upload_index.py（秒传去重）
│   ├── ingest/  pipeline.py    # 提取 + 全文/版本化向量 sidecar
│   └── tasks/
│   │   ├── models.py  store.py  registry.py  runner.py
│   │   └── service.py  handlers.py  worker.py
├── tests/
│   ├── conftest.py
│   ├── unit/  test_agent test_critic test_reliability test_retry
│   │          test_compress test_write_tools test_memory
│   │          test_bugfixes test_ingest_m2 test_auth
│   │          test_devices test_upload_index test_storage_safety test_tasks
│   └── integration/  test_api.py  test_tasks_api.py
├── scripts/  mock_llm.py  benchmark_real.py  backup.sh
├── system/  agent-config.json  tasks.sqlite3（运行时，gitignored）
├── data/    文件工作区 + Agent/(AGENT.md/USER.md/MEMORY.md/notes)
├── pyproject.toml  .env.example  Dockerfile
└── requirements.txt（doc-only，pyproject 为唯一真相源）

frontend/（Next.js 16 App Router + TS + Tailwind v4 + shadcn/ui，设计规范见 docs/frontend-design.md）
├── src/
│   ├── app/  layout.tsx  page.tsx  globals.css(@theme 设计 token)
│   ├── components/
│   │   ├── ui/  shadcn/ui 组件库（button/input/select/combobox/card/badge/switch/skeleton/alert/separator）
│   │   ├── chat/  ChatPanel.tsx  ToolStep.tsx  ContextBar.tsx  PlanCard.tsx
│   │   ├── files/  FilePage.tsx  FilePanel.tsx
│   │   ├── sessions/  SessionList.tsx
│   │   ├── settings/  SettingsPage.tsx  ConnectAppCard.tsx
│   │   │            DevicesCard.tsx  PhotoSyncCard.tsx
│   │   ├── tasks/  TaskPage.tsx
│   │   ├── onboarding/  Onboarding.tsx（仅 web 渲染）
│   │   ├── auth/  LoginCard.tsx  RescanCard.tsx  ServerNotReadyCard.tsx
│   │   ├── PullToRefresh.tsx（全局下拉刷新）
│   │   └── ToastStack.tsx
│   ├── lib/  store.ts(zustand)  events.ts(事件总线常量)
│   │         format.ts(工具函数)  api/(client/chat/files/config/sessions/devices/auth/tasks)
│   │         native/(server-config photo-sync 插件桥)
│   └── （vitest 测试与源码同目录）
├── out/  next build 静态导出（backend 托管）
├── android/  Capacitor 7 原生壳（扫码配对/相册同步/设备令牌，见 docs/android.md）
└── next.config.ts(output:'export')  vitest.config.mts  capacitor.config.ts
```

生产部署为两个 systemd 进程：`agent-drive.service` 托管 uvicorn API + 静态 out/，
`agent-drive-worker.service` 执行持久任务且不监听端口。两者读取 0600 的
`/etc/agent-drive/proxy.env`，仅使用 HTTP(S) proxy 并清除 `ALL_PROXY`；nginx 13311 仍是唯一公网入口。
`scripts/backup.sh` 对任务数据库做 SQLite 在线一致性快照后再归档。docker compose 仅作备用开发形态。

## 五、扩展点与演进状态（2026-08-14）

| 扩展 | 状态 | 接入点 |
|------|------|--------|
| 语义搜索 | ✅ 已落地 | ingest/pipeline + llm/embeddings（Jina 云）+ 带版本/指纹的原子 `.index` sidecar（规模化迁 pgvector） |
| 持久任务系统 | ✅ 已落地 | tasks/ SQLite WAL、独立 Worker、租约/心跳/恢复、去重、取消/重试、父子任务、定时计划、任务中心 |
| 规则自动执行 | ✅ 已落地 | agent/scheduler.py 入队 automation.run + /automation/latest 主动汇报 |
| 文件理解 | ✅ 已落地 | M2a 摄入(PDF/OCR/文本) + M2b 语义 + M2c 问答 |
| 回收站 | ✅ 已落地 | storage .trash(30天自动清) + list_trash/restore_file/empty_trash 工具 + /files/trash API |
| 影音在线播放 | ✅ 已落地 | preview_kind video/audio + raw 端点 media_type + 前端 <video>/<audio> |
| 分享到网盘 | ✅ 已落地 | Web Share Target(manifest share_target) + /files/upload-share 端点 |
| 文件页人工操作 | ✅ 已落地 | /files/rename|move|copy|delete|mkdir API + 前端工具栏/回收站面板 |
| 流式输出 | ✅ 已落地 | SSE /chat/stream + 前端 chatStream |
| 向量库迁移 | 待规模需求 | db/(pgvector, compose 已备) |
| 音视频转写 | 未做（资源评估后） | ingest 加 whisper 解析器 |
| **认证（单用户）** | ✅ 已落地 | auth/store.py（纯标准库 PBKDF2/HMAC）+ api/v1/auth.py + deps.get_owner 统一鉴权（Cookie/Bearer/?token=）；扫码配对免密，详见 docs/security.md |
| 多用户 | 未做（个人项目） | 若需要：auth 层扩展 user 维度 |
| **安卓原生壳** | ✅ 已落地 | frontend/android（Capacitor 7）：扫码配对、相册自动同步（WorkManager+秒传去重+整秒检查点+进度可视）、设备心跳、下拉刷新，详见 docs/android.md |
| **上传去重（秒传）** | ✅ 已落地 | `/files/dedupe` verified-only 预检 + `/files/upload` 服务端流式实算 MD5；索引绑定文件 revision 并随内容变更自动失效 |
| S3 存储 | 未做 | 若做：新增实现类并在 container 切换组装点（业务直接依赖 LocalStorage，无协议抽象） |
