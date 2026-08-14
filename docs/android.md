# 📱 安卓端方案与进度

> 更新（2026-08）：TWA 工程已入库 `android/`，Windows 本机可直接构建签名 APK，不再依赖服务器上的 bubblewrap/expect 交互。
> PWA 仍是日常主用形态；APK 是给想要原生图标/免地址栏/系统分享入口的补充形态。

## 一、方案决策

| 路线 | 状态 | 说明 |
|------|------|------|
| **A. PWA**（主方案） | ✅ 已上线，日常主用 | 手机浏览器打开 → 添加到主屏幕。全屏/图标/离线壳/分享/相机上传 |
| **B. TWA 打包 APK** | ✅ 工程已入库 | `android/` Gradle 工程（androidbrowserhelper），本机构建见 §四 |
| C. Flutter 原生 | 备选 | 需要后台上传/系统集成时再做 |

## 二、已完成的（有效资产）

| 项 | 详情 |
|----|------|
| HTTPS 证书 | `*.rainaki.top` 通配符（Let's Encrypt EC-256），acme.sh 自动续期；`/etc/nginx/certs/rainaki.{pem,key}` |
| nginx | 13311 端口 HTTPS → Agent Drive，SSE 友好（`deploy/nginx-agent-drive.conf` 已入库） |
| 域名/端口 | `https://home.rainaki.top:13311`（Cloudflare DNS-01，家宽自定义端口） |
| PWA 能力 | manifest + sw 离线壳 + 分享到网盘(share_target) + 相机上传 + safe-area |
| assetlinks.json | `frontend/public/.well-known/assetlinks.json`（包名 `top.rainaki.agentdrive`，SHA256 `8ef4635c…e90a94`） |
| **TWA 工程** | ✅ `android/`（2026-08 入库）：AGP 8.7.3 + Gradle 8.14.3 wrapper + androidbrowserhelper 2.5.0，源码/图标/文档齐全，可本机构建 |
| 签名 keystore | 服务器 `/root/agent-drive-android/agentdrive.keystore`（alias=agentdrive，密码见服务器本地 README.txt，不入 git） |

## 三、历史卡点（服务器 bubblewrap 打包，已废弃归档）

早先尝试在服务器上用 bubblewrap 生成 TWA 工程，卡在交互提示（JDK 询问/SDK 路径校验/regenerate 确认/versionName≥6/keystore 密码/gradle 镜像），需 expect 应答。
该路线已废弃：工程改为手写模板直接入库 `android/`，构建在 Windows 本机完成，全程无交互。服务器环境（JDK17/SDK35/bubblewrap）保留备查。

## 四、APK 构建（当前路径：Windows 本机）

前置环境（本机已装好）：

- JDK 17+（Temurin 21）
- Android SDK：`C:\Android\Sdk`（cmdline-tools + platform-tools + platforms;android-35 + build-tools;35.0.0）
- Gradle 8.14.3：`C:\Android\gradle-8.14.3`（wrapper 已入库，日常用 `gradlew.bat` 即可）

```bash
# 1. keystore：从服务器 scp 到本机（指纹与 assetlinks.json 一致，免地址栏直接生效）
scp root@服务器:/root/agent-drive-android/agentdrive.keystore D:/ds/agent-drive-keystore/

# 2. 签名配置（不入 git）
cd android && copy keystore.properties.template keystore.properties   # 填 storeFile/密码

# 3. 构建（keystore 就位则直接出签名 APK）
gradlew.bat assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

keystore 未就位时产物为 `app-release-unsigned.apk`，用 apksigner 单独签名即可（步骤见 `android/README.md`）。

### 安装到手机

- APK 传到手机（微信文件传输助手/网盘分享/数据线）→ 点击安装 → 允许未知来源
- 或干脆用 PWA：手机 Chrome/Edge 打开网址 → 菜单 → 添加到主屏幕

## 五、推送通知（第三阶段，未做）

需要：VAPID 密钥对（`npx web-push generate-vapid-keys`）+ 后端 webpush 库 + 前端订阅 UI。
触发场景：自动化报告生成、red 确认请求。等基本功能稳定后再评估。