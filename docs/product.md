# Agent Drive 产品文档

> 现行产品基线：2026-08-20。本文描述当前生产实现和用户可见边界，不把历史快照、实验能力或未来规划写成已交付功能。

## 1. 产品定义

Agent Drive 是面向个人用户的 Agent-first 私人网盘。用户可以直接操作文件，也可以用自然语言让 Agent 浏览、搜索、理解和整理网盘内容。

产品的核心价值是把三类能力放在同一个 owner-scoped 空间里：

- 文件管理：上传、预览、全文查看、移动、复制、重命名、回收站和恢复；
- 内容理解：名称搜索、语义搜索、文本抽取、向量索引和可选的图片描述；
- Agent 协作：对话、工具轨迹、确认、Skill、任务入队、自动化规则和用户偏好。

当前产品面向个人单用户使用。数据边界按 owner 设计，但产品不提供多租户管理和组织级权限模型。

## 2. 目标用户和使用场景

### 目标用户

- 希望把个人文档、照片和 Agent 记忆集中管理的个人用户；
- 需要按内容而不是只按文件名找资料的用户；
- 希望通过手机后台同步照片，并在网页端统一整理的人；
- 需要保留本地文件所有权、同时使用外部模型进行理解和检索的技术用户。

### 典型场景

1. 用户登录网页后，让 Agent 查看网盘结构并给出整理建议。
2. 用户用名称搜索或语义搜索定位合同、预算、图片和历史记录。
3. 用户在文件页直接预览、移动或放入回收站，敏感写操作由确认流程保护。
4. 用户在 Android App 扫码配对，后台同步相册；服务端负责去重、断点续传和索引入队。
5. 用户在任务中心查看索引、视觉处理、自动化和维护任务的状态与失败原因。

## 3. 用户入口和核心流程

### 3.1 首次使用

1. Web 端使用密码登录，服务端创建 HttpOnly session Cookie。
2. 首次配置页选择对话 Provider，填写地址和 API key 后可直接获取模型目录并选择模型；当前支持 OpenAI 兼容、OpenAI Responses 和 Anthropic。聊天页也可以从当前 Provider 的模型目录中选择本轮使用的模型，未选择时沿用默认模型。
3. 按需配置 Jina embedding 和 OpenAI 兼容视觉模型。留空 API key 只有在对应凭据边界与已存配置一致时才复用；视觉模型可在 provider 和地址不变时直接切换。
4. 如需手机同步，在设置页生成一次性二维码，用 Android App 扫码配对。

### 3.2 日常对话

1. 在“对话”页新建或选择会话。
2. 用自然语言描述查找、整理或理解文件的目标。
3. Agent 先发现可用能力，再调用精确的后端或前端操作；工具步骤、reasoning 和正文分开显示。
4. 只读查询可直接执行；写入、删除、移动等高风险操作需要确认，并支持确定性重放。
5. 会话消息、标题和工具轨迹按 owner 保存，敏感字段在持久化和展示前脱敏；Provider 或工具失败时也保留服务端已创建的会话、脱敏错误消息和最后工具轨迹，后续发送继续复用同一会话。

### 3.3 文件管理和检索

文件页以当前目录为中心，支持名称/路径搜索和语义搜索。语义搜索使用 Jina `retrieval.query` 生成查询向量，再从当前 embedding fingerprint 有效的 pgvector chunk 中返回最佳文件结果。

文件写入或变化后，API 只负责持久化和入队；Java Worker 异步完成 Tika/Tesseract 抽取、全文、embedding 和 vision 索引。索引状态与文件 revision 绑定，旧版本结果不能参与检索。

### 3.4 Android 相册同步

1. App 扫码兑换 Bearer 设备令牌。
2. 用户配置相册同步开关、网络条件、周期和目录。
3. WorkManager 触发同步，MediaStore 按秒级 checkpoint 扫描照片。
4. 上传前做服务端去重预检，真正上传仍由服务端复算 MD5；失败秒通过 pending second/id 续传。
5. 文件发布后由 outbox 触发索引任务，App 可查看同步进度和设备活跃状态。

## 4. 功能地图

| 模块 | 当前能力 | 依赖或边界 |
|------|----------|------------|
| 对话 | 会话、流式正文、上下文注入、reasoning、工具步骤、确认、停止、后台不断流切换、失败会话恢复、标题摘要、本轮模型选择 | 需要已配置且可用的对话 Provider；模型选择只覆盖当前请求 |
| 文件 | 列表、名称搜索、语义搜索、预览、全文、上传、移动、复制、重命名、回收站 | 文件保存在 owner-scoped 本地文件系统 |
| 内容索引 | 文本抽取、全文、embedding、图片描述、revision 校验 | 由独立 Worker 异步处理；embedding/vision 为可选配置 |
| 任务中心 | 顶层任务统计、筛选、阶段/文件/批次进度、执行输入/结果/失败详情、取消/重试、单条删除和终态批量清理、索引重建 | PostgreSQL 是唯一任务状态源；运行中详情通过任务事件持续刷新；活动任务和活动子任务不会被清理 |
| 自动化 | 计划任务、自动化执行、每日报告和用户偏好 | 执行结果依赖 Worker 与对应 handler |
| 设置 | LLM、embedding、vision、模型探测、二维码配对、设备列表、退出登录 | 已存 API key 默认显示掩码，可点击眼睛临时回显；服务端加密存储 |
| Skills | 内置 Skill、自定义 Skill 搜索/新建/编辑/启停/删除、Agent 按需读取 | 指令只编排登记工具；内置 Skill 只读，自定义 Skill 按 owner 隔离 |
| Android App | 扫码连接、加密令牌、相册同步、去重、断点续传、同步状态 | Capacitor 7；当前没有 iOS 客户端 |

