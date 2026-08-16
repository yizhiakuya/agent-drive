# 🛡️ 安全设计（认证与暴露面）

> 背景：此前网盘零认证——公网知道域名端口即可读写全部数据、操作 Agent。
> 本文档记录认证方案、暴露面加固与运维要点。

## 一、威胁模型（个人单用户）

| 威胁 | 等级 | 对策 |
|------|------|------|
| 公网任意访客访问 API/界面 | 高 | 全 API 鉴权（Cookie / 设备令牌） |
| 密码爆破 | 中 | PBKDF2-60 万次 + 登录限速 5 次/分钟/IP |
| 设备令牌泄露（旧手机/失窃） | 中 | 设备列表可吊销令牌，立即失联；App 端令牌加密存储（Keystore）+ 禁云备份 |
| 明文流量 | 低 | 仅 nginx 13311 HTTPS 入口；8000 只绑 127.0.0.1 |
| 会话 cookie 窃取（XSS） | 低 | HttpOnly + SameSite=Lax + Secure(prod)；session 带随机 jti，登出哈希吊销并保留到原到期时间 |
| 认证文件损坏后重新开放首次设密 | 高 | 已存在的 auth.json 无法解析/读取、顶层或设备/配对/撤销嵌套字段畸形时失败关闭并拒绝启动，保留原文件等待恢复 |
| 路径穿越 / symlink 逃逸读敏感文件 | 高 | storage.resolve 组件级拒绝符号链接 + 越界 403；`.index/.trash/.storage.lock` 与在途 staging 公共访问直接拒绝；SPA 静态回退 resolve 后校验仍位于 frontend/out，越界 404 |
| 覆盖/并发上传破坏数据 | 中 | 请求体流式写 0600 temp + fsync；父目录用 dirfd/O_NOFOLLOW；覆盖原子 replace、noclobber 原子 link，并以 link/replace 为提交点，之后的目录 fsync/临时链接清理异常不伪报失败；目录复制 staging 后原子交换，提交后的清理失败只留隐藏待回收项；服务端实算 MD5，索引绑定文件 revision |
| 后台任务重复执行/崩溃丢失 | 中 | SQLite WAL 持久队列、任务去重键、Worker 租约与心跳；租约过期按最大尝试次数恢复或失败，任务处理器保持幂等 |

## 二、认证模型（扫码即授权）

```
web/PWA ──密码──▶ 会话 Cookie（30 天）──▶ 生成配对码（二维码）──▶ App 扫码兑换
                                                              （一次性 5 分钟）
App：设备令牌（Bearer）──▶ 全部 API（WebView / 后台 Worker）；媒体 raw/download GET 可用受限 ?token=
```

- **密码**：PBKDF2-SHA256 60 万次 + 随机盐，只存哈希（`system/auth.json`）；只进 web
- **会话令牌**：HMAC-SHA256 签名、随机 `jti`、30 天；仅 web 同源使用。登出把令牌 SHA-256 记入持久撤销表直到原到期时间，过期记录在验证/颁发/吊销时清理；auth 状态写入由“同路径共享进程锁 + POSIX sidecar `flock`”包围，每次 reload-before-mutate，多实例不会互相覆盖撤销/设备状态（无 `fcntl` 平台仍保证同进程多实例）
- **配对码（扫码即授权）**：已登录 web 生成 → 二维码携带 → App 兑换设备令牌，**免输密码**。
  144 bit 随机、一次性、5 分钟有效、最多 3 个未使用、兑换限速 10 次/分钟/IP、失败写审计。
  已使用的码保留到过期：二次扫码命中会报"已被使用"（二维码被盗的告警信号）
- **设备令牌**：随机 43 字符，服务端只存 SHA-256 哈希；**重扫 = 换新令牌并吊销旧令牌**（一设备一有效令牌）；
  **移除设备 = 吊销令牌**
- **逃生口**：App 重扫码页有小链接"使用密码登录"（令牌被吊销且手边无已登录浏览器时用，同受限速保护）
- **首设密码**：首次访问 → 设密页（第一个设置者成为主人）；之后登录页

## 三、鉴权通道（get_owner）

| 通道 | 载体 | 场景 |
|------|------|------|
| Cookie | `agentdrive_session` | web/PWA 全部请求（SSE/上传/分享自动携带） |
| Bearer | `Authorization: Bearer <session|device>` | App 后台 Worker、心跳、跨域 JSON 请求 |
| 查询参数 | `?token=<device>` | **仅** `GET /files/raw` 与 `GET /files/download`；媒体元素无法带 Header 的兼容通道 |

公开豁免：`/api/v1/health`（探活）、`/api/v1/auth/*` 中的 status/setup/login/logout/pair-exchange、静态资源、`/.well-known/assetlinks.json`。
其余全部 `GET/POST/... /api/v1/*` 均需鉴权；合法 query token 也不能访问列表、状态或写接口。普通 API、上传和 Chat SSE 共用身份快照感知的 401 处理：只有当前凭据的响应才派发 `agent-drive:unauthorized`，旧 token 的迟到 401 不会踢掉新登录。
登出会删除 Cookie，并在服务端吊销请求携带的有效 Cookie session / Bearer session 或设备令牌；复制出的旧凭据不可重放。原生端离线/5xx 时仍先可靠清除本机加密令牌，并明确提示“服务端吊销状态未知”；401/403 表示当前凭据已经不可用，可直接完成本地退出，不误报仍有有效令牌。

