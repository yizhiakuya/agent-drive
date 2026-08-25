# 微服务演进架构

> 当前 API 仍是 Java 模块化边缘层；Content、File、Identity、Index 和 Agent State Service 已独立部署并通过 loopback/internal token 接入生产。文件 metadata/mutation 编排暂留 API 事务边界，文件内容读和物理 mutation 通过 File Service 镜像契约保持双端一致。

## 1. 当前基线

当前生产拓扑由 Java API 和独立 Content/File 进程组成；只有 Content Service 已接入业务请求：

```text
Browser / Android
        |
      nginx
        |
Java API :8000
   |              |
PostgreSQL     owner 文件根
   |              |
 pgvector    Content Service :8010
                           |
                       Vision Provider
       File Service :8020（已接入内容读/物理 mutation 镜像）
       Agent Service :8050（聊天 session/transcript/replay/confirmation/run/SSE state）
```

后端源码已经形成若干逻辑模块，但模块之间主要通过进程内调用、同一 PostgreSQL 和同一 owner 文件根连接。当前 `backend_api` 是 Agent 内部 dispatcher，不能把模型看到的工具名直接当作服务边界。

## 2. 逻辑模块与目标服务

| 逻辑模块 | 当前代码边界 | 第一阶段目标 | 最终数据所有权 |
|---|---|---|---|
| Gateway/API | `api`、WebFlux、SSE、静态资源 | 保持 Java API 边缘层 | 无业务数据 |
| Identity | `api/auth`、`auth`、部分 `devices` | Identity Service | users、sessions、pairing、device credentials |
| File/Storage | `api/files`、`files`、文件持久化适配器 | File Service | files、revisions、trash、dedupe、versions、object metadata |
| Agent/Chat | `api/chat`、`agent`、会话和 Skill | Agent Service | chat_sessions、chat_messages、replay、confirmation、skills |
| Content Intelligence | `vision`、Tika、embedding 调用 | Content Service | provider 调用、description、抽取版本 |
| Search/Index | `index`、pgvector | 先与 Content 合并，后续独立 | documents、chunks、embeddings |
| Device Sync | `api/devices`、`devices`、Android PhotoSync | 先依赖 File API | sync_state、上传检查点和设备同步状态 |
| Platform | `infrastructure`、`net`、日志、加密 | 各服务内部适配层 | 不作为业务微服务 |

前端、Android 壳、`OperationActivityCenter` 和会话 `plan` 都是客户端或展示能力，不应拆成业务微服务。

## 3. 推荐第一版拓扑

第一阶段建议只部署以下服务：

```text
API Gateway
  |
  +-- Identity Service
  +-- File/Storage Service ---- S3/MinIO
  +-- Agent/Chat Service ------ Chat DB + Redis
  +-- Content Service ---------- Vision Provider
  |                              |
  |                          Index DB/pgvector
  +-- File Service ------------- owner object/content storage
  +-- Device Sync（可选，初期可留在 File Service）
```

`Content Service` 初期同时负责视觉描述、Tika 抽取、chunk 和 embedding，避免“描述生成 → 文档写入 → 向量化”被拆成分布式事务。只有当视觉 Provider 延迟、搜索查询量或 GPU/Provider 成本出现独立扩展需求时，才进一步拆出 `Vision Inference` 和 `Search/Index`。

## 4. 服务契约

### Identity

- 网关验证 Cookie/Bearer，向下游传递已验证的 owner、credential kind 和 scopes。
- 下游不得信任请求体中的 owner ID。
- token、session 和 pairing 状态只由 Identity 所有，其他服务通过内部鉴权调用。

### File/Storage

- 公共 API 使用 owner-relative POSIX path、revision 和 checksum。
- 文件内容通过受控流或对象存储引用传递，服务之间不能共享本地路径。
- 文件 mutation 的结果必须带 `file_id`、`revision`、`path` 和 `content_md5`。
- 文件二进制迁出本地文件根之前，必须保留原子发布、版本快照、回收站和 dedupe 语义。

### Content/Index

- 输入：`owner_id`、`file_id`、`revision`、媒体类型和对象引用。
- 视觉输出：一段综合描述、模型名、prompt/描述版本和 source revision。
- 索引写入必须校验 revision；过期结果只能返回冲突，不能覆盖当前文件。
- 向量记录必须绑定 embedding fingerprint 和 chunk version。

### Agent/Chat

