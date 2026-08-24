# 微服务演进架构

> 当前基线仍是 Java 模块化单体。本文件定义可执行的微服务演进边界，不表示服务已经拆分或已经部署。

## 1. 当前基线

当前生产拓扑只有一个 Java API 进程：

```text
Browser / Android
        |
      nginx
        |
Java API :8000
   |             |
PostgreSQL    owner 文件根
   |
 pgvector
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
  +-- Content Service ---------- Vision Provider + Jina
                                  |
                              Index DB/pgvector
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

1. **端口化**：当前单体内先使用 `VisionDescriptionPort`、File/Index application port 和 DTO，禁止跨模块直接依赖实现类。
2. **Gateway/Identity 边界**：统一 owner、scope、request-id 和内部服务认证；先不拆数据表。
3. **对象存储**：将 owner 文件根抽象为对象存储，保留 revision、MD5、版本和回收站不变量。
4. **Content Service**：先外置视觉/内容理解，保留同步调用和原有业务结果；失败仍返回当前响应。
5. **Search/Index Service**：迁移 documents/chunks/pgvector，按 revision/fingerprint 做双读校验后切换。
6. **Agent/Chat Service**：迁移 SSE relay、ChatRunRegistry、replay 和 confirmation，再将 `backend_api` dispatcher 换成内部服务 client。
7. **Device Sync**：File API 稳定后再独立部署，Android 只依赖公开上传、dedupe 和同步状态契约。

## 7. 不应直接做的拆分

- 不按每个 Controller 建一个服务。
- 不让服务共享 owner 本地目录或互相读取数据库表。
- 不把 `plan`、活动中心、确认卡做成后台任务服务。
- 不在 Agent、File、Index 内各自实现一套 owner 鉴权。
- 不在没有 Redis/事件回放能力前横向扩展 Chat Service。

## 8. 当前阶段验收

当前阶段的验收是：端口抽象不改变现有 HTTP/Agent 行为；所有测试和构建通过；生产仍只有一个 Java API artifact。只有完成对象存储、服务鉴权、独立数据所有权和回滚策略后，才进入真正的网络服务拆分。
