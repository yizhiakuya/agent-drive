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
import java.io.FileNotFoundException;
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
 * - 单张失败不阻塞整批：永久 4xx 和已删除/无权限的本地媒体跳过并推进水位，其余
 *   4xx/本地 I/O 冻结水位下轮重试；线程中断会保留中断位并中止整批
 */
public final class SyncEngine {

    private static final int MAX_PER_RUN = 200;

    /** 连接级失败（断网/服务端故障/鉴权失效）：中止整批，Worker 退避重试。 */
    private static final class AbortBatchException extends IOException {
        AbortBatchException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * 服务端会一直拒绝的单张错误（永久 4xx）：跳过该张并推进连续水位，不阻塞检查点。
     * 其余 4xx（404/405/408/409/429 等）可能由服务端瞬时状态引起，仍按失败冻结重试。
     */
    private static final class PermanentSkipException extends IOException {
        PermanentSkipException(String msg) {
            super(msg);
        }
    }

    static boolean isPermanentClientError(int code) {
        return code == 400 || code == 413 || code == 415 || code == 416 || code == 422;
    }

    /** 单次请求（上传/预检）HTTP 状态码的同步结果类别（纯函数输出）。 */
    enum SyncResult {
        /** 请求成功：上传任意 2xx 记为成功；dedupe 预检仅 200 视为命中。 */
        SUCCESS,
        /** dedupe 预检 404：服务端未命中，继续走上传，不算失败。 */
        MISS,
        /** 401/403/≥500：连接级失败（鉴权失效/服务端故障），整批中止。 */
        ABORT,
        /** 永久 4xx（400/413/415/416/422）：跳过该张并推进连续水位。 */
        SKIP,
        /** 其余 4xx（404/405/408/409/429 等）：可能瞬时，冻结水位下轮重试。 */
        RETRY
    }

    /** 单张本地媒体读取失败的处理类别（纯函数输出）。 */
    enum LocalMediaResult {
        /** 媒体已删除或权限永久拒绝：跳过并推进连续水位。 */
        SKIP,
        /** 普通本地 I/O 故障：冻结当前秒水位，下轮重试。 */
        RETRY,
        /** 显式或线程级中断：保留中断位并中止整批。 */
        ABORT
    }

    /**
     * 纯函数：按异常链和调用方捕获到的线程中断状态分类本地媒体失败。
     *
     * @param error 单张处理抛出的异常。
     * @param threadInterrupted 捕获异常时线程是否已处于中断状态。
     * @return 永久跳过、可重试或整批中止。
     */
    static LocalMediaResult classifyLocalMediaFailure(Throwable error, boolean threadInterrupted) {
        if (threadInterrupted || hasCause(error, InterruptedException.class)) {
            return LocalMediaResult.ABORT;
        }
        if (hasCause(error, FileNotFoundException.class) || hasCause(error, SecurityException.class)) {
            return LocalMediaResult.SKIP;
        }
        return LocalMediaResult.RETRY;
    }

    /** 判断异常链中是否包含指定类型，并防止异常自引用造成死循环。 */
    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return false;
    }

    /**
     * 纯函数：把整型 HTTP 状态码归为同步结果类别。
     * <p>
     * {@code upload=true}（上传路径）：任意 2xx = 成功，404 归为可重试的 RETRY；
     * {@code upload=false}（dedupe 预检）：仅 200 视为命中，404 视为 MISS（未命中继续上传），
     * 其余 2xx（201/204 等）仍归 RETRY。
     * 与 dedupeHit/uploadFile 原内联分类逐分支等价。
     */
    static SyncResult classifySyncStatus(int code, boolean upload) {
        if (code >= 200 && code < 300) {
            return (upload || code == 200) ? SyncResult.SUCCESS : SyncResult.RETRY;
        }
        if (code == 404) {
            return upload ? SyncResult.RETRY : SyncResult.MISS;
        }
        if (code == 401 || code == 403 || code >= 500) {
            return SyncResult.ABORT;
        }
        if (isPermanentClientError(code)) {
            return SyncResult.SKIP;
        }
        return SyncResult.RETRY; // 其余 4xx（405/408/409/429 等）
    }

