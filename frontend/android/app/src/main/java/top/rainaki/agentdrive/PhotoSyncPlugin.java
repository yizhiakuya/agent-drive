package top.rainaki.agentdrive;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

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

/** 相册自动同步桥：配置 / 状态 / 立即同步 / 权限申请。 */
@CapacitorPlugin(name = "PhotoSync")
public class PhotoSyncPlugin extends Plugin {

    private static PhotoSyncPlugin instance;
    private static PluginCall pendingPermissionCall;

    @Override
    public void load() {
        instance = this;
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
        call.resolve(status());
    }

    @PluginMethod
    public void configure(PluginCall call) {
        Context ctx = getContext();
        if (call.hasOption("enabled")) {
            ServerConfigStore.setSyncEnabled(ctx, call.getBoolean("enabled", false));
        }
        if (call.hasOption("wifiOnly")) {
            ServerConfigStore.setWifiOnly(ctx, call.getBoolean("wifiOnly", true));
        }
        if (call.hasOption("intervalHours")) {
            ServerConfigStore.setIntervalHours(ctx, call.getDouble("intervalHours", 6.0));
        }
        if (call.hasOption("targetFolder")) {
            String f = call.getString("targetFolder");
            if (f != null && !f.trim().isEmpty()) {
                ServerConfigStore.setTargetFolder(ctx, f.trim());
            }
        }
        PhotoSyncScheduler.ensureScheduled(ctx);
        call.resolve(status());
    }

    @PluginMethod
    public void syncNow(PluginCall call) {
        if (!ServerConfigStore.isConfigured(getContext())) {
            call.reject("未配置服务器：请先扫码连接");
            return;
        }
        if (!hasMediaPermission()) {
            call.reject("缺少相册权限");
            return;
        }
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(PhotoSyncWorker.class).build();
        WorkManager.getInstance(getContext()).enqueueUniqueWork("photo_sync_now", ExistingWorkPolicy.KEEP, req);
        JSObject ret = new JSObject();
        ret.put("started", true);
        call.resolve(ret);
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
        pendingPermissionCall = call;
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
        ret.put("enabled", ServerConfigStore.isSyncEnabled(ctx));
        ret.put("wifiOnly", ServerConfigStore.isWifiOnly(ctx));
        ret.put("intervalHours", ServerConfigStore.getIntervalHours(ctx));
        ret.put("targetFolder", ServerConfigStore.getTargetFolder(ctx));
        long last = ServerConfigStore.getLastSyncAt(ctx);
        ret.put("lastSyncAt", last == 0 ? JSONObject.NULL : last);
        ret.put("lastSyncedCount", ServerConfigStore.getLastCount(ctx));
        ret.put("lastError", ServerConfigStore.getLastError(ctx));
        // 实时进度（与 syncProgress 事件同构）
        ret.put("running", SyncEngine.running);
        ret.put("phase", SyncEngine.phase);
        ret.put("currentFile", SyncEngine.currentFile);
        ret.put("uploaded", SyncEngine.uploaded);
        ret.put("total", SyncEngine.total);
        return ret;
    }
}
