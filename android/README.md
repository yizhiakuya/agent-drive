# 🤖 Android 客户端（TWA）工程

> 把 Agent Drive 的 PWA 打包成免地址栏的原生 APK（Trusted Web Activity）。
> 对应方案与历史见 docs/android.md。

## 原理

- LauncherActivity（androidbrowserhelper）全屏打开 https://home.rainaki.top:13311
- 免地址栏验证走 Digital Asset Links：APK 签名指纹 ↔ 服务器 /.well-known/assetlinks.json
- 分享到网盘：系统分享 → ACTION_SEND → POST 到前端 manifest 的 share_target

## 本机构建（Windows）

前置：JDK 17+（本机 Temurin 21 ✅）、Android SDK（cmdline-tools + platform 35 + build-tools 35，位于 C:\Android\Sdk）、Gradle 8.14.3（C:\Android\gradle-8.14.3）。

~~~powershell
# 1. 签名配置（不入 git）：从模板复制并填真实路径/密码
copy keystore.properties.template keystore.properties

# 2. 构建（keystore 就位则直接出签名 APK）
.\gradlew.bat assembleRelease
# 产物: app\build\outputs\apk\release\app-release.apk（已签名）或 app-release-unsigned.apk
~~~

> 首次运行 gradlew 会下载 Gradle 发行包（国内网络可能卡在 GitHub Pages）。
> 本机已把 8.14.3 发行包预置到 wrapper dist 目录；换机器时可先手动下载
> gradle-8.14.3-bin.zip 解压到 %USERPROFILE%\.gradle\wrapper\dists\gradle-8.14.3-bin\ 下。
> 或者直接用本地安装的 Gradle：C:\Android\gradle-8.14.3\bin\gradle.bat assembleRelease

keystore 不在仓库（*.keystore 已 gitignore）。本机约定放置目录：
D:\ds\agent-drive-keystore\agentdrive.keystore（从服务器 /root/agent-drive-android/ scp，密码在服务器本地 README.txt）。

## 无 keystore 时的兜底签名

~~~powershell
# 用 SDK 自带的 apksigner 手动签名未签名产物
& "C:\Android\Sdk\build-tools\35.0.0\apksigner.bat" sign `
  --ks D:\ds\agent-drive-keystore\agentdrive.keystore `
  --ks-pass pass:文件中的密码 `
  --out AgentDrive-1.0.10.apk app\build\outputs\apk\release\app-release-unsigned.apk

# 校验（应输出与 assetlinks.json 一致的 SHA256 指纹）
& "C:\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs AgentDrive-1.0.10.apk
~~~

## 关键约定

| 项 | 值 |
|----|----|
| 包名 | top.rainaki.agentdrive |
| 签名指纹 | 8ef4635c9f505891d0c9251ed229e610c7a5d7799bd7eb73311df1de4fe90a94（服务器现有 keystore） |
| 目标地址 | https://home.rainaki.top:13311/（manifest meta-data） |
| versionName | 1.0.10（TWA 要求 ≥6 字符） |

> ⚠️ 若换了新 keystore，必须同步更新两处指纹：
> frontend/public/.well-known/assetlinks.json + app/src/main/res/values/strings.xml，并重新构建前端产物。