    /**
     * 纯函数：同秒续传的查询选择串——先扫 pending 秒 _ID 水位之后的剩余张，再扫更晚的秒。
     */
    static String buildResumeSelection() {
        return MediaStore.Images.Media.DATE_ADDED + " > ? OR ("
                + MediaStore.Images.Media.DATE_ADDED + " = ? AND "
                + MediaStore.Images.Media._ID + " > ?)";
    }

    // 进度状态（Worker 与插件同进程，插件 getStatus 直接读；JS 经事件实时收）
    public static volatile boolean running = false;
    public static volatile String phase = "idle";
    public static volatile String currentFile = "";
    public static volatile int uploaded = 0;
    public static volatile int total = 0;

    private SyncEngine() {
    }

    /** 纯 Java 检查点记账器：保证失败秒后的更晚成功秒不能越过失败点。 */
    static final class CheckpointTracker {
        private long groupSecond = -1;
        private long groupMaxId = 0;
        private boolean groupFailed = false;
        private boolean watermarkOpen = true;
        private long lastDrained;
        private long pendingSecond = -1;
        private long pendingMaxId = 0;
        private boolean checkpointBlocked = false;
        private boolean pendingCanResolve = false;

        CheckpointTracker(long lastDrained) {
            this(lastDrained, -1, 0);
        }

        CheckpointTracker(long lastDrained, long pendingSecond, long pendingMaxId) {
            this.lastDrained = lastDrained;
            this.pendingSecond = pendingSecond;
            this.pendingMaxId = pendingMaxId;
            this.checkpointBlocked = pendingSecond >= 0;
            // 只有从上一轮读入的 pending 才能在本轮“跨过且未看到该秒”时被解析；
            // 本轮刚产生的失败/截断 pending 必须冻结到下一轮。
            this.pendingCanResolve = pendingSecond >= 0;
        }

        void begin(long second, long initialMaxId) {
            if (groupSecond != -1 && second != groupSecond) {
                finishGroup(true);
            }
            if (groupSecond == -1 && pendingSecond >= 0) {
                if (second == pendingSecond) {
                    pendingCanResolve = false; // 已看到该秒的候选行，必须完整处理
                } else if (second > pendingSecond && pendingCanResolve) {
                    // 查询结果已越过 pending 秒，说明该秒没有尚未处理的 MediaStore 行。
                    resolvePending();
                }
            }
            if (groupSecond == -1) {
                groupSecond = second;
                groupMaxId = second == pendingSecond ? Math.max(initialMaxId, pendingMaxId) : initialMaxId;
                groupFailed = false;
                watermarkOpen = true;
            }
        }

        void success(long id) {
            if (watermarkOpen && id > groupMaxId) {
                groupMaxId = id;
            }
        }

        void failure() {
            groupFailed = true;
            pendingCanResolve = false;
            watermarkOpen = false; // 失败后的成功项会靠服务端秒传重试，不能跨过失败 _ID
        }

        /** 永久被拒照片按“跳过”记账：水位越过它，但不把该秒标记为失败。 */
        void skip(long id) {
            if (watermarkOpen && id > groupMaxId) {
                groupMaxId = id;
            }
        }

        void finishGroup(boolean complete) {
            if (groupSecond == -1) return;
            if (groupFailed || !complete) {
                rememberPending(groupSecond, groupMaxId);
                checkpointBlocked = true;
            } else if (pendingSecond == groupSecond) {
                // 本轮把上一轮失败秒的剩余照片全部处理完，解除阻断。
                pendingSecond = -1;
                pendingMaxId = 0;
                checkpointBlocked = false;
                lastDrained = Math.max(lastDrained, groupSecond);
            } else if (!checkpointBlocked) {
                lastDrained = Math.max(lastDrained, groupSecond);
            }
            groupSecond = -1;
            groupMaxId = 0;
            groupFailed = false;
            watermarkOpen = true;
        }

