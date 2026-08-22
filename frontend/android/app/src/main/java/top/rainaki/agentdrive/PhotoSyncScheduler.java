package top.rainaki.agentdrive;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** WorkManager 周期任务调度：相册自动同步（App 关闭也运行，重启后仍在册）。 */
public final class PhotoSyncScheduler {

    public static final String UNIQUE_PERIODIC = "photo_sync_periodic";

    private PhotoSyncScheduler() {
    }

    public static final String UNIQUE_QUICK = "photo_sync_now";

    /** 事件驱动（MediaStore 观察者）：拍照后立即排一次快速同步，沿用同步设置约束。 */
    public static void enqueueQuickSync(Context ctx) {
        synchronized (PhotoSyncScheduler.class) {
            // 与停用路径共用锁，避免“检查已启用”后刚好被停用、又把 quick work 排回去。
            if (!ServerConfigStore.isSyncEnabled(ctx) || !ServerConfigStore.isConfigured(ctx)) {
                return;
            }
            Constraints constraints = syncConstraints(ctx);
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PhotoSyncWorker.class)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_QUICK, ExistingWorkPolicy.KEEP, request);
        }
    }

    public static void ensureScheduled(Context ctx) {
        synchronized (PhotoSyncScheduler.class) {
            reconcileSchedule(ctx);
        }
    }

    /** 插件配置路径在后台等待 WorkManager 入库结果，失败时明确报告并留待启动重试。 */
    public static void ensureScheduledAndWait(Context ctx) {
        Operation operation;
        synchronized (PhotoSyncScheduler.class) {
            operation = reconcileSchedule(ctx);
        }
        try {
            operation.getResult().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("同步调度等待被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("同步调度未完成", e);
        }
    }

    private static Operation reconcileSchedule(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        if (!ServerConfigStore.isSyncEnabled(ctx) || !ServerConfigStore.isConfigured(ctx)) {
            // 停用必须同时取消周期和事件驱动任务；否则相册观察者留下的 quick work
            // 仍可能在用户关闭同步后上传照片。
            wm.cancelUniqueWork(UNIQUE_QUICK);
            return wm.cancelUniqueWork(UNIQUE_PERIODIC);
        }
        // WorkManager 周期下限 15 分钟
        long minutes = Math.max(15, Math.round(ServerConfigStore.getIntervalHours(ctx) * 60));
        Constraints constraints = syncConstraints(ctx);
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(PhotoSyncWorker.class, minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build();
        return wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    /** 周期、观察者快速同步和手动同步共用同一组产品约束。 */
    public static Constraints syncConstraints(Context ctx) {
        return new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(ServerConfigStore.isWifiOnly(ctx)
                        ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
    }
}
