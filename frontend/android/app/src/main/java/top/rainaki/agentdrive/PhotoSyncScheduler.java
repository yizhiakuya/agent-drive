package top.rainaki.agentdrive;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** WorkManager 周期任务调度：相册自动同步（App 关闭也运行，重启后仍在册）。 */
public final class PhotoSyncScheduler {

    public static final String UNIQUE_PERIODIC = "photo_sync_periodic";

    private PhotoSyncScheduler() {
    }

    public static void ensureScheduled(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        if (!ServerConfigStore.isSyncEnabled(ctx) || !ServerConfigStore.isConfigured(ctx)) {
            wm.cancelUniqueWork(UNIQUE_PERIODIC);
            return;
        }
        // WorkManager 周期下限 15 分钟
        long minutes = Math.max(15, Math.round(ServerConfigStore.getIntervalHours(ctx) * 60));
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(ServerConfigStore.isWifiOnly(ctx)
                        ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(PhotoSyncWorker.class, minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build();
        wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
