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
| 会话 cookie 窃取（XSS） | 低 | HttpOnly + SameSite=Lax + Secure(prod) |
| 路径穿越 / symlink 逃逸读敏感文件 | 高 | storage.resolve 组件级拒绝符号链接 + 越界 403；上传写越界同样被拦 |
| 覆盖/并发上传破坏数据 | 中 | save_bytes 原子写（tmp+replace）；noclobber 走 os.link 原子独占创建（无 TOCTOU）；秒传索引随内容变更自动失效 |

## 二、认证模型（扫码即授权）

```
web/PWA ──密码──▶ 会话 Cookie（30 天）──▶ 生成配对码（二维码）──▶ App 扫码兑换
                                                              （一次性 5 分钟）
App：设备令牌（Bearer）──▶ 全部 API（WebView / 后台 Worker / 媒体预览 ?token=）
```

- **密码**：PBKDF2-SHA256 60 万次 + 随机盐，只存哈希（`system/auth.json`）；只进 web
- **会话令牌**：HMAC-SHA256 签名、无状态、30 天；仅 web 同源使用
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
| 查询参数 | `?token=<device>` | 媒体元素（img/video/audio）无法带 Cookie/Header 的兼容通道 |

公开豁免：`/api/v1/auth/status|setup|login|logout`、静态资源、`/.well-known/assetlinks.json`。
其余全部 `GET/POST/... /api/v1/*` 均需鉴权，401 触发前端回登录页。

## 四、暴露面加固（服务器）

| 项 | 现状 | 措施 |
|----|------|------|
| 公网入口 | 13311 (nginx HTTPS) + **8000 裸奔** | ✅ 8000 改绑 127.0.0.1（systemd ExecStart --host 127.0.0.1） |
| 防火墙 | ufw inactive | 暂不启用（家宽 IP 变化风险）；如需要：默认 deny + 放行 22/13311 |
| 登录限速 | 无 | ✅ 5 次/分钟/IP（内存级） |
| 审计 | 仅 Agent 操作 | ✅ 登录成功/失败/设密/设备令牌颁发均入 audit.log（含 IP，密码不落日志） |

## 五、设备侧加固（App）

- **令牌加密存储**：设备令牌/服务器地址/同步设置存 EncryptedSharedPreferences（AES256-GCM + AES256-SIV，MasterKey 由 Android Keystore 硬件级保护），不再明文落盘；旧版明文数据首次启动自动迁移并清空
- **禁云备份**：`allowBackup=false`——防止 Google 云备份恢复把设备令牌克隆到另一台手机（克隆即可冒充已配对设备）

## 六、运维要点

- **密码文件**：`backend/system/auth.json`（0600 建议），含密码哈希 + 签名密钥 + 设备令牌哈希
  —— 备份该文件即备份全部凭据；**删除该文件 = 重置认证**（重新设密，旧设备令牌全部失效）
- **忘记密码**：服务器上删除 auth.json 后重启服务 → 重新设密 → App 重新登录
- **吊销设备**：web 设置 → 设备列表 → 移除（同时吊销其令牌）
- **变更密码**：暂无 UI；先删 auth.json 重置（会话会全部失效，属预期）
- **媒体 URL 含 token**：nginx access log 会记录 ?token=，必要时 log_format 去掉 query；令牌可吊销，风险可控

## 六、遗留与后续

| 项 | 状态 |
|----|------|
| 修改密码 UI | 未做（删除 auth.json 可重置） |
| fail2ban / nginx 限速 | 未做（应用层限速已覆盖登录） |
| 双因素 | 不计划（个人单用户） |
| 端到端加密文件 | 不计划 |