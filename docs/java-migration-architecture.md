# Java 后端架构与迁移记录

> 状态：Java API/Worker 已接管生产；索引重建、回滚演练、稳定性观察和旧 Python 清理均已完成（2026-08-19）。本文保留迁移决策和切换证据，不是待办路线图。

## 1. 当前基线

Agent Drive 的唯一后端是 Java 21 Maven 工程：

| 层 | 当前实现 |
|----|----------|
| Web | Spring Boot 3.5.x + WebFlux |
| 模块边界 | Spring Modulith 1.4.x，模块化单体，不拆网络微服务 |
| Agent | LangChain4j 1.19.x，原生 structured tool calling |
| 持久化 | PostgreSQL 16 + pgvector、MyBatis-Plus/Mapper XML、Flyway V1-V14 |
| 文件 | Java NIO owner-scoped 本地文件系统 |
| 抽取 | Apache Tika（图片不走 OCR，图片由视觉模型描述） |
| 构建 | Maven；API 与 Worker 共用 artifact |

生产结构化状态只进入 PostgreSQL。实际文件和用户可见 Agent 文档仍在 owner-scoped 文件根；旧 SQLite/JSON 和 Python 资料只作归档恢复输入。

## 2. 运行拓扑与 profile

```text
nginx :13311
      │
      ▼
Java API 127.0.0.1:8000 ─── PostgreSQL/pgvector
      │
      └── frontend/out

Java Worker ─────────────── PostgreSQL task leases/outbox
      └── 文件根、Tika 文档抽取、embedding/vision provider
```