        void finishEmpty() {
            if (groupSecond == -1 && pendingSecond >= 0 && pendingCanResolve) {
                resolvePending();
            }
        }

        void truncatedAt(long nextSecond) {
            if (groupSecond != -1 && nextSecond == groupSecond) {
                finishGroup(false);
            } else {
                finishGroup(true);
                rememberPending(nextSecond, 0);
                checkpointBlocked = true;
            }
        }

        private void resolvePending() {
            if (pendingSecond >= 0) {
                lastDrained = Math.max(lastDrained, pendingSecond);
                pendingSecond = -1;
                pendingMaxId = 0;
                checkpointBlocked = false;
            }
        }

        private void rememberPending(long second, long maxId) {
            if (pendingSecond < 0) {
                pendingSecond = second;
                pendingMaxId = maxId;
            } else if (second == pendingSecond) {
                pendingMaxId = Math.max(pendingMaxId, maxId);
            }
        }

        long lastDrained() { return lastDrained; }
        long pendingSecond() { return pendingSecond; }
        long pendingMaxId() { return pendingMaxId; }
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
    public static synchronized int sync(Context ctx) throws IOException {
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
            selection = buildResumeSelection();
            args = new String[]{String.valueOf(pendingSecond), String.valueOf(pendingSecond),
                    String.valueOf(pendingMaxId)};
        } else {
            selection = MediaStore.Images.Media.DATE_ADDED + " > ?";
            args = new String[]{String.valueOf(lastSync)};
        }

        // 限幅查询：SQL 级 LIMIT（MAX_PER_RUN+1），不再全量 COUNT 统计。
        // Tracker 必须在 query 之前创建：查询/Cursor 异常也要保留已有 pending 水位。
        CheckpointTracker checkpoint = new CheckpointTracker(lastSync, pendingSecond, pendingMaxId);
        int count = 0;
        int failures = 0;
        int processed = 0;
        boolean truncated = false;
        long truncatedSecond = -1;
        boolean aborted = false;
        boolean interruptedAbort = false;
        boolean checkpointCommitted = false;
        Cursor c = null;
        running = true;
        phase = "scanning";
        currentFile = "";
        uploaded = 0;
        total = 0;
        emitProgress();
        try {
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
                        MediaStore.Images.Media.DATE_ADDED + " ASC, " + MediaStore.Images.Media._ID
                                + " ASC LIMIT " + (MAX_PER_RUN + 1));
            }
            if (c == null) {
                throw new IOException("无法读取相册");
            }
            int rows = c.getCount();
            boolean drained = rows < MAX_PER_RUN + 1;
            total = Math.min(rows, MAX_PER_RUN); // +1 行只用于截断边界探测
            phase = total == 0 ? "done" : "uploading";
            emitProgress();

