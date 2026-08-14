package top.rainaki.agentdrive;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 设备登记/心跳：App 启动、回前台、同步完成时 POST /api/v1/devices/register（尽力而为）。 */
public final class DeviceRegistrar {

    private DeviceRegistrar() {
    }

    /** 异步上报，失败静默（下次心跳再试）。 */
    public static void register(final Context ctx, final boolean withSyncState) {
        final String server = ServerConfigStore.getServer(ctx);
        if (server == null || server.trim().isEmpty()) {
            return;
        }
        final String deviceId = ServerConfigStore.getDeviceId(ctx);
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("device_id", deviceId);
                payload.put("name", Build.MANUFACTURER + " " + Build.MODEL);
                payload.put("model", Build.MODEL);
                payload.put("platform", "android");
                payload.put("app_version", BuildConfig.VERSION_NAME);
                if (withSyncState) {
                    JSONObject sync = new JSONObject();
                    sync.put("enabled", ServerConfigStore.isSyncEnabled(ctx));
                    sync.put("wifi_only", ServerConfigStore.isWifiOnly(ctx));
                    sync.put("interval_hours", ServerConfigStore.getIntervalHours(ctx));
                    long last = ServerConfigStore.getLastSyncAt(ctx);
                    sync.put("last_sync_at", last == 0 ? JSONObject.NULL : last);
                    sync.put("last_synced_count", ServerConfigStore.getLastCount(ctx));
                    String err = ServerConfigStore.getLastError(ctx);
                    sync.put("last_error", err == null ? JSONObject.NULL : err);
                    payload.put("sync", sync);
                }
                String url = server.trim().replaceAll("/+$", "") + "/api/v1/devices/register";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                String deviceToken = ServerConfigStore.getDeviceToken(ctx);
                if (deviceToken != null && !deviceToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + deviceToken);
                }
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {
                // 心跳尽力而为
            }
        }).start();
    }
}
