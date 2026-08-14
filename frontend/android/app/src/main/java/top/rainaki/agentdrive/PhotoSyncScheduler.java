package top.rainaki.agentdrive;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** WorkManager 周期任务调度：相册自动同步（App 关闭也运行，重启后仍在册）。 */
public final class PhotoSyncScheduler {

    public static final String UNIQUE_PERIODIC = "photo_sync_periodic";

    private PhotoSyncScheduler() {
    }

    public static final String UNIQUE_QUICK = "photo_sync_now";

    /** 事件驱动（MediaStore 观察者）：拍照后立即排一次快速同步，带约束（仅 Wi-Fi/低电量）。 */
    public static void enqueueQuickSync(Context ctx) {
        if (!ServerConfigStore.isSyncEnabled(ctx) || !ServerConfigStore.isConfigured(ctx)) {
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(ServerConfigStore.isWifiOnly(ctx)
                        ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PhotoSyncWorker.class)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_QUICK, ExistingWorkPolicy.KEEP, request);
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
