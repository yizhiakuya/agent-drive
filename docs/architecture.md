# 🏗️ Agent Drive 架构设计 v1.0

## 一、架构分层（严格单向依赖）

```
┌─────────────────────────────────────────────────┐
│  Presentation 表现层                              │
│  frontend (React) · CLI (M3) · WebSocket (流式)   │
├─────────────────────────────────────────────────┤
│  Interface 接口层                                 │
│  api/v1 (FastAPI) · schemas (Pydantic)           │
│  职责: 协议转换 · 参数校验 · 鉴权 · 版本管理       │
├─────────────────────────────────────────────────┤
│  Application 应用层                               │
│  services/ (会话服务 · 文件服务 · 配置服务)         │
│  职责: 用例编排 · 事务边界 · 跨领域协调             │
├─────────────────────────────────────────────────┤
│  Domain 领域层                                    │
│  agent/ (loop · tools · memory · prompt)         │
│  llm/  (providers · manager)                     │
│  storage/ (local · s3) · ingest/ (pipeline)      │
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
| `ingest/` | 摄入管线（M2） | — |

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
- `local.py`: 本地文件系统实现（路径安全、幂等）
- `s3.py`: M2 MinIO 实现（占位）
- 业务代码只依赖 base，可平滑切换

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
│   │   │   ├── router.py  chat.py  config.py  files.py  sessions.py
│   │   └── deps.py          # 依赖获取
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
│   │   └── memory/  preferences.py  sessions.py
│   ├── llm/
│   │   ├── base.py  manager.py  types.py
│   │   └── providers/  openai_compat.py  responses.py  anthropic.py
│   ├── storage/
│   │   ├── base.py  local.py  s3.py
│   └── ingest/             # M2 占位
│       └── pipeline.py
├── tests/
│   ├── conftest.py          # 共享 fixtures
│   ├── unit/  test_agent.py  test_critic.py  test_reliability.py
│   └── integration/  test_api.py
├── scripts/  run_dev.sh  benchmark.sh
├── pyproject.toml  .env.example  Makefile
└── requirements.txt

frontend/
├── src/
│   ├── main.jsx  App.jsx
│   ├── api/  client.js  chat.js  files.js  sessions.js
│   ├── components/  chat/  files/  sessions/  onboarding/  common/
│   ├── hooks/  useChat.js
│   └── styles/
```

## 五、M2 扩展点（架构已预留）

| 扩展 | 接入点 |
|------|--------|
| 语义搜索 | ingest/pipeline + storage/index (pgvector) |
| 流式输出 | llm/base 增加 stream 协议 + WS 通道 |
| 定时任务 | services/scheduler + core/queue |
| 认证 | api/deps + core/auth |
| 多用户 | 会话/文件增加 owner 维度 |
| S3 存储 | storage/s3.py 实现 base 协议 |