            while (c.moveToNext()) {
                long ts = c.getLong(2);
                if (processed >= MAX_PER_RUN) {
                    // 第 MAX+1 行只用于判断截断发生在哪一秒，不上传。
                    truncated = true;
                    truncatedSecond = ts;
                    break;
                }
                processed++;
                // 先以已读到的真实秒 begin，再读 _ID：随后任何字段读取异常都会由
                // 外层 catch 以该真实秒挂 pending，而不是把已完成的上一组误标失败。
                checkpoint.begin(ts, ts == pendingSecond ? pendingMaxId : 0);
                long id = c.getLong(0);

                String name = c.getString(1);
                if (name == null || name.trim().isEmpty()) {
                    name = "photo-" + id + ".jpg";
                }
                String mime = c.getString(3);
                if (mime == null || mime.isEmpty()) {
                    mime = "image/jpeg";
                }
                String dateDir = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts * 1000L));
                String relFolder = folder + "/" + dateDir;

                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                if (Thread.currentThread().isInterrupted()) {
                    aborted = true;
                    interruptedAbort = true;
                    checkpoint.failure();
                    break;
                }
                try {
                    uploadOne(ctx, base, relFolder, name, mime, uri, deviceToken);
                    count++;
                    checkpoint.success(id);
                    uploaded = count;
                    currentFile = name;
                    emitProgress();          // JS 实时进度
                    notifyProgress(ctx);      // 通知栏进度（节流）
                } catch (PermanentSkipException e) {
                    checkpoint.skip(id);
                    currentFile = name;
                    emitProgress();
                } catch (AbortBatchException e) {
                    aborted = true;           // 连接级失败：中止整批
                    checkpoint.failure();     // 当前秒挂 pending，检查点不能越过
                    break;
                } catch (Exception error) {
                    LocalMediaResult localResult = classifyLocalMediaFailure(
                            error, Thread.currentThread().isInterrupted());
                    if (localResult == LocalMediaResult.SKIP) {
                        checkpoint.skip(id);
                        currentFile = name;
                        emitProgress();
                    } else if (localResult == LocalMediaResult.ABORT) {
                        Thread.currentThread().interrupt();
                        aborted = true;
                        interruptedAbort = true;
                        checkpoint.failure();
                        break;
                    } else {
                        failures++;           // 普通本地 I/O：同秒冻结，下轮重试
                        checkpoint.failure();
                    }
                }
            }
            if (c != null) {
                c.close();
                c = null;
            }

            if (aborted) {
                checkpoint.finishGroup(false);
            } else if (truncated) {
                checkpoint.truncatedAt(truncatedSecond);
            } else {
                checkpoint.finishGroup(drained);
                if (drained) {
                    checkpoint.finishEmpty();
                }
            }

            // 单次加密 prefs commit 原子发布完整检查点；失败后的更晚成功秒不会越过最早失败秒。
            ServerConfigStore.setCheckpoint(
                    ctx,
                    Math.max(lastSync, checkpoint.lastDrained()),
                    checkpoint.pendingSecond(),
                    checkpoint.pendingMaxId());
            checkpointCommitted = true;

            if (aborted) {
                ServerConfigStore.setLastError(ctx, interruptedAbort
                        ? "相册同步已中断，本批未完成"
                        : "网络或服务器异常，已中止本批，将自动重试");
            } else if (failures > 0) {
                ServerConfigStore.setLastError(ctx, failures + " 张上传失败，将自动重试");
            } else {
                ServerConfigStore.setLastError(ctx, null);
            }
            return count;
        } catch (Exception error) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception closeError) {
                    error.addSuppressed(closeError);
                }
            }
            if (!checkpointCommitted) {
                // query/Cursor/读字段异常也不能让已成功的当前秒丢失水位；若尚未开始
                // 当前秒，Tracker 会原样保留上一轮 pending，下一轮仍会重试。
                checkpoint.failure();
                checkpoint.finishGroup(false);
                try {
                    ServerConfigStore.setCheckpoint(
                            ctx,
                            Math.max(lastSync, checkpoint.lastDrained()),
                            checkpoint.pendingSecond(),
                            checkpoint.pendingMaxId());
                    checkpointCommitted = true;
                } catch (Exception checkpointError) {
                    error.addSuppressed(checkpointError);
                }
            }
            try {
                ServerConfigStore.setLastError(ctx, "相册扫描/检查点异常，将自动重试");
            } catch (Exception stateError) {
                error.addSuppressed(stateError);
            }
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("相册同步失败", error);
        } finally {
            running = false;
            phase = "done";
            emitProgress();
        }
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
                md5 = copyAndDigest(in, out);
            }

            // 先查服务端已验证索引；命中则真正免传，未命中再上传并由服务端复算 MD5。
            if (dedupeHit(base, md5, deviceToken)) {
                return true;
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

    static String copyAndDigest(InputStream in, OutputStream out) throws Exception {
        if (in == null) {
            throw new FileNotFoundException("相册文件已删除或无法读取");
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (n == 0) {
                int single = in.read();
                if (single == -1) break;
                digest.update((byte) single);
                out.write(single);
                continue;
            }
            digest.update(buf, 0, n);
            out.write(buf, 0, n);
        }
        return hex(digest.digest());
    }

    private static boolean dedupeHit(String base, String md5, String deviceToken) throws IOException {
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) new URL(base + "/files/dedupe?md5=" + md5).openConnection();
        } catch (IOException e) {
            throw new AbortBatchException("秒传预检网络失败", e);
        }
        try {
            conn.setRequestMethod("GET");
            if (deviceToken != null && !deviceToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + deviceToken);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code;
            try {
                code = conn.getResponseCode();
            } catch (IOException e) {
                throw new AbortBatchException("秒传预检网络失败", e);
            }
            switch (classifySyncStatus(code, false)) {
                case SUCCESS:
                    drainQuietly(conn.getInputStream());
                    return true;
                case MISS:
                    return false;
                case ABORT:
                    throw new AbortBatchException("秒传预检失败 HTTP " + code, null);
                case SKIP:
                    throw new PermanentSkipException("秒传预检被服务端拒绝 HTTP " + code);
                default:
                    throw new IOException("秒传预检失败 HTTP " + code); // 其他 4xx 冻结水位，下轮重试
            }
        } finally {
            conn.disconnect();
        }
    }

    /** multipart/form-data：path=查询参数（已拼好），md5/noclobber=表单字段，文件 part 名 file。 */
    private static void uploadFile(String uploadUrl, String fileName, String mime, InputStream in,
                                  String md5, String deviceToken) throws IOException {
        String boundary = "----AgentDriveBoundary" + System.currentTimeMillis();
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
        } catch (IOException e) {
            throw new AbortBatchException("网络连接失败", e);
        }
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
        } catch (IOException e) {
            conn.disconnect();
            throw new AbortBatchException("网络连接失败", e);
        }
        int code;
        try {
            code = conn.getResponseCode();
        } catch (IOException e) {
            conn.disconnect();
            throw new AbortBatchException("网络连接失败", e); // 断网：整批中止而非 200 张串行超时
        }
        // 消费响应实体以释放连接；不读正文也保持连接可复用。
        if (code >= 200 && code < 300) {
            drainQuietly(conn.getInputStream());
        } else {
            drainQuietly(conn.getErrorStream());
        }
        conn.disconnect();
        SyncResult result = classifySyncStatus(code, true);
        if (result == SyncResult.ABORT) {
            throw new AbortBatchException("服务器拒绝/异常 HTTP " + code, null); // 令牌失效或服务端故障：整批中止
        }
        if (result == SyncResult.SKIP) {
            throw new PermanentSkipException("上传被服务端拒绝 HTTP " + code); // 永久 4xx：跳过并推进水位
        }
        if (result == SyncResult.RETRY) {
            throw new IOException("上传失败 HTTP " + code); // 其他 4xx：冻结水位，下轮重试
        }
    }

    /** 排空响应实体（丢弃正文），任何读取错误都忽略：连接随后 disconnect。 */
    private static void drainQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try (InputStream s = in) {
            byte[] buf = new byte[4096];
            while (s.read(buf) != -1) {
                // 丢弃正文，只为完成实体消费
            }
        } catch (IOException ignored) {
            // 响应正文读取失败不影响已完成的请求语义
        }
    }

    private static void writePart(OutputStream os, String boundary, String field, String valueOrName,
                                  String mime, InputStream in) throws IOException {
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"";
        if (mime != null) {
            String safeName = valueOrName.replace("\\", "_").replace("/", "_")
                    .replace("\r", "_").replace("\n", "_").replace("\"", "_");
            String safeMime = mime.replace("\r", "").replace("\n", "");
            head += "; filename=\"" + safeName + "\"\r\nContent-Type: " + safeMime;
        }
        os.write((head + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n == 0) {
                    int single = in.read();
                    if (single == -1) break;
                    os.write(single);
                    continue;
                }
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
