package top.rainaki.agentdrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ServerConfigStoreTest {

    @Test
    public void plaintextMigrationCopiesKnownKeysAndPreservesUnknownEntries() {
        MemoryPreferences secure = new MemoryPreferences();
        MemoryPreferences legacy = new MemoryPreferences();
        legacy.values.put("server", "https://legacy.example");
        legacy.values.put("device_token", "legacy-token");
        legacy.values.put("sync_enabled", true);
        legacy.values.put("unrelated", "keep");
        legacy.values.put("__androidx_security_crypto_encrypted_prefs_key_keyset__", "keyset");

        ServerConfigStore.migrateSources(secure, legacy, null, legacy.getAll());

        assertEquals("https://legacy.example", secure.getString("server", null));
        assertEquals("legacy-token", secure.getString("device_token", null));
        assertTrue(secure.getBoolean("sync_enabled", false));
        assertFalse(legacy.contains("server"));
        assertFalse(legacy.contains("device_token"));
        assertEquals("keep", legacy.getString("unrelated", null));
        assertTrue(legacy.contains("__androidx_security_crypto_encrypted_prefs_key_keyset__"));
    }

    @Test
    public void matchingSecureAndLegacyValuesMigrateMissingKeysAndCleanBothSources() {
        MemoryPreferences secure = new MemoryPreferences();
        secure.values.put("server", "https://current.example");
        MemoryPreferences legacyRaw = new MemoryPreferences();
        legacyRaw.values.put("server", "https://current.example");
        legacyRaw.values.put("sync_folder", "旧相册");
        MemoryPreferences legacyEncrypted = new MemoryPreferences();
        legacyEncrypted.values.put("server", "https://current.example");
        legacyEncrypted.values.put("device_token", "encrypted-token");
        legacyEncrypted.values.put("sync_last_error", null);

        ServerConfigStore.migrateSources(
                secure, legacyRaw, legacyEncrypted, legacyRaw.getAll());

        assertEquals("https://current.example", secure.getString("server", null));
        assertEquals("encrypted-token", secure.getString("device_token", null));
        assertEquals("旧相册", secure.getString("sync_folder", null));
        assertFalse(secure.contains("sync_last_error"));
        assertFalse(legacyRaw.contains("server"));
        assertFalse(legacyRaw.contains("sync_folder"));
        assertFalse(legacyEncrypted.contains("server"));
        assertFalse(legacyEncrypted.contains("device_token"));
    }

    @Test
    public void conflictingNewAndLegacyValuesFailClosedWithoutDeletingEitherCopy() {
        MemoryPreferences secure = new MemoryPreferences();
        secure.values.put("device_token", "token-a");
        MemoryPreferences legacy = new MemoryPreferences();
        legacy.values.put("device_token", "token-b");

        assertThrows(IllegalStateException.class,
                () -> ServerConfigStore.migrateSources(secure, legacy, null, legacy.getAll()));

        assertEquals("token-a", secure.getString("device_token", null));
        assertEquals("token-b", legacy.getString("device_token", null));
        assertEquals(0, legacy.commitCount);
    }

    @Test
    public void legacyCiphertextWinsOverOlderPlaintextResidue() {
        MemoryPreferences secure = new MemoryPreferences();
        MemoryPreferences legacyRaw = new MemoryPreferences();
        legacyRaw.values.put("device_token", "stale-plain-token");
        MemoryPreferences legacyEncrypted = new MemoryPreferences();
        legacyEncrypted.values.put("device_token", "current-encrypted-token");

        ServerConfigStore.migrateSources(
                secure, legacyRaw, legacyEncrypted, legacyRaw.getAll());

        assertEquals("current-encrypted-token", secure.getString("device_token", null));
        assertFalse(legacyRaw.contains("device_token"));
        assertFalse(legacyEncrypted.contains("device_token"));
    }

    @Test
    public void destinationCommitFailureDoesNotCleanLegacySource() {
        MemoryPreferences secure = new MemoryPreferences();
        MemoryPreferences legacy = new MemoryPreferences();
        legacy.values.put("device_token", "must-survive");
        secure.failNextCommit = true;

        assertThrows(IllegalStateException.class,
                () -> ServerConfigStore.migrateSources(secure, legacy, null, legacy.getAll()));

        assertFalse(secure.contains("device_token"));
        assertEquals("must-survive", legacy.getString("device_token", null));
    }

    @Test
    public void cleanupFailureKeepsNewEncryptedValueForRetry() {
        MemoryPreferences secure = new MemoryPreferences();
        MemoryPreferences legacy = new MemoryPreferences();
        legacy.values.put("device_token", "copied-before-cleanup");
        legacy.failNextCommit = true;

        assertThrows(IllegalStateException.class,
                () -> ServerConfigStore.migrateSources(secure, legacy, null, legacy.getAll()));

        assertEquals("copied-before-cleanup", secure.getString("device_token", null));
        assertEquals("copied-before-cleanup", legacy.getString("device_token", null));
    }

    @Test
    public void stalePlaintextCleanupFailureRetriesWithoutLosingCurrentCiphertext() {
        MemoryPreferences secure = new MemoryPreferences();
        MemoryPreferences legacyRaw = new MemoryPreferences();
        legacyRaw.values.put("device_token", "stale-plain-token");
        MemoryPreferences legacyEncrypted = new MemoryPreferences();
        legacyEncrypted.values.put("device_token", "current-encrypted-token");
        legacyRaw.failNextCommit = true;

        assertThrows(IllegalStateException.class, () -> ServerConfigStore.migrateSources(
                secure, legacyRaw, legacyEncrypted, legacyRaw.getAll()));
        assertEquals("current-encrypted-token", secure.getString("device_token", null));
        assertEquals("stale-plain-token", legacyRaw.getString("device_token", null));
        assertEquals("current-encrypted-token", legacyEncrypted.getString("device_token", null));

        ServerConfigStore.migrateSources(
                secure, legacyRaw, legacyEncrypted, legacyRaw.getAll());
        assertEquals("current-encrypted-token", secure.getString("device_token", null));
        assertFalse(legacyRaw.contains("device_token"));
        assertFalse(legacyEncrypted.contains("device_token"));
    }

    @Test
    public void syncSettingsUpdateUsesOneAtomicCommit() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("sync_enabled", false);
        prefs.values.put("sync_wifi_only", true);
        prefs.values.put("sync_interval_bits", Double.doubleToLongBits(6.0));
        prefs.values.put("sync_folder", "相册同步");

        ServerConfigStore.updateSyncSettings(prefs, true, false, 12.0, " 新目录 ");

        assertEquals(1, prefs.commitCount);
        assertTrue(prefs.getBoolean("sync_enabled", false));
        assertFalse(prefs.getBoolean("sync_wifi_only", true));
        assertEquals(12.0, Double.longBitsToDouble(prefs.getLong("sync_interval_bits", 0L)), 0.0);
        assertEquals("新目录", prefs.getString("sync_folder", null));
    }

    @Test
    public void failedSyncSettingsCommitLeavesAllPreviousValues() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("sync_enabled", false);
        prefs.values.put("sync_wifi_only", true);
        prefs.values.put("sync_interval_bits", Double.doubleToLongBits(6.0));
        prefs.values.put("sync_folder", "相册同步");
        prefs.failNextCommit = true;

        assertThrows(IllegalStateException.class,
                () -> ServerConfigStore.updateSyncSettings(prefs, true, false, 12.0, "new"));

        assertFalse(prefs.getBoolean("sync_enabled", true));
        assertTrue(prefs.getBoolean("sync_wifi_only", false));
        assertEquals(6.0, Double.longBitsToDouble(prefs.getLong("sync_interval_bits", 0L)), 0.0);
        assertEquals("相册同步", prefs.getString("sync_folder", null));
    }

    @Test
    public void switchingServerAtomicallyResetsCheckpointAndSyncSummary() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("server", "https://old.example");
        prefs.values.put("device_token", "old-token");
        prefs.values.put("sync_last_at", 1234L);
        prefs.values.put("sync_pending_second", 1234L);
        prefs.values.put("sync_pending_max_id", 88L);
        prefs.values.put("sync_last_count", 7);
        prefs.values.put("sync_last_error", "old failure");

        ServerConfigStore.updateConnection(prefs, "https://new.example", "new-token");

        assertEquals(1, prefs.commitCount);
        assertEquals("https://new.example", prefs.getString("server", null));
        assertEquals("new-token", prefs.getString("device_token", null));
        assertEquals(0L, prefs.getLong("sync_last_at", -1L));
        assertFalse(prefs.contains("sync_pending_second"));
        assertFalse(prefs.contains("sync_pending_max_id"));
        assertEquals(0, prefs.getInt("sync_last_count", -1));
        assertFalse(prefs.contains("sync_last_error"));
    }

    @Test
    public void reconnectingSameServerKeepsCheckpoint() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("server", "https://same.example");
        prefs.values.put("sync_last_at", 1234L);
        prefs.values.put("sync_pending_second", 1234L);
        prefs.values.put("sync_pending_max_id", 88L);
        prefs.values.put("sync_last_count", 7);
        prefs.values.put("sync_last_error", "old failure");

        ServerConfigStore.updateConnection(prefs, "https://same.example", "new-token");

        assertEquals(1234L, prefs.getLong("sync_last_at", -1L));
        assertEquals(1234L, prefs.getLong("sync_pending_second", -1L));
        assertEquals(88L, prefs.getLong("sync_pending_max_id", -1L));
        assertEquals(7, prefs.getInt("sync_last_count", -1));
        assertEquals("old failure", prefs.getString("sync_last_error", null));
    }

    @Test
    public void syncDiagnosticsAreWrittenAsOneCommit() {
        MemoryPreferences prefs = new MemoryPreferences();
        ServerConfigStore.setSyncStats(prefs, 10, 6, 3, 1, 2, 2, false);

        assertEquals(1, prefs.commitCount);
        assertEquals(10, prefs.getInt("sync_last_scanned", -1));
        assertEquals(6, prefs.getInt("sync_last_uploaded", -1));
        assertEquals(3, prefs.getInt("sync_last_deduped", -1));
        assertEquals(1, prefs.getInt("sync_last_skipped", -1));
        assertEquals(2, prefs.getInt("sync_last_failed", -1));
        assertEquals(2, prefs.getInt("sync_last_retryable", -1));
        assertFalse(prefs.getBoolean("sync_last_notification", true));
    }

    @Test
    public void targetFolderRejectsTraversalAndNormalizesSeparators() {
        assertEquals("手机照片/2026", ServerConfigStore.normalizeTargetFolder(" 手机照片\\2026 "));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfigStore.normalizeTargetFolder("../outside"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfigStore.normalizeTargetFolder("/absolute"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfigStore.normalizeTargetFolder(".trash"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfigStore.normalizeTargetFolder(".versions"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfigStore.normalizeTargetFolder(".copy.staging"));
    }

    @Test
    public void activityObserverHasLifecycleCleanupHook() throws Exception {
        assertEquals(android.database.ContentObserver.class,
                MainActivity.class.getDeclaredField("mediaObserver").getType());
        assertTrue(java.lang.reflect.Modifier.isPublic(
                MainActivity.class.getDeclaredMethod("onDestroy").getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(
                PhotoSyncPlugin.class.getDeclaredField("CONFIGURE_EXECUTOR").getModifiers()));
    }

    @Test
    public void photoSyncExposesNotificationSettingsAction() {
        boolean found = java.util.Arrays.stream(PhotoSyncPlugin.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("openNotificationSettings"));
        assertTrue(found);
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        private boolean failNextCommit;
        private int commitCount;

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defaultValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defaultValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defaultValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defaultValues;
        }

        @Override
        public int getInt(String key, int defaultValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defaultValue;
        }

        @Override
        public long getLong(String key, long defaultValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defaultValue;
        }

        @Override
        public float getFloat(String key, float defaultValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defaultValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defaultValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> writes = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                writes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                writes.put(key, value == null ? null : new HashSet<>(value));
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                writes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                writes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                writes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                writes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                writes.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                writes.clear();
                removals.clear();
                return this;
            }

            @Override
            public boolean commit() {
                commitCount += 1;
                if (failNextCommit) {
                    failNextCommit = false;
                    return false;
                }
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(writes);
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
