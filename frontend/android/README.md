# Android 原生壳（Capacitor）

由 `npx cap add android` 生成 + 以下手写原生代码（Capacitor 官方插件机制）：

```
app/src/main/java/top/rainaki/agentdrive/
├── MainActivity.java        # 入口：首启未配置 → 扫码页；注册插件；后台任务在册
├── ScanActivity.java        # 扫码连接页（zxing）：解析 agentdrive://connect?server=...
├── ServerConfigStore.java   # EncryptedSharedPreferences：服务器地址、设备令牌和同步设置（兼容旧版迁移）
├── ServerConfigPlugin.java  # JS 桥：getServer/setServer/rescan
├── PhotoSyncPlugin.java     # JS 桥：相册同步配置/状态/立即同步/权限
├── PhotoSyncScheduler.java  # WorkManager 周期任务调度（网络/低电量约束）
├── PhotoSyncWorker.java     # 后台同步任务 + 完成通知
└── SyncEngine.java          # MediaStore 扫描 → multipart 上传 /api/v1/files/upload
```

## 构建

```bash
cd ../..          # frontend/
npm run build     # 静态导出 out/
npx cap sync android
cd android
gradlew.bat assembleRelease   # 或 C:\Android\gradle-8.14.3\bin\gradle.bat assembleRelease
```

签名：`keystore.properties`（不入 git，模板见 `.template`）；keystore 放 `D:\ds\agent-drive-keystore\`。
完整方案见 `docs/android.md`。
