package top.rainaki.agentdrive;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 相册同步引擎：MediaStore 扫描 → 暂存并算 MD5 → multipart 上传（秒传 + 不覆盖）。
 *
 * 可靠性设计：
 * - 逐张检查点：每张成功后立即推进 lastSyncAt，失败重试从断点续传
 * - 内容去重（秒传）：服务端按 MD5 命中则跳过传输；重试时已传文件零流量
 * - 同名冲突：noclobber 参数让服务端自动加序号，绝不覆盖
 * - 单张失败不阻塞整批：跳过继续，失败计数由 Worker 决定退避重试
 */
public final class SyncEngine {

    private static final int MAX_PER_RUN = 200;

    // 进度状态（Worker 与插件同进程，插件 getStatus 直接读；JS 经事件实时收）
    public static volatile boolean running = false;
    public static volatile String phase = "idle";
    public static volatile String currentFile = "";
    public static volatile int uploaded = 0;
    public static volatile int total = 0;

    private SyncEngine() {
    }

    /** 广播进度：JS 监听 syncProgress 事件（WebView 打开时） */
    public static void emitProgress() {
        PhotoSyncPlugin.emitProgress(new com.getcapacitor.JSObject()
                .put("running", running)
                .put("phase", phase)
                .put("currentFile", currentFile)
                .put("uploaded", uploaded)
                .put("total", total));
    }

    /** @return 本次成功上传张数；失败数写入 lastError（Worker 据此退避重试） */
    public static int sync(Context ctx) throws IOException {
        String server = ServerConfigStore.getServer(ctx);
        if (server == null || server.trim().isEmpty()) {
            throw new IOException("未配置服务器");
        }
        String base = server.trim().replaceAll("/+$", "") + "/api/v1";

        long lastSync = ServerConfigStore.getLastSyncAt(ctx); // epoch 秒
        String folder = ServerConfigStore.getTargetFolder(ctx);
        if (folder == null || folder.trim().isEmpty()) {
            folder = "相册同步";
        }
        String deviceToken = ServerConfigStore.getDeviceToken(ctx);

        ContentResolver cr = ctx.getContentResolver();
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
        };
        String selection = MediaStore.Images.Media.DATE_ADDED + " > ?";
        String[] args = {String.valueOf(lastSync)};

        // 先统计待传总数（进度条用）
        running = true;
        phase = "scanning";
        currentFile = "";
        uploaded = 0;
        total = 0;
        emitProgress();
        try (Cursor cc = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{"COUNT(*)"}, selection, args, null)) {
            if (cc != null && cc.moveToFirst()) {
                total = Math.min(cc.getInt(0), MAX_PER_RUN);
            }
        }
        phase = total == 0 ? "done" : "uploading";
        emitProgress();

        Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
                MediaStore.Images.Media.DATE_ADDED + " ASC");
        if (c == null) {
            running = false;
            throw new IOException("无法读取相册");
        }

        int count = 0;
        int failures = 0;
        long maxTs = lastSync;
        try {
            while (c.moveToNext() && count < MAX_PER_RUN) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long ts = c.getLong(2);
                String mime = c.getString(3);
                if (mime == null || mime.isEmpty()) {
                    mime = "image/jpeg";
                }
                String dateDir = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts * 1000L));
                String relFolder = folder + "/" + dateDir;

                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                try {
                    if (uploadOne(ctx, base, relFolder, name, mime, uri, deviceToken)) {
                        maxTs = Math.max(maxTs, ts);
                        count++;
                        ServerConfigStore.setLastSyncAt(ctx, maxTs); // 逐张检查点
                        uploaded = count;
                        currentFile = name;
                        emitProgress();          // JS 实时进度
                        notifyProgress(ctx);      // 通知栏进度（节流）
                    }
                } catch (Exception e) {
                    failures++; // 单张失败不阻塞整批
                }
            }
        } finally {
            c.close();
        }

        if (failures > 0) {
            ServerConfigStore.setLastError(ctx, failures + " 张上传失败，将自动重试");
        } else {
            ServerConfigStore.setLastError(ctx, null);
        }
        running = false;
        phase = "done";
        emitProgress();
        return count;
    }

    /** 通知栏进度（节流：最多每秒更新一次，避免刷屏） */
    private static long lastNotifyAt = 0;

    private static void notifyProgress(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt < 1000 && uploaded < total) {
            return;
        }
        lastNotifyAt = now;
        try {
            PhotoSyncWorker.ensureChannel(ctx);
            NotificationManagerCompat.from(ctx).notify(1, new NotificationCompat.Builder(ctx, "photo_sync")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("相册同步中")
                    .setContentText(uploaded + " / " + total + (currentFile.isEmpty() ? "" : " · " + currentFile))
                    .setProgress(total, uploaded, total == 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build());
        } catch (SecurityException ignored) {
            // 无通知权限时静默
        }
    }

    /** 单张：MediaStore → 缓存临时文件（顺带算 MD5）→ multipart 上传 → 清理临时文件。 */
    private static boolean uploadOne(Context ctx, String base, String relFolder, String name, String mime,
                                    Uri uri, String deviceToken) throws Exception {
        File tmp = null;
        try {
            tmp = File.createTempFile("photosync-", ".tmp", ctx.getCacheDir());
            String md5;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) {
                    return false; // 读不到就跳过（不计失败）
                }
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                md5 = hex(digest.digest());
            }

            // path 走查询参数（服务端约定），md5/noclobber 走表单字段
            String query = "?path=" + URLEncoder.encode(relFolder, "UTF-8");
            try (InputStream in = new FileInputStream(tmp)) {
                uploadFile(base + "/files/upload" + query, name, mime, in, md5, deviceToken);
            }
            return true;
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    /** multipart/form-data：path=查询参数（已拼好），md5/noclobber=表单字段，文件 part 名 file。 */
    private static void uploadFile(String uploadUrl, String fileName, String mime, InputStream in,
                                  String md5, String deviceToken) throws IOException {
        String boundary = "----AgentDriveBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (deviceToken != null && !deviceToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + deviceToken);
        }
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        try (OutputStream os = conn.getOutputStream()) {
            writePart(os, boundary, "md5", md5, null, null);
            writePart(os, boundary, "noclobber", "true", null, null);
            writePart(os, boundary, "file", fileName, mime, in);
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("上传失败 HTTP " + code);
        }
    }

    private static void writePart(OutputStream os, String boundary, String field, String valueOrName,
                                  String mime, InputStream in) throws IOException {
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"";
        if (mime != null) {
            head += "; filename=\"" + valueOrName + "\"\r\nContent-Type: " + mime;
        }
        os.write((head + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
        } else {
            os.write(valueOrName.getBytes(StandardCharsets.UTF_8));
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}