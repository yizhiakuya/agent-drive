package top.rainaki.agentdrive;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.net.Uri;

import androidx.core.app.NotificationManagerCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** 相册自动同步桥：配置 / 状态 / 立即同步 / 权限申请。 */
@CapacitorPlugin(name = "PhotoSync")
public class PhotoSyncPlugin extends Plugin {

    private static final ExecutorService CONFIGURE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "photo-sync-configure");
        thread.setDaemon(true);
        return thread;
    });
    private static PhotoSyncPlugin instance;
    private static PluginCall pendingPermissionCall;

    @Override
    public void load() {
        instance = this;
    }

    @Override
    protected void handleOnDestroy() {
        // Activity 销毁后不能再解析挂起的权限回调，否则旧 PluginCall 会失效。
        PluginCall pending = pendingPermissionCall;
        pendingPermissionCall = null;
        if (pending != null) {
            pending.reject("Activity 已销毁，权限请求已取消");
        }
        super.handleOnDestroy();
    }

    /** 供 SyncEngine 广播进度（WebView 打开时 JS 实时收到） */
    public static void emitProgress(JSObject data) {
        PhotoSyncPlugin p = instance;
        if (p != null) {
            p.notifyListeners("syncProgress", data);
        }
    }

    static void onPermissionResult(boolean granted) {
        if (pendingPermissionCall != null) {
            JSObject ret = new JSObject();
            ret.put("granted", granted);
            pendingPermissionCall.resolve(ret);
            pendingPermissionCall = null;
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        try {
            call.resolve(status());
        } catch (RuntimeException e) {
            call.reject("安全配置存储不可用：" + e.getMessage());
        }
    }

    @PluginMethod
    public void configure(PluginCall call) {
        Context ctx = getContext();
        Boolean enabled = call.hasOption("enabled") ? call.getBoolean("enabled", false) : null;
        Boolean wifiOnly = call.hasOption("wifiOnly") ? call.getBoolean("wifiOnly", true) : null;
        Double interval = call.hasOption("intervalHours") ? call.getDouble("intervalHours", 6.0) : null;
        String folder = call.hasOption("targetFolder") ? call.getString("targetFolder") : null;
        try {
            CONFIGURE_EXECUTOR.execute(() -> {
                try {
                    // 专用单线程从 commit 到调度结果全程串行，且不阻塞桥接/UI 线程。
                    ServerConfigStore.updateSyncSettings(ctx, enabled, wifiOnly, interval, folder);
                } catch (RuntimeException e) {
                    call.reject("同步配置保存失败：" + e.getMessage());
                    return;
                }
                try {
                    PhotoSyncScheduler.ensureScheduledAndWait(ctx);
                } catch (RuntimeException scheduleError) {
                    // WorkManager 不是事务 API，不能假装可回滚其未知副作用。保留已提交的
                    // 期望状态，App 下次启动会再次 ensureScheduled 幂等收敛。
                    call.reject("同步配置已保存，但调度失败；下次启动将重试：" + scheduleError.getMessage());
                    return;
                }
                try {
                    call.resolve(status());
                } catch (RuntimeException e) {
                    call.reject("同步配置已保存，但读取状态失败：" + e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            call.reject("同步配置任务无法启动：" + e.getMessage());
        }
    }

    @PluginMethod
    public void syncNow(PluginCall call) {
        try {
            if (!ServerConfigStore.isConfigured(getContext())) {
                call.reject("未配置服务器：请先扫码连接");
                return;
            }
            if (!ServerConfigStore.isSyncEnabled(getContext())) {
                JSObject ret = new JSObject();
                ret.put("started", false);
                ret.put("reason", "同步未启用");
                call.resolve(ret);
                return;
            }
            if (!hasMediaPermission()) {
                call.reject("缺少相册权限");
                return;
            }
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(PhotoSyncWorker.class)
                    .setConstraints(PhotoSyncScheduler.syncConstraints(getContext()))
                    .build();
            WorkManager.getInstance(getContext()).enqueueUniqueWork(PhotoSyncScheduler.UNIQUE_QUICK,
                    ExistingWorkPolicy.KEEP, req);
            JSObject ret = new JSObject();
            ret.put("started", true);
            call.resolve(ret);
        } catch (RuntimeException e) {
            call.reject("安全配置存储不可用：" + e.getMessage());
        }
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (hasMediaPermission()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        if (getActivity() == null) {
            call.reject("activity 不可用");
            return;
        }
        PluginCall previous = pendingPermissionCall;
        pendingPermissionCall = call;
        if (previous != null) {
            previous.reject("权限请求被新的请求取代");
        }
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.READ_MEDIA_IMAGES");
            perms.add("android.permission.POST_NOTIFICATIONS");
        } else {
            perms.add("android.permission.READ_EXTERNAL_STORAGE");
        }
        String[] arr = perms.toArray(new String[0]);
        getActivity().runOnUiThread(() -> getActivity().requestPermissions(arr, MainActivity.REQ_PHOTO_PERM));
    }

    /** 打开当前 App 的系统通知设置，供同步诊断在通知被拒时提供直达入口。 */
    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        if (getActivity() == null) {
            call.reject("activity 不可用");
            return;
        }
        try {
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            } else {
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            }
            getActivity().startActivity(intent);
            JSObject result = new JSObject();
            result.put("opened", true);
            call.resolve(result);
        } catch (RuntimeException error) {
            call.reject("无法打开通知设置：" + error.getMessage());
        }
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return getContext().checkSelfPermission("android.permission.READ_MEDIA_IMAGES")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return getContext().checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")
                == PackageManager.PERMISSION_GRANTED;
    }

    private JSObject status() {
        Context ctx = getContext();
        JSObject ret = new JSObject();
        ret.put("configured", ServerConfigStore.isConfigured(ctx));
        ret.put("permissionGranted", hasMediaPermission());
        ret.put("enabled", ServerConfigStore.isSyncEnabled(ctx));
        ret.put("wifiOnly", ServerConfigStore.isWifiOnly(ctx));
        ret.put("intervalHours", ServerConfigStore.getIntervalHours(ctx));
        ret.put("targetFolder", ServerConfigStore.getTargetFolder(ctx));
        long last = ServerConfigStore.getLastSyncAt(ctx);
        ret.put("lastSyncAt", last == 0 ? JSONObject.NULL : last);
        ret.put("lastSyncedCount", ServerConfigStore.getLastCount(ctx));
        ret.put("lastError", ServerConfigStore.getLastError(ctx));
        ret.put("lastScanned", ServerConfigStore.getLastScanned(ctx));
        ret.put("lastUploaded", ServerConfigStore.getLastUploaded(ctx));
        ret.put("lastDeduped", ServerConfigStore.getLastDeduped(ctx));
        ret.put("lastSkipped", ServerConfigStore.getLastSkipped(ctx));
        ret.put("lastFailed", ServerConfigStore.getLastFailed(ctx));
        ret.put("lastRetryable", ServerConfigStore.getLastRetryable(ctx));
        ret.put("notificationsEnabled", notificationsEnabled(ctx));
        ret.put("lastNotification", ServerConfigStore.getLastNotification(ctx));
        // 实时进度（与 syncProgress 事件同构）
        ret.put("running", SyncEngine.running);
        ret.put("phase", SyncEngine.phase);
        ret.put("currentFile", SyncEngine.currentFile);
        ret.put("uploaded", SyncEngine.uploaded);
        ret.put("total", SyncEngine.total);
        return ret;
    }

    private static boolean notificationsEnabled(Context ctx) {
        try {
            return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
