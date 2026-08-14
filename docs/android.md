# 📱 安卓端方案与进度

> 更新（2026-08）：安卓客户端改为 **Capacitor 原生壳**（frontend/android）——web 前端代码原样打包进 App，
> 通过插件桥接入安卓 SDK（扫码连接服务器 / 相册自动同步 / 通知）。TWA 套壳方案已废弃（历史见 git 255d41e）。
> PWA 仍是日常主用形态；原生壳负责 PWA 做不到的事：后台同步、系统集成。

## 一、方案决策

| 路线 | 状态 | 说明 |
|------|------|------|
| **A. PWA**（日常主用） | ✅ 已上线 | 手机浏览器打开 → 添加到主屏幕。全屏/图标/离线壳/分享/相机上传 |
| **B. Capacitor 原生壳** | ✅ 已落地 | frontend/android：web 资源本地打包 + 原生插件桥（扫码连接/相册自动同步），同包名同签名 |
| ~~TWA 套壳~~ | ❌ 已废弃 | 只能打开网页，无后台/系统能力；工程存档于 git 255d41e |
| C. Flutter 原生 | 备选 | 仅在需要重写全部 UI 时考虑 |

## 二、原生壳架构（Capacitor）

```
frontend/out (Next 静态导出)  ──打包──▶  APK 内本地资源（离线壳）
        │                                      │
        │  JS 插件桥 (@capacitor/core)          │  Java 插件
        ▼                                      ▼
  ServerConfig / PhotoSync      加密 SharedPreferences + WorkManager + MediaStore
        │                                      │
        └──────────▶  HTTP API ◀──────────────┘
                       扫码配置的服务器地址（默认 https://home.rainaki.top:13311）
```

**首启流程（扫码即授权，免密码）**：App 打开 → 原生扫码页 → 扫网页「设置 → 连接手机 App」的二维码（agentdrive://connect?server=...&pair=一次性配对码）→ 原生层兑换设备令牌存入**加密 SharedPreferences**（AES256-GCM，MasterKey 在 Android Keystore）→ 加载本地 web 资源。配对码一次性、5 分钟有效；重扫自动吊销旧令牌。无二维码时可用密码登录作逃生口。

**相册自动同步**：PhotoSyncWorker（WorkManager 周期任务，App 关闭/重启都运行）扫描 MediaStore 新增照片 → multipart 上传 /files/upload（按日期归档到 相册同步/YYYY-MM-DD/）→ 完成通知。约束：电池非低电量 + 网络（可选仅 Wi-Fi），频率 1/6/12/24 小时。

**传输可靠性（网盘级去重）**：
- 内容去重（秒传）：客户端逐张算 MD5 → 服务端索引（system/upload-index.json）命中且文件仍在 → 跳过传输与索引，直接算成功
- 同名冲突：noclobber 参数 → 服务端自动加序号 name-2.jpg，绝不覆盖
- 整秒检查点（1.0.22 起）：`lastSyncAt` 只推进到「整秒全部成功」的秒；同秒内有失败或未取完则挂 `pendingSecond+pendingMaxId`（_ID 水位）下一轮续传——同秒失败张、单秒超 200 张（连拍/批量导入）都不会再被 `DATE_ADDED > 检查点` 永久跳过
- 单张失败不阻塞整批：跳过继续，Worker 指数退避重试；重试零流量（已传文件秒传命中）
- 事件驱动：MediaStore ContentObserver 拍照秒级触发快速同步（周期任务兜底）；查询 SQL LIMIT 不 COUNT 全量统计
- 令牌与配置加密存储：服务器地址/设备令牌/同步设置存 EncryptedSharedPreferences；`allowBackup=false` 防云备份恢复克隆令牌；旧版明文数据首次启动自动迁移并清空
- 进度可视：App 内进度块（实时事件）+ 通知栏进度条；全局下拉刷新（App/PWA 通用）

## 三、已完成的（有效资产）

| 项 | 详情 |
|----|------|
| HTTPS 证书 | *.rainaki.top 通配符（Let's Encrypt EC-256），acme.sh 自动续期 |
| nginx | 13311 端口 HTTPS → Agent Drive，SSE 友好（deploy/nginx-agent-drive.conf） |
| 域名/端口 | https://home.rainaki.top:13311（Cloudflare DNS-01，家宽自定义端口） |
| PWA 能力 | manifest + sw 离线壳 + share_target + 相机上传 + safe-area |
| assetlinks.json | frontend/public/.well-known/assetlinks.json（包名 top.rainaki.agentdrive，SHA256 8ef4635c…e90a94） |
| 原生壳工程 | ✅ frontend/android（Capacitor 7 + AGP 8.7.3 + Gradle 8.14.3 wrapper），含 ServerConfig/PhotoSync 插件、扫码页、相册同步 Worker |
| web 端二维码 | ✅ 设置页「连接手机 App」卡片（qrcode 生成 agentdrive://connect?server=当前origin） |
| **设备列表** | ✅ App 启动/回前台/同步完成时 POST /api/v1/devices/register 心跳登记；web 设置页「🖥️ 设备列表」实时显示（名称/型号/版本/活跃时间/相册同步状态），可移除；存储 backend/system/devices.json |
| 签名 keystore | 服务器 /root/agent-drive-android/agentdrive.keystore（alias=agentdrive，密码见服务器本地 README.txt，不入 git） |

## 四、构建（Windows 本机）

前置环境（本机已装好）：JDK 17+（Temurin 21）、Android SDK C:\Android\Sdk、Gradle 8.14.3 C:\Android\gradle-8.14.3。

```bash
cd frontend
npm run build                 # 静态导出 out/（含二维码卡片）
npx cap sync android          # 拷贝 web 资源进 App

# keystore：从服务器 scp 到本机（指纹与 assetlinks.json 一致）
scp root@服务器:/root/agent-drive-android/agentdrive.keystore D:/ds/agent-drive-keystore/
cd android && copy keystore.properties.template keystore.properties   # 填路径/密码

gradlew.bat assembleRelease  # 或 C:\Android\gradle-8.14.3\bin\gradle.bat assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk（keystore 就位则已签名）
```

无 keystore 时产物为 app-release-unsigned.apk，用 apksigner 单独签名即可。

### 发布 APK（web 页提供下载）

```bash
# APK 在部署时拷入 out/app（不进 git，见 .gitignore）——不进 public/，避免被打进 App 自身资源
cd .. && npm run build
copy android\app\build\outputs\apk\release\app-release.apk out\app\agent-drive.apk
# 部署 out/ 到服务器（tar 原子替换，保留 .well-known）
# 之后下载地址稳定不变：https://home.rainaki.top:13311/app/agent-drive.apk
# web 设置页「连接手机 App」卡片内有 📲 下载按钮
```

### 安装到手机

- USB：adb install app-release.apk（C:\Android\Sdk\platform-tools\adb.exe）
- 或 APK 传手机 → 点击安装 → 允许未知来源

## 五、后续路线

| 项 | 状态 |
|----|------|
| 相册自动同步 | ✅ 已落地（后台周期 + 手动立即同步 + 仅 Wi-Fi） |
| 扫码连接服务器 | ✅ 已落地（首启 + 设置内重扫） |
| 推送通知（VAPID + webpush） | 未做：自动化报告/red 确认的推送，等基本功能稳定后评估 |
| 视频/文件自动同步 | 可扩展：SyncEngine 增加 MediaStore.Video 查询即可 |
| 音视频转写 | 未做（资源评估后）：ingest 加 whisper 解析器 |
| iOS 客户端 | 未做：Capacitor 工程可 npx cap add ios（需 macOS 构建） |