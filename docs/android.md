# Android 客户端

> 现行方案（2026-08-21）：Capacitor 7 原生壳复用 Next.js 静态前端。PWA 是日常主用形态，App 只承载 PWA 无法可靠提供的扫码配对、后台同步、MediaStore、通知和设备集成。当前版本号为 `1.0.31`（versionCode `31`）。

## 1. 工程与职责

```text
frontend/out ──Capacitor──▶ APK 本地 web 资源
      │                              │
      └── ServerConfig / PhotoSync ──┴── Keystore + WorkManager + MediaStore
```

- `MainActivity` 注册 `ServerConfigPlugin` 和 `PhotoSyncPlugin`，必须发生在 `super.onCreate()` 之前。
- `ServerConfig` 管理服务器地址、设备 ID 和设备令牌；`PhotoSync` 管理同步配置、权限、调度、进度和手动重扫。
- App 不提供 LLM/embedding/vision 配置界面；AI 配置在 Web 设置页完成。
- 生产服务器默认为 `https://home.rainaki.top:13311`，也可以通过扫码连接配置其他地址。

## 2. 首次连接和认证

1. 打开 App，进入原生扫码页。
2. 扫描 Web 设置页“连接手机 App”生成的 `agentdrive://connect?server=...&pair=...` 二维码。
3. App 只接受无用户凭据、无查询/片段的 HTTPS 服务器地址，并限制兑换响应体为 64 KiB；不安全地址会在本地拒绝，不会发送配对码。
   已注册的 `ServerConfig.setServer` bridge 也复用同一地址校验，不能从 WebView 绕过扫码边界写入 HTTP 或带凭据地址；相机权限拒绝会保留可重试提示。
4. App 调用 pair-exchange 兑换一次性设备令牌，并写入独立的加密 prefs。
5. 令牌存在时加载本地 web 资源；令牌缺失或吊销时回到重扫码页，密码登录作为逃生口。切换到不同服务器会原子清空旧服务器的同步检查点，避免跳过新服务器文件。

配对码有效期 5 分钟且只能使用一次；重扫或移除设备会吊销旧令牌。服务端只保存设备令牌 hash，App 不把令牌传给 JavaScript 页面之外的模型或日志。

## 3. 安全存储

服务器地址、设备令牌、设备 ID 和同步设置使用独立 `agent_drive_secure` EncryptedSharedPreferences，MasterKey 存在 Android Keystore，应用设置 `allowBackup=false`。

升级时可以识别旧 `agent_drive` 明文和旧版本同文件密文，但只有新 prefs 成功提交或逐键确认相等后才清理旧数据；密文冲突、Keystore 初始化、迁移、清理和 commit 失败都失败关闭，不降级到明文。AndroidX keyset 不得清理，失败清理可在下次启动幂等重试。

## 4. 相册同步契约

`PhotoSyncWorker` 使用 WorkManager 周期运行，App 关闭或重启后仍可执行；ContentObserver 以 1 秒防抖触发快速同步，手动同步和周期同步使用不同 unique work 名称。`SyncEngine.sync()` 在进程内串行，避免并发 Worker 覆盖 checkpoint。

### 检查点

- `lastSyncAt` 只推进到整秒内所有可见照片都完成；同秒失败或查询截断时保存 `pendingSecond + pendingMaxId`。
- MediaStore 查询最多取 201 行；第 201 行只作为截断哨兵，不上传。每行先读取 `DATE_ADDED`，字段异常归入该真实秒的 pending。
- `lastSyncAt`、pending second、pending max ID 同一次加密 prefs commit；失败不提交部分状态，不让更晚秒越过最早失败秒。

### 上传

- 客户端可先 `GET /api/v1/files/dedupe?md5=...` 做只读预检，但只有服务端 verified 且 revision 仍匹配才命中秒传。
- 真正上传始终由服务端重新计算 MD5；multipart part 名为 `file`，`path` 为 query 参数，`md5` 和 `noclobber` 为表单字段。
- 同名文件使用原子 noclobber 自动改名，不覆盖已有文件。
- HTTP 永久 4xx（400/413/415/416/422）跳过该项并推进连续水位；其他 4xx 冻结当前秒水位并继续本批，连接失败、401/403 和 5xx 中止本批并退避重试。
- 本地媒体已删除（`FileNotFoundException`）或读取权限永久拒绝（`SecurityException`）时跳过该项；其他本地 I/O 失败冻结当前秒水位等待下轮重试。显式 `InterruptedException` 或已中断线程会保留/重设中断位、中止本批并保留 pending，不能被“单张失败继续”吞掉。

### 调度与权限

- enabled、wifiOnly、interval、folder 一次加密提交；多个 configure 调用进入专用后台执行器，从 prefs 写入到 WorkManager 入库全程串行。
- 调度失败保留已提交的期望状态并明确 reject，由下次启动的 `ensureScheduled` 收敛，不伪造 WorkManager 回滚。
- 只检查图片读取权限；通知权限拒绝只影响通知，不使同步失败。状态接口同时返回
  `permissionGranted`、`configured`、`lastError` 和实时 `phase/uploaded/total`，设置页应优先
  展示权限引导和可操作的错误重试入口；`started=false` 表示同步开关未启用，不应显示为已启动。
- 通知权限被拒时，诊断卡提供 `打开通知设置` 直达当前 App 的系统通知设置；通知权限仍不是相册同步的必要条件。
- 设置页同步诊断还展示目标目录、上次扫描/上传/去重/跳过/失败/可重试计数和通知开关；旧版本原生状态缺少这些字段时前端按零值/允许通知兼容，不显示 `NaN`。
- 停用同步会同时取消周期和相册观察者触发的 quick work；目标文件夹通过 `targetFolder` 配置并
  与 enabled/wifiOnly/interval 在同一次加密提交中保存。Android 与服务端共同拒绝 `.index`、`.trash`、`.versions`、`.storage.lock` 及 upload/copy staging 内部路径，避免配置保存成功后才在上传阶段失败。
- Activity 销毁时注销 ContentObserver、清除 debounce callback、替换或 reject 挂起权限回调。

## 5. 构建与发布

前置：Temurin 21、Android SDK `C:\Android\Sdk`、Gradle 8.14.3。日常前端迭代不构建 APK。

```powershell
cd frontend
npm run build
npx cap sync android
cd android
gradlew.bat lintDebug
gradlew.bat testDebugUnitTest
gradlew.bat assembleRelease
```

签名配置放在仓库外的 `keystore.properties`，不得提交。Release 产物为 `app/build/outputs/apk/release/app-release.apk`；需要提供下载时复制为 `frontend/out/app/agent-drive.apk`，随前端 tar 一起发布，不放进 `frontend/public`，避免 APK 被打进自身资源。

固定下载地址：[https://home.rainaki.top:13311/app/agent-drive.apk](https://home.rainaki.top:13311/app/agent-drive.apk)

APK 发版时同步递增 `frontend/android/app/build.gradle` 的 versionCode/versionName，并运行 Android JVM 单测。普通 Web/PWA 改动只需前端门禁和静态部署，部署脚本会保留已有 APK。

## 6. 当前边界

当前没有 iOS 工程、视频/音频自动同步或音视频转写；这些能力需要单独设计，不应在 App 现状描述中写成已支持。