## 5. 产品交互原则

- 对话、文件、任务和设置是四个主入口；用户可以在 Agent 与直接操作之间切换，也可以在一个会话仍生成或执行工具时查看和运行其他会话。
- 读操作优先保持即时反馈；抽取、向量化、视觉识别和维护任务异步执行。
- 文件 mutation 以原子发布为可见性提交点，后续索引通过事件链异步收敛。
- Agent 只使用稳定的 `backend_api`、`frontend_api` 和只读 `read_skill` 能力，不接受任意 URL、凭据、请求头或代码入口。
- 移动端优先保证触控尺寸、全屏预览和无横向滚动；Web 和 Android 复用同一套静态前端。
- 任务中心按生命周期提供操作：活动任务可查看进度并取消，失败或已取消任务可重试，所有已结束任务可查看详情并在二次确认后删除。
- “清理已结束”只处理完成、失败和已取消记录；父任务存在活动子任务时保留，父任务安全删除时连同已结束子任务一起回收，避免用户误以为 30 天前才可清理。

## 6. 数据、认证和安全边界

```text
Web / Android
      │ HTTPS :13311
      ▼
nginx → Java API :8000 → PostgreSQL / pgvector
                         └→ owner-scoped 文件系统

Java Worker → PostgreSQL leases/events/outbox
            └→ 抽取、索引、计划任务和外部 provider
```

- PostgreSQL 保存用户、session、设备、会话、Skill、文件 metadata/revision、任务、全文和向量状态。
- 实际二进制文件以及用户可见的 `AGENT.md`、`USER.md`、`MEMORY.md` 保存在 owner 目录。
- Web 使用密码和 HttpOnly Cookie；Android 使用扫码配对得到的 Bearer 设备令牌。
- 公共文件路径使用 owner 内相对 POSIX 路径；拒绝越界、符号链接和内部 staging 路径。
- 上传由服务端复算 MD5，文件写入、复制、覆盖和回收站使用 staging、fsync 和原子发布。
- provider API key 使用加密存储；日志、会话、工具轨迹和 SSE 错误不回显完整密钥、Cookie、Bearer 或设备令牌。
- nginx 公网上传限制为 200 MB，Java API 另有 300 MB 直连兜底限制。

详细规则见 [`security.md`](security.md) 和 [`architecture.md`](architecture.md)。

## 7. 部署和运维模型

生产使用同一个 Java 21 Maven artifact，以两种模式运行：

- `agent-drive-java.service`：API、SSE、静态前端和轻量请求编排；
- `agent-drive-java-worker.service`：任务租约、计划任务、文件抽取、全文/向量/视觉索引。

API 只监听本机 `127.0.0.1:8000`，公网由 nginx 暴露 `13311`。生产部署由 `scripts/deploy.ps1` 编排，备份由 `agent-drive-java-backup.timer` 每日执行，保留 PostgreSQL dump、owner 文件根和 SHA-256 manifest。

发布后的基本验收包括：

1. Java artifact 和前端静态导出构建通过；
2. systemd unit 通过 `systemd-analyze verify`；
3. API、Worker 均为 active；
4. `/api/v1/health` 返回成功；
5. 浏览器完成登录、主页面、文件搜索、任务/设置和移动端布局 smoke test。

## 8. 当前产品边界

以下能力当前明确不属于产品契约：

- S3 或其他对象存储后端；
- 多用户、团队空间、组织角色和租户管理；
- iOS 客户端；
- 音视频转写；
- 端到端加密文件存储；
- 可分享的深层页面 URL 路由。

embedding 和 vision 没有配置时，对应索引或搜索能力不可用；产品应返回明确的配置/服务错误，不能伪装成“已向量化”或空成功结果。

## 9. 当前验收备注

2026-08-20 的生产浏览器 smoke test 已确认：登录页、对话页、文件页、名称搜索、语义搜索、任务页、设置页，以及 320/390/407 宽度下的移动布局可以加载，未发现前端控制台 error/warn 或横向溢出。

本次验收还记录了三个需要进入产品/运维 backlog 的现象：

- 文件夹行点击打开空白预览面板，没有进入目录；
- 任务中心存在大量 `automation handler unavailable` 历史失败任务；
- 任务顶部“有效向量”统计与实际可返回语义搜索结果不一致。

这些现象不改变本文对产品边界的描述，修复后应同步更新本节或移除已过期备注。

## 10. 相关文档

- [`README.md`](../README.md)：项目总览、快速开始和部署入口；
- [`architecture.md`](architecture.md)：模块边界、数据所有权和运行拓扑；
- [`security.md`](security.md)：认证、密钥、文件安全和生产暴露面；
- [`frontend-architecture.md`](frontend-architecture.md)：前端状态、请求生命周期和 Chat 流；
- [`android.md`](android.md)：Android 壳、相册同步和构建发布；
- [`agent-definition.md`](agent-definition.md)：Agent 工具、确认和可靠性契约。
