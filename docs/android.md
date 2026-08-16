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
- 内容去重（秒传）：客户端读取照片到 cache 并算 MD5 → `GET /files/dedupe` 只查询服务端实算、revision 仍匹配的 verified 索引 → 命中零传输；未命中 multipart 上传，服务端边流式落盘边复算并拒绝 hash 不一致
- 同名冲突：noclobber 参数 → 服务端自动加序号 name-2.jpg，绝不覆盖
- 整秒检查点：`CheckpointTracker` 只推进「整秒全部成功」的 `lastSyncAt`；首个失败后冻结连续 `_ID` 水位，更晚成功秒不得越过；200+1 行查询中第 201 行只作同秒/跨秒截断哨兵而不上传。查询、Cursor、MediaStore 字段和 checkpoint commit 异常也会保留已有/当前秒 pending 并退避重试；每行先读 DATE_ADDED 并以其真实秒 begin 再读其余字段，字段异常挂在真实失败秒上而不是误标上一组。`lastSyncAt + pendingSecond + pendingMaxId` 由一次加密 prefs commit 原子发布
- 单张失败不阻塞整批：服务端会一直拒绝的永久 4xx（400/413/415/416/422）跳过该张并推进连续水位，不会无限重试卡死检查点；其余 4xx（404/405/408/409/429 等）视为可能瞬时，冻结水位由 Worker 指数退避重试；重试零流量（已传文件秒传命中）
- 事件驱动：MediaStore ContentObserver 拍照触发快速同步（1 秒防抖合并连拍，周期任务兜底）；observer 由 Activity 字段持有且在 `onDestroy()` 注销/清除回调，避免重建后泄漏和重复排队；API 26+ Bundle 与旧版 sortOrder 都限制 201 行，不 COUNT 全量统计
- 约束与并发一致：周期、ContentObserver 快速和手动立即同步都要求电池非低电量；“仅 Wi-Fi”用 `UNMETERED`，关闭时用 `CONNECTED`。不同 unique work 名称即使同时触发，`SyncEngine.sync()` 也在 App 进程内串行执行，避免两个 Worker 互相覆盖加密检查点
- 配置与调度一致：一次 configure 的 enabled/wifiOnly/interval/folder 用单次加密 prefs commit 发布；多个 configure 进入专用单线程执行器，从写入到 WorkManager Operation 入库结果全程串行，不阻塞桥接/UI 线程。失败时保留已提交的“期望状态”并明确 reject，下次 App 启动由 `ensureScheduled` 幂等收敛，不执行无法保证副作用一致的伪回滚
- 断网中止（1.0.23 起）：连接失败/401/403/5xx 整批中止 + 退避重试（不再逐张串行超时）；单张 4xx 跳过继续
- 权限判定只看图片读取权限：通知权限被拒不算同步失败（仅失去进度通知）
- 令牌与配置加密存储：服务器地址/设备令牌/同步设置存独立 `agent_drive_secure` EncryptedSharedPreferences；`allowBackup=false` 防云备份恢复克隆令牌。升级兼容旧 `agent_drive` 明文与 1.0.27 同文件密文：同键时以 1.0.27 持续写入的密文为现行值，旧明文视为更早版本/清理残留；独立新密文若与 legacy 现行值冲突则保留双方并失败关闭。新密文 commit 成功或逐键确认相等后才清理旧业务数据，AndroidX keyset 永不 clear，清理失败下次启动幂等重试；初始化/迁移/commit 失败不降级明文，入口弹窗、插件 reject、Worker Log+retry 都显式处理
- 1.0.24：修复旧版明文配置迁移在 Android 16 上因误处理 AndroidX 保留键导致的启动崩溃
- 1.0.25：文件页手机工具栏改为 3×2、44px 触控网格；320px 极窄屏隐藏品牌文字但保留图标与无障碍名称，修复标题竖排、设置入口被截断和整页横向滚动；viewport 使用 Next.js 16 独立导出
- 1.0.26：新增「后台任务」页，展示文件索引、批量重建、维护与自动化任务的状态/进度/错误，支持取消、失败重试和手动重建索引；原生 App 每 5 秒携带 Bearer 令牌轮询，web/PWA 使用 Cookie SSE
- 1.0.27：修复不可读 MediaStore 流未标记失败导致的检查点丢图；引入连续水位 Tracker 与 201 行哨兵，检查点单次原子提交并串行化同进程同步；秒传改 verified 预检 + 服务端实算 MD5；三种同步入口统一网络/电量约束；加密存储完全失败关闭并提供用户可见错误
- 1.0.28：加密配置迁到独立 prefs 文件并兼容旧明文/1.0.27 同文件密文；同文件密文优先于明文残留，独立新旧密文冲突时保留双方并失败关闭。同步设置每次 configure 单次 commit，专用后台单线程串行等待 WorkManager 入库，调度失败保留期望状态供下次启动重试；服务端永久拒绝的 4xx 跳过推进水位，不再无限重试卡死检查点；Activity 销毁时注销 MediaStore observer 并取消挂起权限回调，避免重建后泄漏和重复快速同步
- 相册同步进度可视：App 内进度块（实时事件）+ 通知栏进度条；全局下拉刷新（App/PWA 通用）

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
| **后台任务中心** | ✅ 任务列表/索引覆盖率/Worker 在线状态；取消与重试；向量配置保存后自动入队重建，上传和相册同步后的内容解析不阻塞请求 |
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
