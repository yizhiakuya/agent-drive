# Agent Drive Java Backend

`backend/` 是 Agent Drive 的唯一后端目录。当前只运行 Java 21 API；任务/Worker 运行链路已移除，索引等业务由 API 直接执行。生产 API 监听 `127.0.0.1:8000`。模块边界、数据所有权和安全约束见 [`docs/architecture.md`](../docs/architecture.md) 与 [`docs/security.md`](../docs/security.md)。

## 技术栈

- Java 21、Spring Boot 3.5.x、WebFlux、Spring Modulith 1.4.x
- LangChain4j 1.19.x、MyBatis-Plus、Flyway V1-V19
- PostgreSQL 16 + pgvector
- Apache Tika、视觉模型和 Jina embedding（图片不走 OCR）
- Maven

## 本地运行

本地需要可用的 PostgreSQL/pgvector 和对应环境变量。启动 API：

```bash
# API
mvn spring-boot:run \
  -Dspring-boot.run.profiles=db,java-chat \
  -Dspring-boot.run.arguments="--app.mode=api"

```

生产使用 `deploy/agent-drive-java.service`，备份由独立 timer 执行。

## 测试与构建

```bash
mvn -q test
mvn -q -DskipTests package
```

后端诊断脚本使用 Java source-file 模式：

```bash
java scripts/HealthCheck.java http://127.0.0.1:8000
java scripts/SessionView.java <SESSION_ID>
java scripts/SessionView.java <SESSION_ID> --full
```

`HealthCheck` 只检查 health；`SessionView` 只读 PostgreSQL，并对常见 token 做脱敏。

## API 纵向能力

`java-auth`/`java-chat` 组合 profile 提供：

- Cookie/Bearer 认证、设备登记/撤销、会话；
- provider、embedding、vision 配置和模型 probe；
- 文件列表、上传、预览、全文、回收站、dedupe 和 semantic search；
- 直接索引/向量/视觉业务 API：文本先由 `/index/file` 抽取，再由 `/index/vectors` 写入向量；失败返回结构化 `ok/status/code/detail` 和逐项结果；
- Chat SSE、reasoning、工具轨迹、确认、replay 和自动化报告。

向量和视觉接口直接执行 owner-scoped 业务并返回逐项结果；普通文档 Tika、chunk、Jina 文本向量，以及图片视觉描述 + Jina 视觉向量均由 API 执行，图片不走 OCR。Provider 不可用时返回明确的 `ok/status/code/detail`，不会伪报排队或成功。Chat SSE 每个 `data` 都是 JSON object，模型只使用统一的 `backend_api`/`frontend_api` catalog，Agent 不暴露任务创建接口。

## 迁移资料

`migrate` profile 默认 dry-run，只适用于人工处理 legacy snapshot。正式导入必须使用空的 Java PostgreSQL、空的目标数据根和显式确认；导入会复制 owner 文件、复算 MD5/SHA-256 并导入认证、设备、provider、dedupe、任务和 schedule。

```bash
java -jar agent-drive-backend.jar \
  --spring.profiles.active=db,migrate --app.mode=migrate \
  --migration.apply=false \
  --migration.legacy-data-dir=../legacy-python-data/data \
  --migration.legacy-system-dir=../legacy-python-data/system \
  --app.data-dir=/opt/agent-drive-java/data
```

生产运行不读取旧 SQLite/JSON，不启动 Python；旧资料只保存在 `legacy-python-data/` 和 `/opt/agent-drive-java/backups/`，需要恢复时使用受控备份流程。

## 生产发布

- artifact：`/opt/agent-drive-java/agent-drive-backend.jar`
- API unit：`agent-drive-java.service`
- Backup timer：`agent-drive-java-backup.timer` → `/opt/agent-drive-java/backups/`
- 数据根：`/opt/agent-drive-java/data`
- Java 环境和随机密钥：`/etc/agent-drive-java/java.env`（0600）
- PostgreSQL：独立 `agent-drive-java-postgres`，宿主只绑定 `127.0.0.1:15433`

推荐从仓库根目录执行 `pwsh -File scripts/deploy.ps1 -Target all`。它会运行 Maven 门禁、安装 artifact、API/backup units、执行 systemd 校验并检查 health/readiness。脚本不自动 commit/push。
