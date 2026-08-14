# 🛡️ 安全设计（认证与暴露面）

> 背景：此前网盘零认证——公网知道域名端口即可读写全部数据、操作 Agent。
> 本文档记录认证方案、暴露面加固与运维要点。

## 一、威胁模型（个人单用户）

| 威胁 | 等级 | 对策 |
|------|------|------|
| 公网任意访客访问 API/界面 | 高 | 全 API 鉴权（Cookie / 设备令牌） |
| 密码爆破 | 中 | PBKDF2-60 万次 + 登录限速 5 次/分钟/IP |
| 设备令牌泄露（旧手机/失窃） | 中 | 设备列表可吊销令牌，立即失联 |
| 明文流量 | 低 | 仅 nginx 13311 HTTPS 入口；8000 只绑 127.0.0.1 |
| 会话 cookie 窃取（XSS） | 低 | HttpOnly + SameSite=Lax + Secure(prod) |

## 二、认证模型

```
┌─ web/PWA（同源）─── HttpOnly Cookie（30 天）────────┐
│                                                     │
├─ App WebView（跨域）── Bearer 设备令牌 ──┐          │
│                                          ▼          ▼
├─ App 相册同步 Worker ── Bearer 设备令牌   统一鉴权 get_owner
│                                          （Cookie / Bearer / ?token=）
└─ 媒体预览 img/video ── ?token= 查询参数 ──┘
```

- **密码**：PBKDF2-SHA256 60 万次 + 随机盐，只存哈希（`system/auth.json`）
- **会话令牌**：HMAC-SHA256 签名、无状态、30 天；仅 web 同源使用
- **设备令牌**：随机 43 字符，服务端只存 SHA-256 哈希；App 登录后颁发一次，存 SharedPreferences，
  供后台同步/跨域请求使用；**移除设备 = 吊销令牌**
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

## 五、运维要点

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