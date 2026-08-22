package top.rainaki.agentdrive;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
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
    private static final String LEGACY_PREFS = "agent_drive";
    private static final String SECURE_PREFS = "agent_drive_secure";
    private static final String KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__";
    private static final String VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__";
    private static final String[] CONFIG_KEYS = {
            "server", "device_id", "device_token", "sync_enabled", "sync_wifi_only",
            "sync_interval_bits", "sync_folder", "sync_last_at", "sync_pending_second",
            "sync_pending_max_id", "sync_last_count", "sync_last_error", "sync_last_scanned",
            "sync_last_uploaded", "sync_last_deduped", "sync_last_skipped", "sync_last_failed",
            "sync_last_retryable", "sync_last_notification"
    };
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
                Context app = ctx.getApplicationContext();
                MasterKey masterKey = new MasterKey.Builder(app)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                p = createEncrypted(app, SECURE_PREFS, masterKey);
                migrateLegacy(app, p, masterKey);
            } catch (Exception e) {
                // 绝不能把 token/config 静默写入明文；调用方必须明确收到失败。
                RuntimeException failure = new IllegalStateException("安全配置存储初始化失败", e);
                Log.e(TAG, failure.getMessage(), e);
                throw failure;
            }
            cached = p;
            return p;
        }
    }

    private static SharedPreferences createEncrypted(Context ctx, String name, MasterKey masterKey)
            throws Exception {
        return EncryptedSharedPreferences.create(
                ctx, name, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /**
     * 将旧 ``agent_drive`` 文件中的明文或 1.0.27 同文件密文迁到独立加密文件。
     *
     * 新文件与 legacy 文件彻底分离，避免 AndroidX keyset/密文键和旧明文业务键混存。
     * legacy 同键密文是 1.0.27 持续写入的现行值，优先于更老明文/清理残留；独立新
     * 密文若与 legacy 现行值冲突则保留双方并失败关闭。写入成功后才逐键清理旧业务
     * 数据，keyset 永远不 clear；清理失败时下次启动保留新值并幂等重试。
     */
    private static void migrateLegacy(Context ctx, SharedPreferences secure, MasterKey masterKey)
            throws Exception {
        if (!(secure instanceof EncryptedSharedPreferences)) {
            throw new IllegalStateException("安全配置存储不是加密实现");
        }
        SharedPreferences legacyRaw = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        Map<String, ?> rawValues = legacyRaw.getAll();
        boolean hasKeyKeyset = rawValues.containsKey(KEY_KEYSET);
        boolean hasValueKeyset = rawValues.containsKey(VALUE_KEYSET);
        if (hasKeyKeyset != hasValueKeyset) {
            throw new IllegalStateException("旧版加密 keyset 不完整");
        }
        SharedPreferences legacyEncrypted = hasKeyKeyset
                ? createEncrypted(ctx, LEGACY_PREFS, masterKey)
                : null;

        migrateSources(secure, legacyRaw, legacyEncrypted, rawValues);
    }

    /** 纯 SharedPreferences 迁移事务，独立于 Keystore，便于 JVM 回归测试。 */
    static void migrateSources(
            SharedPreferences secure,
            SharedPreferences legacyRaw,
            SharedPreferences legacyEncrypted,
            Map<String, ?> rawValues) {
        Map<String, Object> sourceValues = new HashMap<>();
        Set<String> encryptedKeys = new HashSet<>();
        Set<String> rawKeys = new HashSet<>();
        for (String key : CONFIG_KEYS) {
            boolean hasEncrypted = legacyEncrypted != null && legacyEncrypted.contains(key);
            boolean hasRaw = rawValues.containsKey(key);
            Object encryptedValue = hasEncrypted ? readPreferenceValue(legacyEncrypted, key) : null;
            Object rawValue = hasRaw && !hasEncrypted
                    ? normalizeRawLegacyValue(key, rawValues.get(key)) : null;
            if (hasEncrypted) {
                // 1.0.27 持续写同文件密文；同名明文只可能来自更老版本或清理失败残留。
                // 因此密文是该 legacy 文件内的现行值，即使它与残留明文不同也应优先。
                sourceValues.put(key, encryptedValue);
                encryptedKeys.add(key);
            } else if (hasRaw) {
                sourceValues.put(key, rawValue);
            }
            if (hasRaw) rawKeys.add(key);
        }

        SharedPreferences.Editor destination = secure.edit();
        boolean destinationChanged = false;
        for (Map.Entry<String, Object> entry : sourceValues.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();
            if (secure.contains(key)) {
                Object currentValue = readPreferenceValue(secure, key);
                if (!Objects.equals(currentValue, sourceValue)) {
                    // 无可靠版本号时不能猜哪个更新；保留两份并失败关闭，避免令牌/检查点丢失。
                    throw new IllegalStateException("新旧加密配置冲突，拒绝删除任一副本: " + key);
                }
                continue;
            }
            putTypedValue(destination, key, sourceValue);
            destinationChanged = true;
        }
        if (destinationChanged && !destination.commit()) {
            throw new IllegalStateException("旧版配置迁移到独立加密存储失败");
        }

        // 走到这里说明每个旧值都已成功复制，或与新值逐项相等；此时才允许清理旧源。
        // 明文必须先清：若其 commit 失败，保留的 1.0.27 密文仍可在下次启动证明现行值，
        // 不会让更老明文残留单独与新存储冲突。
        if (!rawKeys.isEmpty()) {
            SharedPreferences.Editor cleanupRaw = legacyRaw.edit();
            for (String key : rawKeys) cleanupRaw.remove(key);
            if (!cleanupRaw.commit()) {
                throw new IllegalStateException("旧版明文配置清理失败");
            }
        }
        if (legacyEncrypted != null && !encryptedKeys.isEmpty()) {
            SharedPreferences.Editor cleanupEncrypted = legacyEncrypted.edit();
            for (String key : encryptedKeys) cleanupEncrypted.remove(key);
            if (!cleanupEncrypted.commit()) {
                throw new IllegalStateException("旧版加密配置清理失败");
            }
        }
    }

    private static Object readPreferenceValue(SharedPreferences source, String key) {
        switch (key) {
            case "sync_last_error":
                return source.getString(key, null); // null 等价于“当前无同步错误”。
            case "server":
            case "device_id":
            case "device_token":
            case "sync_folder":
                String stringValue = source.getString(key, null);
                if (stringValue == null) {
                    throw new IllegalStateException("旧版加密字段损坏: " + key);
                }
                return stringValue;
            case "sync_enabled":
            case "sync_wifi_only":
                return source.getBoolean(key, false);
            case "sync_interval_bits":
            case "sync_last_at":
            case "sync_pending_second":
            case "sync_pending_max_id":
                return source.getLong(key, 0L);
            case "sync_last_count":
                return source.getInt(key, 0);
            case "sync_last_scanned":
            case "sync_last_uploaded":
            case "sync_last_deduped":
            case "sync_last_skipped":
            case "sync_last_failed":
            case "sync_last_retryable":
                return source.getInt(key, 0);
            case "sync_last_notification":
                return source.getBoolean(key, false);
            default:
                throw new IllegalArgumentException("未知配置键: " + key);
        }
    }

    private static Object normalizeRawLegacyValue(String key, Object value) {
        switch (key) {
            case "sync_last_error":
                if (value == null || value instanceof String) return value;
                break;
            case "server":
            case "device_id":
            case "device_token":
            case "sync_folder":
                if (value instanceof String) return value;
                break;
            case "sync_enabled":
            case "sync_wifi_only":
                if (value instanceof Boolean) return value;
                break;
            case "sync_interval_bits":
            case "sync_last_at":
            case "sync_pending_second":
            case "sync_pending_max_id":
                if (value instanceof Long) return value;
                break;
            case "sync_last_count":
                if (value instanceof Integer) return value;
                break;
            case "sync_last_scanned":
            case "sync_last_uploaded":
            case "sync_last_deduped":
            case "sync_last_skipped":
            case "sync_last_failed":
            case "sync_last_retryable":
                if (value instanceof Integer) return value;
                break;
            case "sync_last_notification":
                if (value instanceof Boolean) return value;
                break;
            default:
                throw new IllegalArgumentException("未知配置键: " + key);
        }
        throw new IllegalStateException("旧版明文字段类型损坏: " + key);
    }

    private static void putTypedValue(SharedPreferences.Editor destination, String key, Object value) {
        switch (key) {
            case "sync_last_error":
                if (value == null) destination.remove(key);
                else destination.putString(key, (String) value);
                return;
            case "server":
            case "device_id":
            case "device_token":
            case "sync_folder":
                destination.putString(key, (String) value);
                return;
            case "sync_enabled":
            case "sync_wifi_only":
                destination.putBoolean(key, (Boolean) value);
                return;
            case "sync_interval_bits":
            case "sync_last_at":
            case "sync_pending_second":
            case "sync_pending_max_id":
                destination.putLong(key, (Long) value);
                return;
            case "sync_last_count":
                destination.putInt(key, (Integer) value);
                return;
            case "sync_last_scanned":
            case "sync_last_uploaded":
            case "sync_last_deduped":
            case "sync_last_skipped":
            case "sync_last_failed":
            case "sync_last_retryable":
                destination.putInt(key, (Integer) value);
                return;
            case "sync_last_notification":
                destination.putBoolean(key, (Boolean) value);
                return;
            default:
                throw new IllegalArgumentException("未知配置键: " + key);
        }
    }

    private static void commitOrThrow(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("安全配置写入失败");
        }
    }

    // ---- 服务器连接（扫码） ----
    public static String getServer(Context ctx) {
        return prefs(ctx).getString("server", null);
    }

    public static void setServer(Context ctx, String server) {
        updateServer(prefs(ctx), server);
    }

    /** 扫码成功后原子保存连接地址与设备令牌，避免只写入一半。 */
    public static void setConnection(Context ctx, String server, String token) {
        updateConnection(prefs(ctx), server, token);
    }

    /**
     * 保存服务器地址；地址切换时同时清空旧服务器的相册同步检查点。
     *
     * <p>检查点是服务器语义的一部分，跨服务器复用会导致跳过新服务器上的照片；
     * 清理与地址写入共用一个 commit，避免只更新一半。</p>
     */
    static void updateServer(SharedPreferences p, String server) {
        if (server == null || server.trim().isEmpty()) {
            throw new IllegalArgumentException("服务器地址不能为空");
        }
        String normalized = server.trim();
        String previous = p.getString("server", null);
        SharedPreferences.Editor editor = p.edit().putString("server", normalized);
        if (!Objects.equals(previous, normalized)) {
            resetSyncCheckpoint(editor);
        }
        commitOrThrow(editor);
    }

    /** 纯 SharedPreferences 连接更新逻辑，供 JVM 回归测试验证切换时的检查点语义。 */
    static void updateConnection(SharedPreferences p, String server, String token) {
        if (server == null || server.trim().isEmpty()) {
            throw new IllegalArgumentException("服务器地址不能为空");
        }
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("设备令牌不能为空");
        }
        String normalized = server.trim();
        String previous = p.getString("server", null);
        SharedPreferences.Editor editor = p.edit()
                .putString("server", normalized)
                .putString("device_token", token.trim());
        if (!Objects.equals(previous, normalized)) {
            resetSyncCheckpoint(editor);
        }
        commitOrThrow(editor);
    }

    private static void resetSyncCheckpoint(SharedPreferences.Editor editor) {
        editor.putLong("sync_last_at", 0L)
                .remove("sync_pending_second")
                .remove("sync_pending_max_id")
                .putInt("sync_last_count", 0)
                .putInt("sync_last_scanned", 0)
                .putInt("sync_last_uploaded", 0)
                .putInt("sync_last_deduped", 0)
                .putInt("sync_last_skipped", 0)
                .putInt("sync_last_failed", 0)
                .putInt("sync_last_retryable", 0)
                .putBoolean("sync_last_notification", false)
                .remove("sync_last_error");
    }

    public static boolean isConfigured(Context ctx) {
        String s = getServer(ctx);
        return s != null && !s.trim().isEmpty();
    }

    // ---- 设备身份（首次生成，持久保存） ----
    public static synchronized String getDeviceId(Context ctx) {
        String id = prefs(ctx).getString("device_id", null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            commitOrThrow(prefs(ctx).edit().putString("device_id", id));
        }
        return id;
    }

    // ---- 设备令牌（登录后由服务器颁发，后台同步鉴权用） ----
    public static String getDeviceToken(Context ctx) {
        return prefs(ctx).getString("device_token", null);
    }

    public static void setDeviceToken(Context ctx, String token) {
        commitOrThrow(prefs(ctx).edit().putString("device_token", token));
    }

    public static void clearDeviceToken(Context ctx) {
        commitOrThrow(prefs(ctx).edit().remove("device_token"));
    }

    // ---- 相册同步设置 ----
    public static boolean isSyncEnabled(Context ctx) {
        return prefs(ctx).getBoolean("sync_enabled", false);
    }

    public static boolean isWifiOnly(Context ctx) {
        return prefs(ctx).getBoolean("sync_wifi_only", true);
    }

    /** 同步周期（小时）。 */
    public static double getIntervalHours(Context ctx) {
        return Double.longBitsToDouble(prefs(ctx).getLong("sync_interval_bits", Double.doubleToLongBits(6.0)));
    }

    public static String getTargetFolder(Context ctx) {
        String configured = prefs(ctx).getString("sync_folder", "相册同步");
        try {
            return normalizeTargetFolder(configured);
        } catch (IllegalArgumentException ignored) {
            // 旧版本/手工篡改的配置不能把路径穿越带入上传请求；下次 configure 可修正它。
            return "相册同步";
        }
    }

    /** 单次 commit 保存本次 configure 的全部字段。持久设置是调度的期望状态源。 */
    static synchronized void updateSyncSettings(
            Context ctx, Boolean enabled, Boolean wifiOnly, Double intervalHours, String folder) {
        updateSyncSettings(prefs(ctx), enabled, wifiOnly, intervalHours, folder);
    }

    static void updateSyncSettings(
            SharedPreferences p, Boolean enabled, Boolean wifiOnly, Double intervalHours, String folder) {
        SharedPreferences.Editor editor = p.edit();
        if (enabled != null) editor.putBoolean("sync_enabled", enabled);
        if (wifiOnly != null) editor.putBoolean("sync_wifi_only", wifiOnly);
        if (intervalHours != null) {
            if (!Double.isFinite(intervalHours) || intervalHours <= 0) {
                throw new IllegalArgumentException("同步周期必须为正数");
            }
            editor.putLong("sync_interval_bits", Double.doubleToLongBits(intervalHours));
        }
        if (folder != null) {
            editor.putString("sync_folder", normalizeTargetFolder(folder));
        }
        commitOrThrow(editor);
    }

    /** 校验相册目标目录为 owner 根下的相对 POSIX 路径，避免配置保存后每次同步才失败。 */
    static String normalizeTargetFolder(String folder) {
        if (folder == null) throw new IllegalArgumentException("同步目录不能为空");
        String normalized = folder.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.length() > 240 || normalized.startsWith("/")) {
            throw new IllegalArgumentException("同步目录必须是 1-240 字符的相对路径");
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || isInternalPathSegment(segment) || segment.indexOf(0) >= 0
                    || containsControlCharacter(segment)) {
                throw new IllegalArgumentException("同步目录包含非法路径段");
            }
        }
        return normalized;
    }

    /** 与服务端公共路径边界保持一致，阻止同步把照片写入内部目录或 staging。 */
    private static boolean isInternalPathSegment(String segment) {
        return segment.equals(".index") || segment.equals(".trash")
                || segment.equals(".versions") || segment.equals(".storage.lock")
                || segment.startsWith(".upload.") || segment.startsWith(".copy.")
                || segment.startsWith(".copy-old.");
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    /** 上次同步截至时间（epoch 秒）——只推进到「整秒全部成功」的秒。 */
    public static long getLastSyncAt(Context ctx) {
        return prefs(ctx).getLong("sync_last_at", 0L);
    }

    /** 未完成秒（-1 = 无）：该秒内有失败/未取完的照片，下轮从 _ID 水位续传。 */
    public static long getPendingSecond(Context ctx) {
        return prefs(ctx).getLong("sync_pending_second", -1L);
    }

    /** 未完成秒内已成功上传的最大 _ID（0 = 无）。 */
    public static long getPendingMaxId(Context ctx) {
        return prefs(ctx).getLong("sync_pending_max_id", 0L);
    }

    /** 原子发布完整同步检查点，避免 lastSyncAt 与 pending 状态只写入一半。 */
    public static void setCheckpoint(Context ctx, long lastSyncAt, long pendingSecond, long pendingMaxId) {
        SharedPreferences.Editor editor = prefs(ctx).edit().putLong("sync_last_at", lastSyncAt);
        if (pendingSecond >= 0) {
            editor.putLong("sync_pending_second", pendingSecond)
                    .putLong("sync_pending_max_id", pendingMaxId);
        } else {
            editor.remove("sync_pending_second").remove("sync_pending_max_id");
        }
        commitOrThrow(editor);
    }

    public static int getLastCount(Context ctx) {
        return prefs(ctx).getInt("sync_last_count", 0);
    }

    public static void setLastCount(Context ctx, int n) {
        commitOrThrow(prefs(ctx).edit().putInt("sync_last_count", n));
    }

    public static String getLastError(Context ctx) {
        return prefs(ctx).getString("sync_last_error", null);
    }

    public static void setLastError(Context ctx, String e) {
        commitOrThrow(prefs(ctx).edit().putString("sync_last_error", e));
    }

    /**
     * 原子保存最近一次同步的可观测统计；数值来自真实扫描/上传分支，不是估算值。
     */
    public static void setSyncStats(Context ctx, int scanned, int uploaded, int deduped,
                                    int skipped, int failed, int retryable, boolean notification) {
        setSyncStats(prefs(ctx), scanned, uploaded, deduped, skipped, failed, retryable, notification);
    }

    /** 纯 SharedPreferences 统计写入，便于 JVM 验证单次 commit 语义。 */
    static void setSyncStats(SharedPreferences p, int scanned, int uploaded, int deduped,
                             int skipped, int failed, int retryable, boolean notification) {
        commitOrThrow(p.edit()
                .putInt("sync_last_scanned", Math.max(0, scanned))
                .putInt("sync_last_uploaded", Math.max(0, uploaded))
                .putInt("sync_last_deduped", Math.max(0, deduped))
                .putInt("sync_last_skipped", Math.max(0, skipped))
                .putInt("sync_last_failed", Math.max(0, failed))
                .putInt("sync_last_retryable", Math.max(0, retryable))
                .putBoolean("sync_last_notification", notification));
    }

    public static int getLastScanned(Context ctx) {
        return prefs(ctx).getInt("sync_last_scanned", 0);
    }

    public static int getLastUploaded(Context ctx) {
        return prefs(ctx).getInt("sync_last_uploaded", 0);
    }

    public static int getLastDeduped(Context ctx) {
        return prefs(ctx).getInt("sync_last_deduped", 0);
    }

    public static int getLastSkipped(Context ctx) {
        return prefs(ctx).getInt("sync_last_skipped", 0);
    }

    public static int getLastFailed(Context ctx) {
        return prefs(ctx).getInt("sync_last_failed", 0);
    }

    public static int getLastRetryable(Context ctx) {
        return prefs(ctx).getInt("sync_last_retryable", 0);
    }

    public static boolean getLastNotification(Context ctx) {
        return prefs(ctx).getBoolean("sync_last_notification", false);
    }
}