- 对外保持现有 `/api/v1/chat`、SSE、`session_id` 和 `backend_api` 契约。
- Agent 通过内部 client 调用 File、Content/Index、Identity API；模型不获得内部 URL、token 或 header。
- `ChatRunRegistry`、replay、confirmation 和 SSE relay 在多副本部署前必须迁到 Redis/持久化状态，不能继续只放 JVM 内存。

## 5. 数据与一致性

最终目标是每个服务拥有自己的 schema/database，禁止多个服务直接读写同一业务表。跨服务关系使用稳定 ID：`owner_id`、`file_id`、`revision`、`document_id`。

第一阶段允许同一 PostgreSQL 实例承载不同 schema，但每个服务只能通过自己的 repository 访问自己的 schema。之后再按服务迁移数据库，不允许直接复制表后长期双写。

文件变更可发布内部集成事件：

```text
file.revision.changed
file.deleted
file.restored
content.description.generated
index.updated
```

这些是服务间集成事件，不是用户任务队列；不得恢复历史 tasks、schedules 或 outbox 运行链路。

## 6. 迁移顺序

1. **端口化**：当前单体内先使用 `VisionDescriptionPort`、`FileContentPort`、File/Index application port 和 DTO，禁止跨模块直接依赖实现类；视觉和文本抽取只请求受限原始字节，不接触本地绝对路径。
2. **Gateway/Identity 边界**：统一 owner、scope、request-id 和内部服务认证；先不拆数据表。
3. **对象存储**：将 owner 文件根抽象为对象存储，保留 revision、MD5、版本和回收站不变量。
4. **Content Service**：先外置视觉/内容理解，保留同步调用和原有业务结果；当前已实现 `/internal/v1/vision/describe`、`/internal/v1/ready`、内部令牌、原始图片批量预算和远程端口适配器。URL 未配置时保持本地实现，配置后失败仍在当前响应返回逐项错误。
5. **Search/Index Service**：迁移 documents/chunks/pgvector，按 revision/fingerprint 做双读校验后切换。
6. **Agent/Chat Service**：迁移 SSE relay、ChatRunRegistry、replay 和 confirmation，再将 `backend_api` dispatcher 换成内部服务 client。
7. **Device Sync**：File API 稳定后再独立部署，Android 只依赖公开上传、dedupe 和同步状态契约。

## 7. 不应直接做的拆分

- 不按每个 Controller 建一个服务。
- 不让服务共享 owner 本地目录或互相读取数据库表。
- 不把 `plan`、活动中心、确认卡做成后台任务服务。
- 不在 Agent、File、Index 内各自实现一套 owner 鉴权。
- 不在没有 Redis/事件回放能力前横向扩展 Chat Service。

## 8. Content Service 当前实现

- 源码位于 `services/content-service`，不访问主 API 数据库或 owner 本地路径，也不持久化图片和描述。
- 请求最多 16 张，主 API 默认按最多 4 张/20 MiB 批次发送；图片保持原始 bytes，Provider 请求使用 `image_url.detail=high`。
- owner 当前视觉 provider 快照只在一次内网请求中传递；服务环境变量仅作为独立运行时默认配置。响应不会包含 API key，Provider URL 禁止 userInfo/query/fragment，JDK HTTP 客户端禁止跟随重定向。
- `deploy/agent-drive-content.service` 和 `scripts/deploy.ps1 -Target content|all` 负责独立发布、健康检查和上一版本回滚；服务默认监听 `127.0.0.1:8010`。

## 9. File Service 当前实现

- 源码位于 `services/file-service`，当前提供 `POST /internal/v1/files/content`、`GET /internal/v1/files/manifest` 和 `/internal/v1/ready`；服务拥有独立的 owner UUID 分区，不读取主 API 数据库或主 API 本地路径。
- 每次读取都重新校验 owner、相对路径、符号链接、内部目录、文件大小和 MD5；返回只包含当前请求的 Base64 原始 bytes，不缓存内容。
- manifest 只返回可见文件的相对路径、大小和 MD5，用于迁移前比对新旧存储；条目有界，内部目录和符号链接不会进入清单。
- `RemoteFileContentPort` 和 `RemoteFileMirror` 按 `AGENT_DRIVE_FILE_SERVICE_URL/TOKEN` 启用；主 API 启动时验证 `/internal/v1/ready`。启用后视觉、Tika/索引和其他原始字节消费者从独立 File Service 读取，上传、mkdir、move/copy、trash/restore 先在 API 事务内发布并通过内部镜像契约同步，失败时按同一 mutation scope 回滚。
- 2026-08-25 已完成首批 owner 文件镜像：`796` 个可见普通文件、`776425013` 字节，源/目标 manifest 的数量和总字节一致；直接 mirror/mkdir/move/copy/tree-delete/trash/restore smoke 与 API mutation smoke 全部通过。File Service 请求体上限已覆盖应用 `300 MiB` 上传上限，独立 0600 token 和 systemd rollback 已启用。
- `deploy/agent-drive-file.service` 和 `scripts/deploy.ps1 -Target file|all` 负责独立发布、健康检查和回滚；服务默认监听 `127.0.0.1:8020`。

