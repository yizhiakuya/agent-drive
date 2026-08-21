# Agent Drive

Agent-first 的私人网盘：文件管理、检索、配置和自动化都可以通过 Agent 完成，同时保留直接操作文件页和设置页的能力。

> 当前事实（2026-08-19）：生产使用 Java 21 + Spring Boot/WebFlux API、独立 Java Worker、PostgreSQL/pgvector、Next.js 16 静态前端和 Capacitor 7 Android 壳。Python 后端已经从运行时和仓库中移除；旧数据只保存在本地 fixture 和生产 cutover backup 中用于人工恢复。

## 能力

- Agent 通过统一的 `backend_api` / `frontend_api` discover/call envelope 操作后端和当前浏览器能力；每轮自动注入 owner Agent 文档和启用 Skill 目录，再通过 `read_skill` 按需加载匹配 Skill 正文。
- 支持 OpenAI 兼容、OpenAI Responses 和 Anthropic provider；支持独立的 embedding 与 vision 配置。
- Chat 使用 JSON object SSE，支持 reasoning、可展开上下文注入、工具轨迹、确认、确定性 replay 和会话摘要；切换会话不会中止仍在生成或执行工具的会话。
- 文件页支持名称/路径搜索、Jina + pgvector 语义搜索、文本预览/全文查看、回收站和 revision 状态。
- 上传服务端复算 MD5；文件写入、复制、覆盖、去重和回收站使用原子发布与路径安全边界。
- PostgreSQL 保存认证、会话、设备、Skill、文件 metadata、任务、schedule、outbox、全文和向量状态；实际文件仍在 owner-scoped 本地文件系统。
- 独立 Worker 异步执行 Tika/Tesseract 抽取、全文/embedding/vision 索引、计划任务和维护任务。
- Web 使用密码登录和 HttpOnly Cookie；Android 使用扫码配对得到的 Bearer 设备令牌。
- Android App 提供扫码连接、后台相册同步、服务端去重、断点续传、进度通知和设备登记。

## 快速开始

### 后端

需要 Java 21 和可用的 PostgreSQL/pgvector 配置。API 与 Worker 使用同一个 Maven 工程，通过启动参数选择模式：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=db,java-chat -Dspring-boot.run.arguments="--app.mode=api"
# 另一个终端启动任务 Worker：
mvn spring-boot:run -Dspring-boot.run.profiles=db,java-chat -Dspring-boot.run.arguments="--app.mode=worker"
```

API 默认只监听 `127.0.0.1:8000`。生产由 nginx `13311` 端口代理，Worker 不监听 HTTP 端口。

### 前端

```bash
cd frontend
npm install
npm run dev -- -p 3333
npm run build
```

开发前端默认使用同源 API；需要直连本地 API 时设置 `NEXT_PUBLIC_API_BASE=http://localhost:8000/api/v1`。生产构建输出到 `frontend/out`，由 Java API 托管。

### 验证

```bash
cd backend && mvn -q test && mvn -q -DskipTests package
cd ../frontend && npm run lint && npm test && npm run build
cd android && gradlew.bat testDebugUnitTest
```

## 部署

Windows 开发机使用顶层脚本发布当前工作区产物：

```powershell
pwsh -File scripts/deploy.ps1 -Target frontend
pwsh -File scripts/deploy.ps1 -Target all
```

脚本会执行门禁、构建、递增 Service Worker cache、全量打包静态目录，并在服务器原子替换前端；`all` 还会安装 Java artifact 和 systemd units，按 API → Worker 顺序重启并检查 health。脚本不会自动 `commit` 或 `push`，发布前应先整理并提交工作区。日常前端迭代不构建 APK，部署时会保留服务器已有的 `out/app/agent-drive.apk`。

生产入口：[https://home.rainaki.top:13311/](https://home.rainaki.top:13311/)

## 目录

```text
agent-drive/
├── backend/                 Java API、Agent、文件、索引、任务 Worker 和测试
├── frontend/                Next.js 静态前端、API client 和 Capacitor Android 工程
├── deploy/                  systemd、nginx、PostgreSQL 和代理模板
├── scripts/                 跨前后端部署、备份和 QA 编排
├── docs/                    现行架构/安全/客户端说明与历史审查快照
├── AGENTS.md                编码、发布和安全维护约束
└── legacy-python-data/      仅供一次性迁移/人工恢复读取，不进入运行时
```

## 运维入口

```bash
journalctl -u agent-drive-java.service -n 100 --no-pager
journalctl -u agent-drive-java-worker.service -n 100 --no-pager
systemctl list-timers agent-drive-java-backup.timer --no-pager
curl http://127.0.0.1:8000/api/v1/health
```

产品定位、用户流程和当前边界见 [`docs/product.md`](docs/product.md)；详细工程事实按主题查看 [`docs/README.md`](docs/README.md)。当前明确未实现的能力包括 S3 存储、多用户产品隔离、iOS 客户端和音视频转写；它们不属于当前生产契约。