## 四、暴露面加固（服务器）

| 项 | 现状 | 措施 |
|----|------|------|
| 公网入口 | 13311 (nginx HTTPS) + **8000 裸奔** | ✅ 8000 改绑 127.0.0.1（systemd ExecStart --host 127.0.0.1） |
| 防火墙 | ufw inactive | 暂不启用（家宽 IP 变化风险）；如需要：默认 deny + 放行 22/13311 |
| 登录限速 | 无 | ✅ 5 次/分钟/IP（内存级，单 API 进程部署 + 过期 key 自动清理）；仅 loopback 反代可提供 X-Forwarded-For，并取 nginx 追加的最右侧有效 IP，直连头不可信 |
| 上传大小 | 仅 nginx 200m | ✅ 后端兜底 413（max_upload_mb=300MB），防绕过 nginx 直连 8000 打爆内存 |
| SPA 静态回退 | 用户路径直接拼接构建目录 | ✅ 拒绝 `..` 路径段，并对 resolve 后的路径做 frontend/out 目录边界校验，防止读取运行时配置与凭据 |
| 服务进程 | root 裸跑 | ✅ API 与任务 Worker 分离，均启用 UMask=077 / NoNewPrivileges / ProtectSystem=full / PrivateTmp / MemoryMax=1G（仓库在 /root 下故保留 root+能力受限）；unit 发布前经 `systemd-analyze verify` 校验 |
| 外部 API 代理 | 继承 shell/SOCKS 环境 | ✅ 两个 service 只读取 `/etc/agent-drive/proxy.env` 的 HTTP(S) 代理，并用 `UnsetEnvironment` 清除 `ALL_PROXY/all_proxy` |
| 审计 | 仅 Agent 操作 | ✅ 登录成功/失败/设密/设备令牌颁发均入 audit.log（含 IP，密码不落日志；1MB 轮转保留 5 份） |

## 五、设备侧加固（App）

- **令牌加密存储**：设备令牌/服务器地址/同步设置存独立 `agent_drive_secure` EncryptedSharedPreferences（AES256-GCM + AES256-SIV，MasterKey 由 Android Keystore 硬件级保护），不再与 legacy 业务键混用底层文件；升级兼容旧 `agent_drive` 明文和 1.0.27 同文件密文，同键时以持续写入的 1.0.27 密文为现行值并清理更老明文残留；独立新密文若与 legacy 现行值冲突则保留双方并失败关闭。新密文成功或与旧值逐键相等后才清理旧业务数据，AndroidX keyset 永不 clear，清理中断可幂等重试
- **失败关闭**：Keystore/加密 prefs 初始化、迁移、明文清理或 commit 任一步失败都不会回退普通 SharedPreferences；原生入口显示阻断对话框，插件 Promise 显式 reject，Worker 用 Log 记录且稳定 retry。持续故障需清除 App 存储后重新扫码
- **禁云备份**：`allowBackup=false`——防止 Google 云备份恢复把设备令牌克隆到另一台手机（克隆即可冒充已配对设备）

## 六、运维要点

- **密码文件**：`backend/system/auth.json`，含密码哈希 + 签名密钥 + 设备令牌哈希；写入使用 0600 安全临时文件、fsync 与原子替换
  —— 备份该文件即备份全部凭据；**删除该文件 = 重置认证**（重新设密，旧设备令牌全部失效）；文件存在但损坏/不可读时服务拒绝启动，绝不静默回到未初始化状态或覆盖原文件
- **忘记密码**：服务器上删除 auth.json 后重启服务 → 重新设密 → App 重新登录
- **吊销设备**：web 设置 → 设备列表 → 移除（同时吊销其令牌）
- **变更密码**：暂无 UI；先删 auth.json 重置（会话会全部失效，属预期）
- **媒体 URL 含 token**：nginx access log 会记录 ?token=，必要时 log_format 去掉 query；令牌可吊销，风险可控
- **任务数据库**：`backend/system/tasks.sqlite3` 为 0600 + WAL；`scripts/backup.sh` 通过 SQLite 在线备份 API 生成一致快照，禁止直接复制活动中的 db/wal/shm 三件套
- **代理配置**：`/etc/agent-drive/proxy.env` 权限 0600，只写 `HTTP_PROXY/HTTPS_PROXY/NO_PROXY`（可同时写小写形式），不要写 SOCKS `ALL_PROXY`

## 七、遗留与后续

| 项 | 状态 |
|----|------|
| 修改密码 UI | 未做（删除 auth.json 可重置） |
| fail2ban / nginx 限速 | 未做（应用层限速已覆盖登录） |
| 双因素 | 不计划（个人单用户） |
| 端到端加密文件 | 不计划 |
