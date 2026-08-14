package top.rainaki.agentdrive;

import android.content.Context;
import android.content.SharedPreferences;

/** 服务器连接配置存储：扫码结果与相册同步设置都在 SharedPreferences。 */
public final class ServerConfigStore {

    private static final String PREFS = "agent_drive";

    private ServerConfigStore() {
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    /** 上次同步截至时间（epoch 秒）。 */
    public static long getLastSyncAt(Context ctx) {
        return prefs(ctx).getLong("sync_last_at", 0L);
    }

    public static void setLastSyncAt(Context ctx, long ts) {
        prefs(ctx).edit().putLong("sync_last_at", ts).apply();
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
