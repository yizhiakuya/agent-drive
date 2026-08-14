# 🏗️ Agent Drive 架构设计 v2.0

> 更新于 2026-08-14：前端迁移 Next.js 16；ingest/embeddings/scheduler 落地；应用层与接口层合并；
> 同日新增：全站认证（auth/：密码+会话+设备令牌+扫码配对）、设备注册表（devices/）、上传去重索引（storage/upload_index.py）、
> Capacitor 安卓原生壳（frontend/android）。认证设计见 docs/security.md。

## 一、架构分层（严格单向依赖）

```
┌─────────────────────────────────────────────────┐
│  Presentation 表现层                              │
│  frontend (Next.js 16 · Tailwind · TS · zustand)  │
│  静态导出 out/ 由 backend 单服务托管               │
├─────────────────────────────────────────────────┤
│  Interface 接口层（含应用编排）                    │
│  api/v1 (FastAPI) · schemas (Pydantic)           │
│  职责: 协议转换 · 参数校验 · 版本管理 · 用例编排    │
├─────────────────────────────────────────────────┤
│  Domain 领域层                                    │
│  agent/ (loop · tools · memory · prompt)         │
│  auth/ (密码·令牌·配对码) · devices/ (注册表)      │
│  llm/  (providers · manager)                     │
│  storage/ (local · upload_index)                 │
│  ingest/ (pipeline)                               │
│  职责: 核心业务逻辑，不依赖 HTTP/框架               │
├─────────────────────────────────────────────────┤
│  Infrastructure 基础设施层                        │
│  core/ (config · logging · errors · container)   │
│  db/ (SQLAlchemy · pgvector M2) · queue (M2)     │
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
| `ingest/` | 摄入管线（M2 已落地：文本/PDF/OCR 提取 + 全文/语义搜索） | ✅ |

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
- 环境：`APP_ENV=dev|test|prod`

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
- `base.py`: Provider 协议（chat/test_connection）
- `providers/`: 每协议一个文件，互不感知
- `manager.py`: 配置读写 + 工厂 + 测试
- M2 扩展点：流式输出、embedding provider

### 3.6 存储抽象（storage/）
- `base.py`: Storage 协议（list/read/write/move/delete/stat）
- `local.py`: 本地文件系统实现（路径安全：防穿越 + 组件级拒绝符号链接；写入原子：tmp + os.replace，独占写 os.link 防同名竞态）
- `upload_index.py`: 秒传去重索引（md5→path，双向映射，读写加锁）
- 索引生命周期自动同步：`container` 组装时 `storage.attach_index()` 反向注入，rename/move/删除/回收站/覆盖写等一切内容变更自动 `forget_path` 失效对应条目——秒传永不会命中已移动/已覆盖的旧路径
- 业务代码只依赖 base，可平滑切换实现

### 3.7 Agent 工具注册（agent/tools/）
- 每个工具 = 独立模块文件（files.py / system.py / analytics.py）
- 工具注册表：spec(JSON Schema+doc) + fn + level + validator
- 安全分级 green/yellow/red 由注册表统一管理

### 3.8 版本化 API
- `/api/v1/*`，版本路由聚合在 `api/v1/router.py`
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
│   │   │   ├── sessions.py  automation.py  devices.py
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
│   └── ingest/  pipeline.py    # M2: 提取(文本/PDF/OCR)+索引+向量(Jina 云)
├── tests/
│   ├── conftest.py
│   ├── unit/  test_agent test_critic test_reliability test_retry
│   │          test_compress test_write_tools test_memory
│   │          test_bugfixes test_ingest_m2 test_auth
│   │          test_devices test_upload_index test_storage_safety
│   │          （共 13 套：pytest 收集 5 + 脚本直跑 8）
│   └── integration/  test_api.py
├── scripts/  mock_llm.py  benchmark_real.py
├── system/  agent-config.json（gitignored, agent 自管理）
├── data/    文件工作区 + Agent/(AGENT.md/USER.md/MEMORY.md/notes)
├── pyproject.toml  .env.example  Dockerfile
└── requirements.txt（doc-only，pyproject 为唯一真相源）

frontend/（Next.js 16 App Router + TS + Tailwind v4）
├── src/
│   ├── app/  layout.tsx  page.tsx  globals.css(@theme 设计 token)
│   ├── components/
│   │   ├── chat/  ChatPanel.tsx  ToolStep.tsx  ContextBar.tsx  PlanCard.tsx
│   │   ├── files/  FilePage.tsx  FilePanel.tsx
│   │   ├── sessions/  SessionList.tsx
│   │   ├── settings/  SettingsPage.tsx  ConnectAppCard.tsx
│   │   │            DevicesCard.tsx  PhotoSyncCard.tsx
│   │   ├── onboarding/  Onboarding.tsx（仅 web 渲染）
│   │   ├── auth/  LoginCard.tsx  RescanCard.tsx  ServerNotReadyCard.tsx
│   │   ├── PullToRefresh.tsx（全局下拉刷新）
│   │   └── ToastStack.tsx
│   ├── lib/  store.ts(zustand)  events.ts(事件总线常量)
│   │         format.ts(工具函数)  api/(client/chat/files/config/sessions/devices/auth)
│   │         native/(server-config photo-sync 插件桥)
│   └── （vitest 测试与源码同目录）
├── out/  next build 静态导出（backend 托管）
├── android/  Capacitor 7 原生壳（扫码配对/相册同步/设备令牌，见 docs/android.md）
└── next.config.ts(output:'export')  vitest.config.ts  capacitor.config.ts
```

部署形态：systemd 单服务（uvicorn 托管 backend + 静态 out/）；docker compose 备用。

## 五、扩展点与演进状态（2026-08-14）

| 扩展 | 状态 | 接入点 |
|------|------|--------|
| 语义搜索 | ✅ 已落地 | ingest/pipeline + llm/embeddings（Jina 云）+ .index 向量 sidecar（规模化迁 pgvector） |
| 规则自动执行 | ✅ 已落地 | agent/scheduler.py + run_automation_now/automation_status 工具 + /automation/latest API(主动汇报) |
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
| **上传去重（秒传）** | ✅ 已落地 | storage/upload_index.py + /files/upload 的 md5/noclobber 表单字段；索引随内容变更自动失效（attach_index） |
| S3 存储 | 未做 | 若做：按 base 协议新增实现 + container 切换 |
