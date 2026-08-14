package top.rainaki.agentdrive;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import com.getcapacitor.BridgeActivity;

/** 原生壳入口：首启未配置服务器 → 扫码连接页；已配置 → 加载本地 web 资源。 */
public class MainActivity extends BridgeActivity {

    public static final int REQ_SCAN = 1001;
    public static final int REQ_PHOTO_PERM = 1002;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Capacitor 要求：插件必须在 super.onCreate() 之前注册，否则 web 端调用拿不到原生实现
        registerPlugin(ServerConfigPlugin.class);
        registerPlugin(PhotoSyncPlugin.class);

        super.onCreate(savedInstanceState);

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
        PhotoSyncScheduler.ensureScheduled(getApplicationContext());

        // 设备登记（web 设备列表可见）；回前台心跳由前端 visibilitychange 触发 heartbeat()
        DeviceRegistrar.register(getApplicationContext(), true);

        // 事件驱动同步：相册新增照片 → 秒级排一次快速同步（周期任务仍作兜底）
        registerMediaObserver();
    }

    /** MediaStore 观察者：新照片落库立即触发快速同步（遵守仅 Wi-Fi 约束）。 */
    private void registerMediaObserver() {
        try {
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    PhotoSyncScheduler.enqueueQuickSync(getApplicationContext());
                }
            };
            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
        } catch (Exception ignored) {
            // 注册失败不影响周期同步
        }
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
            boolean granted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            PhotoSyncPlugin.onPermissionResult(granted);
        }
    }
}
