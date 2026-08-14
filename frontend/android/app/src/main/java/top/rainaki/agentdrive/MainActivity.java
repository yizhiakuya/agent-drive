package top.rainaki.agentdrive;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/** 原生壳入口：首启未配置服务器 → 扫码连接页；已配置 → 加载本地 web 资源。 */
public class MainActivity extends BridgeActivity {

    public static final int REQ_SCAN = 1001;
    public static final int REQ_PHOTO_PERM = 1002;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 首启：没有服务器地址 → 原生扫码页（扫码保存后跳回这里）
        if (!ServerConfigStore.isConfigured(getApplicationContext())) {
            Intent intent = new Intent(this, ScanActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        registerPlugin(ServerConfigPlugin.class);
        registerPlugin(PhotoSyncPlugin.class);

        // 同步已启用时确保后台周期任务在册（App 更新/重启后仍有效）
        PhotoSyncWorker.ensureChannel(getApplicationContext());
        PhotoSyncScheduler.ensureScheduled(getApplicationContext());
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
