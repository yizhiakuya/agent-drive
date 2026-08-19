# 安全边界

> 现行安全基线（2026-08-19）。服务面向个人单用户使用，但内部状态、文件路径、设备和任务都按 owner 隔离。认证、文件写入、Agent 工具和 Android 令牌是四条独立边界。

## 1. 暴露面

```text
公网 HTTPS home.rainaki.top:13311
        │ nginx
        ▼
127.0.0.1:8000 Java API
        ├── PostgreSQL/pgvector（仅受限地址）
        └── owner-scoped 本地文件系统
```

- nginx 是唯一公网入口，API 只绑定 `127.0.0.1:8000`；Worker 不监听 HTTP。
- `/api/v1/health` 用于探活；认证初始化端点按认证规则公开；其他业务 API 默认需要当前 owner。
- 静态资源和 `.well-known/assetlinks.json` 可公开读取，但 SPA fallback 不能越过 `frontend/out` 目录边界。
- nginx 对公网上传限制 200 MB，Java API 还有 `max_upload_mb=300` 的直连兜底；API/Worker 只读取 `/etc/agent-drive/proxy.env` 中的 HTTP(S) 代理，并清除 `ALL_PROXY/all_proxy`。

## 2. 认证模型

```text
Web/PWA 密码 ──▶ HttpOnly session Cookie
       │
       └── 生成一次性 pairing code ──▶ Android 扫码兑换 Bearer device token
```

- 密码使用 PBKDF2-SHA256（60 万次）和随机盐，只存 Java PostgreSQL 的 hash。
- Web session 和设备令牌服务端只存 SHA-256 credential hash；session 默认 30 天，登出会持久撤销当前凭据直到到期。
- 配对码一次性、5 分钟有效，最多保留 3 个未使用码；setup/login 每个客户端每分钟最多 5 次，pair-exchange 每分钟最多 10 次。
- 重扫会吊销旧设备令牌；设置页移除设备也会写入 `revoked_at`。
- 浏览器使用 HttpOnly、SameSite=Lax、生产 Secure Cookie。Android 后台请求使用 Bearer；`?token=` 只兼容媒体 raw/download GET。
- `/api/v1/auth/status|setup|login|logout|pair-exchange` 是认证流程端点，其他路由不能借初始化状态绕过 owner 校验。

## 3. 文件与路径安全

- 公共路径必须是 owner 内相对 POSIX 路径；拒绝 `..`、组件级 symlink、`.index`、`.trash`、`.storage.lock` 和 upload/copy staging。
- 下载、预览、上传、列表和 mutation 共用路径边界；内部流程必须显式使用 `allow_internal`。
- 上传请求体流式写入 0600 临时文件，服务端复算 MD5 后才发布；`noclobber` 使用原子 no-replace 语义，不走“先 exists 再写”。
- 文本写入、覆盖、目录复制和回收站使用 staging、fsync 和 atomic replace/link；目录复制的 recovery marker 用于处理进程崩溃。发布点之后的清理失败不能伪报已发布文件失败。
- 文件 metadata、revision、dedupe、全文和向量都按 owner 绑定；文件内容变化先失效旧索引，再由 Worker 异步重建。

## 4. Agent 和外部 provider

- 模型只能使用稳定的 `backend_api`、`frontend_api` 和受限 plan/skills 工具；调用后端必须先 discover，再使用登记的 `METHOD /api/v1/path` 或 `INTERNAL name`。
- 模型不能提供任意 URL、Cookie、Bearer、Authorization、请求头、Python 入口、Java 类名、JavaScript 或 `eval`。
- 当前 Request 的 Cookie/Bearer 只在进程内传给 backend dispatcher；Worker 通过 PostgreSQL 任务租约和 owner-scoped payload 执行，不接收模型提供的凭据。
- GET 和只读 probe 自动执行；写操作按 operation 风险处理，red 操作需要签名确认、nonce TTL 和一次性消费。非 red 操作按 session/tool/arguments 做确定性 replay。
- provider API key 只在 provider/base URL 相同且表单留空时复用，落库使用 AES-GCM；响应、日志、会话、工具轨迹和 `last_trace` 只保留掩码/脱敏值。
- API key、Cookie、Bearer、设备 token、query credential、完整消息和文件内容不进入普通日志。聊天日志记录 request ID、provider/model、工具 operation、状态和耗时；异常 message/cause 与 SSE error 先脱敏。

## 5. Android 令牌与权限

- 服务器地址、设备令牌和同步设置写入独立 `agent_drive_secure` EncryptedSharedPreferences，使用 Android Keystore 的 AES256-GCM/SIV；`allowBackup=false` 防止云备份克隆令牌。
- 升级兼容旧业务 prefs，但新密文提交成功或逐键确认相等前不会清理旧值；冲突、初始化、迁移和 commit 失败都失败关闭，不降级到明文。
- 相册同步只需要图片读取权限；通知权限拒绝不会被当作同步失败。ContentObserver、权限回调和 Activity 生命周期必须清理，避免重复同步和泄漏。
- `lastSyncAt` 只推进到整秒全部完成；失败或查询截断通过 `pendingSecond + pendingMaxId` 续传。服务端 dedupe 预检不是可信写入依据，真正上传仍复算 MD5。

## 6. 日志、备份与恢复

- API/Worker 使用统一 SLF4J/Logback，生产日志进入 systemd journal；聊天链路按 request ID 检索，敏感字段不写普通日志。
- `agent-drive-java-backup.timer` 每日调用 `scripts/backup-java.sh`，将 PostgreSQL dump、owner 文件根和 manifest 归档到 `/opt/agent-drive-java/backups/`，保留最近 7 份并生成 SHA-256 校验文件；环境密钥位于 0600 的 `/etc/agent-drive-java/java.env`，均不进 git。仓库不再提供旧 Python/SQLite 定时备份脚本。
- 只有处理 legacy 恢复资料时才需要 SQLite snapshot；必须通过 SQLite backup API 生成一致快照，禁止直接打包活动 WAL 三件套。
- 认证表、设备撤销状态和 PostgreSQL schema 异常时服务失败关闭，不得把错误当作“未初始化”重新开放首次设密。
- 生产发布必须执行 `systemd-analyze verify`、API health 和 Worker active 检查；推荐使用 `scripts/deploy.ps1`，它保留前一版静态目录作为回滚副本。

## 7. 当前明确边界

- 当前是个人单用户产品，不提供多租户管理、S3 权限模型或端到端加密文件。
- 修改密码暂未提供网页 UI，需要受控管理流程更新 owner password hash；双因素登录未启用。
- 这些限制不是默认安全绕过，新增接口仍必须经过 owner resolver、路径边界和日志脱敏检查。