API 和 Worker 是同一个代码库的两种启动模式：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=db,java-chat -Dspring-boot.run.arguments="--app.mode=api"
# 另一个终端启动任务 Worker：
mvn spring-boot:run -Dspring-boot.run.profiles=db,java-chat -Dspring-boot.run.arguments="--app.mode=worker"
```

生产 API 使用 `agent-drive-java.service` 和 `--app.mode=api`；独立 Worker 使用 `agent-drive-java-worker.service` 和 `--app.mode=worker`。默认 profile 不注册 ChatRuntime，`java-chat` 才启用 Java auth、provider、chat、files、devices、sessions、tasks、automation 和持久 runtime。

Megumin 的 PostgreSQL 使用独立 `agent-drive-java-postgres`（`pgvector/pgvector:pg16`），宿主只绑定 `127.0.0.1:15433`。数据库凭据和 AES-GCM keys 位于 0600 的 `/etc/agent-drive-java/java.env`，不进入仓库。

## 3. 模块边界

```text
com.agentdrive
├── api             HTTP/SSE、上传下载、静态资源和 fallback
├── agent           runtime、tool catalog、确认、replay、reasoning
├── auth            用户、session、设备令牌、配对码、限速
├── config          LLM、embedding、vision 配置和 probe
├── files/storage   文件用例、revision、trash、路径安全、原子发布
├── devices         设备 metadata、撤销和同步状态
├── tasks           状态机、租约、事件、schedule、outbox、Worker
├── index           Tika 文档、全文、chunk、text/vision embedding
└── infrastructure  PostgreSQL、MyBatis、Flyway、HTTP、日志和启动适配器
```

API 不直接写 SQL、操作物理路径或调用模型 SDK；Agent 不直接访问文件系统。跨模块写操作通过 application service、事务和 owner-scoped outbox 连接。

## 4. 数据与安全边界

PostgreSQL 的结构化状态包括认证、会话消息及来源化 context 注入、设备、Skill、文件 metadata/revision/trash/dedupe、任务和事件、schedule、Worker heartbeat、outbox、文档/chunk、embedding metadata、Agent preference 以及加密 provider 配置。

文件 mutation 保持以下不变量：公共路径为 owner 内相对 POSIX 路径；组件级拒绝 traversal 和 symlink；staging 使用 0600；发布使用 fsync、原子 move/link 和 `.storage.lock`；上传由服务端复算 MD5；`noclobber` 不使用先检查再写入的 TOCTOU 流程。上传、移动、复制、回收站删除/恢复在可见发布前隐藏旧目标，并把 storage lock 持有到 Spring 事务完成；回滚恢复旧磁盘状态，提交后清理 backup，提交后的 artifact 清理失败不反转成功结果。

认证使用 PBKDF2 密码 hash、HttpOnly Cookie session、Bearer session/device token 和一次性 pairing code。模型可见工具是统一的 `backend_api`/`frontend_api` envelope 与只读 `read_skill`；Skill 指令不扩展工具权限，模型不能提供任意 URL、请求头、Cookie、Bearer、JavaScript 或 Java 类名。具体安全边界见 [`security.md`](security.md)。

## 5. API、Agent 和任务契约

- Chat SSE 的每个 `data` 都是 JSON object；事件包括 text、reasoning、tool_start、tool_trace、frontend_action、done、error。
- `thinking_level` 只允许 `auto/low/medium/high`，不发送 temperature；OpenAI 兼容流式模型必须开启 `returnThinking(true)`，reasoning 不进入下一轮 history。
- `backend_api` 必须先 discover，再调用精确的 `METHOD /api/v1/path` 或 `INTERNAL name`。discover 以 offset/limit 稳定分页，返回 total_matches、has_more 和 next_offset，单页最多 20 项；非 red 调用按 session/tool/arguments 持久 replay，ask/auto 模式下 red 操作使用签名确认和一次性 nonce，full 模式按用户授权直接执行。
- `/api/v1/tasks/embed-index`、`vision-index`、`clear-vectors` 只校验参数并入队任务；抽取、embedding、vision、失效索引清理和向量清空都由 Worker 异步完成。`cleanup-index` 只清理失效记录，不等价于清空向量。
- 文件语义搜索使用 Jina `retrieval.query` 和当前 embedding fingerprint 的 pgvector chunk，结果按文件去重并返回最佳片段。
- 文件内容变更先失效旧全文/向量，再经 outbox 入队 `index.file`；坏 outbox 事件进入持久死信，瞬时入队错误保留重试。图片描述写入前校验 source revision；视觉全失败不触发全盘 embedding，显式向量/视觉 provider 失败进入任务 fail/retry。强制向量重算逐批覆盖，不预先删除旧向量。单文件抽取失败标记 skipped，不阻断 rebuild。

任务使用 PostgreSQL 状态机、`FOR UPDATE SKIP LOCKED`、lease/heartbeat、owner-scoped `(user_id, dedupe_key)` partial unique index 和尾部 SSE event cursor。running 任务收到取消后，进度/续租/succeed SQL 均拒绝并由 handler 停止后落为 cancelled。schedule 在写入和派发前校验，5/6 字段 cron 按计划时区计算真实下一次命中，派发把 priority/max_attempts 原样传给任务；任务类型和 lane 在写入时裁剪，空白 lane 统一为 `default`，遗留非法计划自动禁用。Worker 的 schedule/outbox/task 阶段互相隔离；每 2 秒更新 `task_workers`，API 以最近 10 秒心跳判断在线；生产 API 不执行内嵌 Worker。

## 6. 已完成的迁移与切换

迁移顺序曾按“契约 → Java 骨架 → auth/chat → files → tasks/index → 切换”推进，当前所有阶段均已完成，后续不应再把这些阶段写成待办：

- Java API/Worker 已替换生产服务，健康检查、鉴权、静态资源和 Worker canary 通过。
- legacy 数据导入完成，文件 MD5 核验通过，owner-scoped PostgreSQL backfill 完成。
- `index.rebuild` 已完成，普通文档 Tika、图片视觉描述、Jina/pgvector 和任务状态链路已在 Worker 中运行；图片不使用 OCR。
- 实际回滚演练通过；现行 `agent-drive-java-backup.timer` 将 Java PostgreSQL dump、owner 文件根和 manifest 归档到 `/opt/agent-drive-java/backups/`，旧资料仍保留在同一归档目录。
- 旧 Python source/unit 已删除；`legacy-python-data/` 只保留本地一次性 fixture，服务器恢复资料只用于人工恢复。

切换快照（用于追溯，不作为运行时配置）：104 directories、792 files、774,988,316 bytes、2 devices、777 dedupe entries、10 legacy jobs、2 schedules；最终 Java 数据库包含 1 user、2 devices、896 file metadata rows、777 dedupe rows。完整备份目录和校验文件以服务器 cutover 记录为准。

## 7. 迁移工具的剩余用途

`migrate` profile 默认 dry-run。只有在明确处理 legacy snapshot、使用空 Java 数据库和空目标数据根时，才允许显式导入：

```bash
java -jar agent-drive-backend.jar \
  --spring.profiles.active=db,migrate --app.mode=migrate \
  --migration.apply=false \
  --migration.legacy-data-dir=../legacy-python-data/data \
  --migration.legacy-system-dir=../legacy-python-data/system \
  --app.data-dir=/opt/agent-drive-java/data
```

生产运行不调用迁移 profile，不读取旧 SQLite/JSON，也不启动 Python。仓库不再提供旧 SQLite 定时备份入口；需要恢复时先从服务器归档取得资料，并通过 SQLite backup API 生成一致快照，再由人工执行受控导入或恢复。

## 8. 验收门禁和当前边界

现行门禁：Maven test/package、前端 lint/Vitest/build、Android JVM 单测、API/SSE/文件安全契约测试、`systemd-analyze verify` 和生产 health。统一入口是 [`scripts/deploy.ps1`](../scripts/deploy.ps1)。

当前后端仍是单用户产品模型、owner-scoped 本地文件存储；S3、iOS、音视频转写和多用户产品隔离不属于已实现能力。
