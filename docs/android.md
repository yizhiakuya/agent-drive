# 📱 安卓端方案与进度（2026-08-14）

> 状态：**已暂停打包，PWA 可用**。本文档记录完整方案、已有资产、卡点与续做步骤。

## 一、方案决策

| 路线 | 状态 | 说明 |
|------|------|------|
| **A. PWA**（主方案） | ✅ 已上线 | 手机浏览器打开 → 添加到主屏幕。全屏/图标/离线壳/分享/相机上传 |
| B. TWA 打包 APK | ⏸ 暂停（本地打包坑多） | 换 PWABuilder 在线打包（2 分钟）或本地续做（步骤见 §四） |
| C. Flutter 原生 | 备选 | 需要后台上传/系统集成时再做 |

## 二、已完成的（有效资产）

| 项 | 详情 |
|----|------|
| HTTPS 证书 | `*.rainaki.top` 通配符（Let's Encrypt EC-256），acme.sh 自动续期；证书文件 `/etc/nginx/certs/rainaki.{pem,key}` |
| nginx | 13311 端口 HTTPS → Agent Drive，SSE 友好（`/etc/nginx/sites-available/agent-drive`） |
| 域名/端口 | `https://home.rainaki.top:13311`（Cloudflare DNS-01 验证，家宽自定义端口） |
| PWA 能力 | manifest + sw 离线壳 + 分享到网盘(share_target) + 相机上传 + safe-area |
| 签名 keystore | `/root/agent-drive-android/agentdrive.keystore`（alias=agentdrive，密码见服务器本地 `/root/agent-drive-android/README.txt`） |
| assetlinks.json | 已在 `frontend/public/.well-known/assetlinks.json`（包名 `top.rainaki.agentdrive`，SHA256 指纹 `8ef4635c9f505891d0c9251ed229e610c7a5d7799bd7eb73311df1de4fe90a94`） |
| TWA 配置 | `/root/agent-drive-android/twa-manifest.json` |

## 三、TWA 本地打包卡点复盘（如果续做）

环境已就绪：JDK 17（`/usr/lib/jvm/java-17-openjdk-amd64`）、Android SDK（`~/.bubblewrap/android_sdk`，build-tools 35 + platform 35）、bubblewrap CLI。

bubblewrap 的连环交互坑（都需要 expect 应答）：
1. JDK 询问（必须 17）→ 已配 config.json
2. Android SDK 路径校验 → 已配（顶层需真实 `bin/` 拷贝，不是符号链接）
3. 项目 regenerate 确认（y）
4. versionName 要求 ≥6 字符（如 `1.0.10`）
5. keystore 密码提示（见本地 README.txt）
6. 构建改用腾讯 gradle 镜像（wrapper 里已换 `mirrors.cloud.tencent.com`）

expect 脚本在 `/tmp/twa3.exp`（临时，重启会丢；续做时按 §三 列表重写）。

## 四、续做路线（推荐 B1）

### B1. PWABuilder 在线打包（推荐，2 分钟）
1. 路由器把外网 **13311 → 服务器 13311** 端口转发（家宽）
2. 浏览器开 https://www.pwabuilder.com → 输入 `https://home.rainaki.top:13311`
3. 按提示生成 APK 下载 → 手机安装（需允许未知来源）

### B2. 本地续做
```bash
cd /root/agent-drive-android
# 项目已生成（app/ + gradlew）。若项目目录完整，直接构建：
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=~/.bubblewrap/android_sdk
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release-unsigned.apk
# 签名（若 gradle 未内嵌签名）:
$ANDROID_HOME/build-tools/35.0.0/apksigner sign \
  --ks agentdrive.keystore --ks-pass pass:$(cat /root/agent-drive-android/README.txt) \
  --out AgentDrive.apk app-release-unsigned.apk
```

### 安装到手机
- APK 传到手机（微信文件传输助手/网盘分享/数据线）→ 点击安装 → 允许未知来源
- 或干脆用 PWA：手机 Chrome/Edge 打开网址 → 菜单 → 添加到主屏幕

## 五、推送通知（第三阶段，未做）

需要：VAPID 密钥对（`npx web-push generate-vapid-keys`）+ 后端 webpush 库 + 前端订阅 UI。
触发场景：自动化报告生成、red 确认请求。等基本功能稳定后再评估。
