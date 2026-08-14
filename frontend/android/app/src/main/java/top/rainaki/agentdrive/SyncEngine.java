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
 * - 整秒检查点：lastSyncAt 只推进到「整秒全部成功」的秒；同秒有失败或未取完则挂
 *   pending（_ID 水位续传），绝不因 DATE_ADDED > 检查点 永久跳过照片
 * - 内容去重（秒传）：服务端按 MD5 命中则跳过传输；重试时已传文件零流量
 * - 同名冲突：noclobber 参数让服务端自动加序号，绝不覆盖
 * - 单张失败不阻塞整批：跳过继续，失败计数由 Worker 决定退避重试
 */
public final class SyncEngine {

    private static final int MAX_PER_RUN = 200;

    /** 连接级失败（断网/服务端故障/鉴权失效）：中止整批，Worker 退避重试。 */
    private static final class AbortBatchException extends IOException {
        AbortBatchException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

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

    /**
     * 增量同步一批照片（≤200 张）。
     *
     * 检查点（防丢照片的关键设计）：
     * - lastSyncAt 只推进到「整秒全部成功」的秒；同秒内有失败，该秒挂为 pending
     * - pending 秒用 _ID 水位续传（DATE_ADDED=该秒 AND _ID>水位），失败/未取完的
     *   剩余张下一轮必然再被选中，绝不因 DATE_ADDED > 检查点 被永久跳过
     * - 单秒张数超过单轮上限（连拍/批量导入）同样挂 pending 续传，不会截断丢张
     *
     * @return 本次成功上传张数；失败数写入 lastError（Worker 据此退避重试）
     */
    public static int sync(Context ctx) throws IOException {
        String server = ServerConfigStore.getServer(ctx);
        if (server == null || server.trim().isEmpty()) {
            throw new IOException("未配置服务器");
        }
        String base = server.trim().replaceAll("/+$", "") + "/api/v1";

        long lastSync = ServerConfigStore.getLastSyncAt(ctx);       // 最近一个「整秒完成」的秒
        long pendingSecond = ServerConfigStore.getPendingSecond(ctx); // 未完成秒（-1 = 无）
        long pendingMaxId = ServerConfigStore.getPendingMaxId(ctx);   // 该秒内已成功上传的最大 _ID
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
        String selection;
        String[] args;
        if (pendingSecond >= 0) {
            // 未完成秒续传：先扫该秒 _ID 水位之后的剩余张，再扫更晚的秒
            selection = MediaStore.Images.Media.DATE_ADDED + " > ? OR ("
                    + MediaStore.Images.Media.DATE_ADDED + " = ? AND "
                    + MediaStore.Images.Media._ID + " > ?)";
            args = new String[]{String.valueOf(pendingSecond), String.valueOf(pendingSecond),
                    String.valueOf(pendingMaxId)};
        } else {
            selection = MediaStore.Images.Media.DATE_ADDED + " > ?";
            args = new String[]{String.valueOf(lastSync)};
        }

        // 限幅查询：SQL 级 LIMIT（MAX_PER_RUN+1），不再全量 COUNT 统计
        running = true;
        phase = "scanning";
        currentFile = "";
        uploaded = 0;
        total = 0;
        emitProgress();
        Cursor c;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.os.Bundle b = new android.os.Bundle();
            b.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            b.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
            b.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[]{
                    MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media._ID});
            b.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING);
            b.putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_PER_RUN + 1);
            c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, b, null);
        } else {
            c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
                    MediaStore.Images.Media.DATE_ADDED + " ASC, " + MediaStore.Images.Media._ID + " ASC");
        }
        if (c == null) {
            running = false;
            throw new IOException("无法读取相册");
        }
        int rows = c.getCount();
        boolean drained = rows < MAX_PER_RUN + 1; // 光标耗尽（未触限）＝全部匹配行已取出
        total = Math.min(rows, MAX_PER_RUN); // 限幅游标直接给总数（+1 行用于截断边界探测）
        phase = total == 0 ? "done" : "uploading";
        emitProgress();

        int count = 0;
        int failures = 0;
        // 按秒分组记账：整秒无失败才推进检查点
        long groupSecond = -1;
        long groupMaxId = 0;      // 本秒内已成功上传的最大 _ID（pending 续传水位）
        boolean groupFailed = false;
        long lastDrained = lastSync; // 本轮确认「整秒完成」的最大秒
        boolean pendingReplaced = false;
        boolean aborted = false;
        try {
            while (c.moveToNext()) {
                long ts = c.getLong(2);
                long id = c.getLong(0);
                if (groupSecond != -1 && ts != groupSecond) {
                    // 进入新秒＝上一秒的行已全部取出（该秒完整）
                    if (groupFailed) {
                        ServerConfigStore.setPendingSecond(ctx, groupSecond);
                        ServerConfigStore.setPendingMaxId(ctx, groupMaxId);
                        pendingReplaced = true;
                    } else {
                        lastDrained = groupSecond;
                    }
                    groupSecond = -1;
                    groupMaxId = 0;
                    groupFailed = false;
                }
                if (groupSecond == -1) {
                    groupSecond = ts;
                    groupMaxId = (ts == pendingSecond) ? pendingMaxId : 0;
                }
                String name = c.getString(1);
                String mime = c.getString(3);
                if (mime == null || mime.isEmpty()) {
                    mime = "image/jpeg";
                }
                String dateDir = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts * 1000L));
                String relFolder = folder + "/" + dateDir;

                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                try {
                    if (uploadOne(ctx, base, relFolder, name, mime, uri, deviceToken)) {
                        count++;
                        if (id > groupMaxId) {
                            groupMaxId = id;
                        }
                        uploaded = count;
                        currentFile = name;
                        emitProgress();          // JS 实时进度
                        notifyProgress(ctx);      // 通知栏进度（节流）
                    }
                } catch (AbortBatchException e) {
                    aborted = true;      // 连接级失败：中止整批
                    groupFailed = true;  // 当前秒组标记未完成 → pending 续传
                    break;
                } catch (Exception e) {
                    failures++; // 单张失败不阻塞整批（同秒挂 pending，下轮续传）
                    groupFailed = true;
                }
            }
        } finally {
            c.close();
        }

        // 最后一组：无失败且光标耗尽才算整秒完成；否则挂 pending 续传
        if (groupSecond != -1) {
            if (groupFailed || !drained) {
                ServerConfigStore.setPendingSecond(ctx, groupSecond);
                ServerConfigStore.setPendingMaxId(ctx, groupMaxId);
                pendingReplaced = true;
            } else {
                lastDrained = groupSecond;
            }
        }
        // 检查点只前进，不回退
        if (lastDrained > lastSync) {
            ServerConfigStore.setLastSyncAt(ctx, lastDrained);
        }
        // pending 清理：整秒完成、或该秒已无任何行（照片被删）时清除
        if (pendingSecond >= 0 && !pendingReplaced && (groupSecond == -1 || lastDrained >= pendingSecond)) {
            ServerConfigStore.clearPending(ctx);
        }

        if (aborted) {
            ServerConfigStore.setLastError(ctx, "网络或服务器异常，已中止本批，将自动重试");
        } else if (failures > 0) {
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
        int code;
        try {
            code = conn.getResponseCode();
        } catch (IOException e) {
            conn.disconnect();
            throw new AbortBatchException("网络连接失败", e); // 断网：整批中止而非 200 张串行超时
        }
        conn.disconnect();
        if (code == 401 || code == 403 || code >= 500) {
            throw new AbortBatchException("服务器拒绝/异常 HTTP " + code, null); // 令牌失效或服务端故障：整批中止
        }
        if (code < 200 || code >= 300) {
            throw new IOException("上传失败 HTTP " + code); // 单张 4xx：跳过继续
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
            // & 0xff：byte 符号扩展会让 %02x 渲染成 8 位十六进制（md5 全错）
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}