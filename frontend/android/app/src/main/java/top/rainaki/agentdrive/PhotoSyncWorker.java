package top.rainaki.agentdrive;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** 后台同步任务：扫描新增照片并上传，完成后发通知。 */
public class PhotoSyncWorker extends Worker {

    private static final String CHANNEL = "photo_sync";
    private static final int NOTIFY_ID = 1;

    public PhotoSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            int n = SyncEngine.sync(ctx);
            ServerConfigStore.setLastCount(ctx, n);
            if (n > 0) {
                notifyDone(ctx, n);
            }
            // 同步完成后上报设备状态（web 设备列表可见最新同步信息）
            DeviceRegistrar.register(ctx, true);
            // 有失败 → 退避重试（断点续传 + 秒传让重试零流量）
            return ServerConfigStore.getLastError(ctx) == null ? Result.success() : Result.retry();
        } catch (Exception e) {
            ServerConfigStore.setLastError(ctx, String.valueOf(e.getMessage()));
            return Result.retry();
        }
    }

    private void notifyDone(Context ctx, int n) {
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, new NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("相册同步完成")
                    .setContentText("新上传 " + n + " 张照片到网盘")
                    .setAutoCancel(true)
                    .build());
        } catch (SecurityException ignored) {
            // 无通知权限时静默
        }
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "相册自动同步", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
