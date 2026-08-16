package top.rainaki.agentdrive;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.getcapacitor.BridgeActivity;

/** 原生壳入口：首启未配置服务器 → 扫码连接页；已配置 → 加载本地 web 资源。 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "MainActivity";
    public static final int REQ_SCAN = 1001;
    public static final int REQ_PHOTO_PERM = 1002;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Capacitor 要求：插件必须在 super.onCreate() 之前注册，否则 web 端调用拿不到原生实现
        registerPlugin(ServerConfigPlugin.class);
        registerPlugin(PhotoSyncPlugin.class);

        super.onCreate(savedInstanceState);

        // 加密配置不可用时失败关闭，绝不把服务器地址/令牌降级写进明文 prefs。
        try {
            ServerConfigStore.prefs(getApplicationContext());
        } catch (RuntimeException e) {
            new AlertDialog.Builder(this)
                    .setTitle("安全配置存储不可用")
                    .setMessage("App 已停止以保护设备令牌，不会降级到明文存储。请重启设备后重试；如持续出现，请清除 App 存储并重新扫码。")
                    .setCancelable(false)
                    .setPositiveButton("退出", (dialog, which) -> finish())
                    .show();
            return;
        }

        // 首启：没有服务器地址 → 原生扫码页（扫码保存后跳回这里）
        if (!ServerConfigStore.isConfigured(getApplicationContext())) {
            Intent intent = new Intent(this, ScanActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // 同步已启用时确保后台周期任务在册（App 更新/重启后仍有效）
        PhotoSyncWorker.ensureChannel(getApplicationContext());
        try {
            PhotoSyncScheduler.ensureScheduled(getApplicationContext());
        } catch (RuntimeException e) {
            // 持久配置仍是期望状态；本次启动不崩溃，后续启动/重新 configure 会再收敛。
            Log.e(TAG, "后台同步调度失败，将在下次启动重试", e);
        }

        // 设备登记（web 设备列表可见）；回前台心跳由前端 visibilitychange 触发 heartbeat()
        DeviceRegistrar.register(getApplicationContext(), true);

        // 事件驱动同步：相册新增照片 → 秒级排一次快速同步（周期任务仍作兜底）
        registerMediaObserver();
    }

    /** MediaStore 观察者：新照片落库触发快速同步（1 秒防抖：连拍/批量导入合并成一次）。 */
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private ContentObserver mediaObserver;
    private final Runnable quickSyncRunnable = new Runnable() {
        @Override
        public void run() {
            PhotoSyncScheduler.enqueueQuickSync(getApplicationContext());
        }
    };

    private void registerMediaObserver() {
        if (mediaObserver != null) {
            return;
        }
        ContentObserver observer = new ContentObserver(debounceHandler) {
            @Override
            public void onChange(boolean selfChange) {
                debounceHandler.removeCallbacks(quickSyncRunnable);
                debounceHandler.postDelayed(quickSyncRunnable, 1000);
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
            mediaObserver = observer;
        } catch (Exception ignored) {
            // 注册失败不影响周期同步；未写字段，后续 Activity 可重新尝试。
        }
    }

    @Override
    public void onDestroy() {
        debounceHandler.removeCallbacks(quickSyncRunnable);
        ContentObserver observer = mediaObserver;
        mediaObserver = null;
        if (observer != null) {
            try {
                getContentResolver().unregisterContentObserver(observer);
            } catch (Exception ignored) {
                // Activity 销毁继续进行；resolver 已注销/不可用均无需重试。
            }
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN && resultCode == RESULT_OK) {
            // 重新扫码成功：服务器地址已更新，重载 web 界面
            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().reload();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PHOTO_PERM) {
            // 只按图片读取权限判定：通知权限被拒不算失败（同步照常工作，只是没通知）
            boolean photoGranted = true;
            boolean hasPhotoPerm = false;
            for (int i = 0; i < permissions.length; i++) {
                String p = permissions[i];
                if (Manifest.permission.READ_MEDIA_IMAGES.equals(p)
                        || Manifest.permission.READ_EXTERNAL_STORAGE.equals(p)) {
                    hasPhotoPerm = true;
                    if (grantResults.length <= i || grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        photoGranted = false;
                    }
                }
            }
            PhotoSyncPlugin.onPermissionResult(!hasPhotoPerm || photoGranted);
        }
    }
}
