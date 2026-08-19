# Agent Drive Java Backend

`backend/` 是 Agent Drive 的唯一后端目录。API 和任务 Worker 使用同一个 Java 21 Maven 工程；生产 API 监听 `127.0.0.1:8000`，Worker 不监听 HTTP。模块边界、数据所有权和安全约束见 [`docs/architecture.md`](../docs/architecture.md) 与 [`docs/security.md`](../docs/security.md)。

## 技术栈

- Java 21、Spring Boot 3.5.x、WebFlux、Spring Modulith 1.4.x
- LangChain4j 1.19.x、MyBatis-Plus、Flyway V1-V10
- PostgreSQL 16 + pgvector
- Apache Tika + Tesseract/Tess4J
- Maven

## 本地运行

本地需要可用的 PostgreSQL/pgvector 和对应环境变量。API、Worker 通过 `app.mode` 区分：

```bash
# API
mvn spring-boot:run \
  -Dspring-boot.run.profiles=db,java-chat \
  -Dspring-boot.run.arguments="--app.mode=api"

# Worker
mvn spring-boot:run \
  -Dspring-boot.run.profiles=db,java-chat \
  -Dspring-boot.run.arguments="--app.mode=worker"
```

生产使用 `deploy/agent-drive-java.service` 和 `deploy/agent-drive-java-worker.service`。两个进程共享 PostgreSQL、owner 文件根、`/etc/agent-drive-java/java.env` 和 HTTP(S) 代理配置；生产 API 禁止内嵌 Worker。

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
- tasks/schedules、SSE、取消/重试、index/embed/vision/rebuild/cleanup enqueue；
- Chat SSE、reasoning、工具轨迹、确认、replay 和自动化报告。

向量和视觉接口只负责校验参数并创建 owner-scoped 任务；Tika/OCR、chunk、Jina embedding 和图片描述均由 Worker 执行。Chat SSE 每个 `data` 都是 JSON object，模型只使用统一的 `backend_api`/`frontend_api` catalog。

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
- Worker unit：`agent-drive-java-worker.service`
- Backup timer：`agent-drive-java-backup.timer` → `/opt/agent-drive-java/backups/`
- 数据根：`/opt/agent-drive-java/data`
- Java 环境和随机密钥：`/etc/agent-drive-java/java.env`（0600）
- PostgreSQL：独立 `agent-drive-java-postgres`，宿主只绑定 `127.0.0.1:15433`

推荐从仓库根目录执行 `pwsh -File scripts/deploy.ps1 -Target all`。它会运行 Maven 门禁、安装 artifact、API/Worker/backup units、执行 systemd 校验、按 API → Worker 重启并检查 health。脚本不自动 commit/push。
