package top.rainaki.agentdrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * 服务器连接配置存储：扫码结果、设备令牌与相册同步设置全部加密存储。
 *
 * 安全：AndroidX Security Crypto（AES256-GCM，密钥在 Keystore 硬件级保护）；
 * 设备令牌不再明文落盘；旧版明文数据一次性迁移后清空。
 */
public final class ServerConfigStore {

    private static final String TAG = "ServerConfigStore";
    private static final String PREFS = "agent_drive";
    private static volatile SharedPreferences cached;

    private ServerConfigStore() {
    }

    public static SharedPreferences prefs(Context ctx) {
        SharedPreferences p = cached;
        if (p != null) {
            return p;
        }
        synchronized (ServerConfigStore.class) {
            p = cached;
            if (p != null) {
                return p;
            }
            try {
                MasterKey masterKey = new MasterKey.Builder(ctx)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                p = EncryptedSharedPreferences.create(
                        ctx, PREFS, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (Exception e) {
                // Keystore 不可用（异常固件/极老设备）：降级明文保证功能可用
                Log.w(TAG, "加密存储不可用，降级明文 SharedPreferences", e);
                p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
            migrateLegacy(ctx, p);
            cached = p;
            return p;
        }
    }

    /**
     * 旧版明文 prefs → 加密 prefs 一次性迁移（幂等）。
     *
     * EncryptedSharedPreferences 与旧版明文 prefs 使用同一个底层文件时，底层 map
     * 会同时包含 AndroidX 的 keyset 条目和旧明文条目。只迁移应用自己的键，并在
     * 加密提交成功后逐项清理旧键，不能对底层文件调用 clear()，否则会把新 keyset 一起删掉。
     */
    private static void migrateLegacy(Context ctx, SharedPreferences encrypted) {
        if (!(encrypted instanceof EncryptedSharedPreferences)) {
            // 降级到明文 SharedPreferences 时，旧值已经可直接使用，不做迁移或清理。
            return;
        }
        SharedPreferences legacy = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, ?> all = legacy.getAll();
        if (all.isEmpty()) {
            return;
        }

        SharedPreferences.Editor ed = encrypted.edit();
        List<String> migratedKeys = new ArrayList<>();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (!isLegacyConfigKey(key)) {
                // 跳过 AndroidX 内部 keyset 及已经加密的底层键。
                continue;
            }
            Object v = e.getValue();
            boolean copied = false;
            if (v instanceof String) {
                ed.putString(key, (String) v);
                copied = true;
            } else if (v instanceof Boolean) {
                ed.putBoolean(key, (Boolean) v);
                copied = true;
            } else if (v instanceof Integer) {
                ed.putInt(key, (Integer) v);
                copied = true;
            } else if (v instanceof Long) {
                ed.putLong(key, (Long) v);
                copied = true;
            } else if (v instanceof Float) {
                ed.putFloat(key, (Float) v);
                copied = true;
            } else if (v instanceof Set<?>) {
                Set<String> strings = new HashSet<>();
                boolean valid = true;
                for (Object item : (Set<?>) v) {
                    if (!(item instanceof String)) {
                        valid = false;
                        break;
                    }
                    strings.add((String) item);
                }
                if (valid) {
                    ed.putStringSet(key, strings);
                    copied = true;
                }
            }
            if (copied) {
                migratedKeys.add(key);
            }
        }
        if (migratedKeys.isEmpty() || !ed.commit()) {
            Log.w(TAG, "旧版配置迁移写入失败");
            return;
        }

        // 只删除旧的明文键，保留加密值和 AndroidX keyset。
        SharedPreferences.Editor cleanup = legacy.edit();
        for (String key : migratedKeys) {
            cleanup.remove(key);
        }
        if (!cleanup.commit()) {
            Log.w(TAG, "旧版配置明文清理失败");
        }
    }

    private static boolean isLegacyConfigKey(String key) {
        switch (key) {
            case "server":
            case "device_id":
            case "device_token":
            case "sync_enabled":
            case "sync_wifi_only":
            case "sync_interval_bits":
            case "sync_folder":
            case "sync_last_at":
            case "sync_pending_second":
            case "sync_pending_max_id":
            case "sync_last_count":
            case "sync_last_error":
                return true;
            default:
                return false;
        }
    }

    // ---- 服务器连接（扫码） ----
    public static String getServer(Context ctx) {
        return prefs(ctx).getString("server", null);
    }

    public static void setServer(Context ctx, String server) {
        prefs(ctx).edit().putString("server", server).apply();
    }

    public static boolean isConfigured(Context ctx) {
        String s = getServer(ctx);
        return s != null && !s.trim().isEmpty();
    }

    // ---- 设备身份（首次生成，持久保存） ----
    public static String getDeviceId(Context ctx) {
        String id = prefs(ctx).getString("device_id", null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            prefs(ctx).edit().putString("device_id", id).apply();
        }
        return id;
    }

    // ---- 设备令牌（登录后由服务器颁发，后台同步鉴权用） ----
    public static String getDeviceToken(Context ctx) {
        return prefs(ctx).getString("device_token", null);
    }

    public static void setDeviceToken(Context ctx, String token) {
        prefs(ctx).edit().putString("device_token", token).apply();
    }

    public static void clearDeviceToken(Context ctx) {
        prefs(ctx).edit().remove("device_token").apply();
    }

    // ---- 相册同步设置 ----
    public static boolean isSyncEnabled(Context ctx) {
        return prefs(ctx).getBoolean("sync_enabled", false);
    }

    public static void setSyncEnabled(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("sync_enabled", v).apply();
    }

    public static boolean isWifiOnly(Context ctx) {
        return prefs(ctx).getBoolean("sync_wifi_only", true);
    }

    public static void setWifiOnly(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("sync_wifi_only", v).apply();
    }

    /** 同步周期（小时）。 */
    public static double getIntervalHours(Context ctx) {
        return Double.longBitsToDouble(prefs(ctx).getLong("sync_interval_bits", Double.doubleToLongBits(6.0)));
    }

    public static void setIntervalHours(Context ctx, double hours) {
        prefs(ctx).edit().putLong("sync_interval_bits", Double.doubleToLongBits(hours)).apply();
    }

    public static String getTargetFolder(Context ctx) {
        return prefs(ctx).getString("sync_folder", "相册同步");
    }

    public static void setTargetFolder(Context ctx, String folder) {
        prefs(ctx).edit().putString("sync_folder", folder).apply();
    }

    /** 上次同步截至时间（epoch 秒）——只推进到「整秒全部成功」的秒。 */
    public static long getLastSyncAt(Context ctx) {
        return prefs(ctx).getLong("sync_last_at", 0L);
    }

    public static void setLastSyncAt(Context ctx, long ts) {
        prefs(ctx).edit().putLong("sync_last_at", ts).apply();
    }

    /** 未完成秒（-1 = 无）：该秒内有失败/未取完的照片，下轮从 _ID 水位续传。 */
    public static long getPendingSecond(Context ctx) {
        return prefs(ctx).getLong("sync_pending_second", -1L);
    }

    public static void setPendingSecond(Context ctx, long ts) {
        prefs(ctx).edit().putLong("sync_pending_second", ts).apply();
    }

    /** 未完成秒内已成功上传的最大 _ID（0 = 无）。 */
    public static long getPendingMaxId(Context ctx) {
        return prefs(ctx).getLong("sync_pending_max_id", 0L);
    }

    public static void setPendingMaxId(Context ctx, long id) {
        prefs(ctx).edit().putLong("sync_pending_max_id", id).apply();
    }

    public static void clearPending(Context ctx) {
        prefs(ctx).edit().remove("sync_pending_second").remove("sync_pending_max_id").apply();
    }

    public static int getLastCount(Context ctx) {
        return prefs(ctx).getInt("sync_last_count", 0);
    }

    public static void setLastCount(Context ctx, int n) {
        prefs(ctx).edit().putInt("sync_last_count", n).apply();
    }

    public static String getLastError(Context ctx) {
        return prefs(ctx).getString("sync_last_error", null);
    }

    public static void setLastError(Context ctx, String e) {
        prefs(ctx).edit().putString("sync_last_error", e).apply();
    }
}