## 10. Identity Service 当前实现

- 源码位于 `services/identity-service`，自有 `identity_owner`、`identity_credentials` schema，提供 setup/login/logout、`/internal/v1/introspect` 和 token 保护的 readiness。
- 密码仍使用 PBKDF2-HMAC-SHA256 600,000 次迭代；session token 只返回当前响应，数据库保存 SHA-256 摘要。服务不读取主 API 的认证表。
- 主 API 已启用 `RemoteCredentialAuthenticator` 和 `AGENT_DRIVE_IDENTITY_SERVICE_URL/TOKEN`；迁移后的 owner/session/device credential 已通过注册、Bearer introspection、撤销后 200→401 回归。
- `scripts/deploy.ps1 -Target identity` 要求服务器预置独立数据库和 0600 `identity.env`，不会被 `-Target all` 隐式安装，避免未迁移数据时误切认证。

## 11. Index Service 当前实现

- 源码位于 `services/index-service`，自有 `index_documents/index_chunks` schema，提供文档/视觉描述替换、owner manifest 和迁移期 lexical search。
- 文档写入以 `owner_id/file_id/source_revision/document_type` 为边界，替换正文和 chunks 在同一事务内完成；manifest 不返回正文或向量。
- 服务保存 `embedding_fingerprint`/`embedding`，以 owner/file/revision/type/chunk 约束远程向量写入，并在服务自身数据库执行 cosine 语义检索；路径 metadata 通过迁移契约补齐。
- 主 API 已配置 `AGENT_DRIVE_INDEX_SERVICE_URL/TOKEN`，文本/视觉正文和向量都双写；`AGENT_DRIVE_INDEX_READ_MODE` 支持 `local`、`dual`、`remote`。生产已完成双读稳定窗口并切到 `remote`，远程错误按显式 fallback 回本地并记录 warning。
- `scripts/deploy.ps1 -Target index` 要求预置独立数据库和 0600 `index.env`，不会被 `-Target all` 隐式安装。
- Java backup timer 会在 `index.env` 提供数据库用户/名称时把 Index Service dump 放进同一份归档；Index Service 不允许在没有备份覆盖的情况下切流。
- 2026-08-24 已完成首批迁移：主库 `23 documents / 22 chunks` 与 Index DB 完全一致，manifest 和 lexical migration check 已验证；随后历史 5 张 v1/v2 图片已重新生成 `vision-description-v3` 并完成向量同步。当前主库与 Index DB 均为 `6 vision-description-v3 / 0 old vision`。

## 12. Agent/Chat 当前实现

- `services/agent-service` 拥有独立 `agent_drive_agent` 数据库和 systemd unit，保存 session、transcript、confirmation、replay、nonce、run state 和 SSE event；API 通过 `AGENT_DRIVE_AGENT_SERVICE_URL/TOKEN` 使用远程 owner-scoped 适配器。
- `ChatRunRegistry` 的 runtime relay 仍在 API JVM 执行，但持久状态和事件不再依赖该 JVM；无本地 relay 时通过 Agent Service 250ms 有界轮询回放，生产跨进程 smoke 已验证 `done` 事件可从 8050 写入并由 8000 重连返回。
- Agent Service 不是任务队列：不创建后台任务、不提供任务列表/取消/重试语义；Redis/NATS 只作为未来高并发 relay 优化。

## 13. 当前阶段验收

当前阶段的验收是：Content/File/Identity/Index/Agent Service 均可独立构建、测试、部署、健康检查和回滚；生产已启用内部服务 token，文件 manifest、认证 credential、索引文档/向量和 Agent session 数据均完成独立库迁移与一致性校验。API 仍保留本地 fallback 开关，远程切流可在不改客户端契约的情况下回退。
