package top.rainaki.agentdrive;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 相册同步引擎：扫描 MediaStore 新增照片 → multipart 上传到网盘 /files/upload。 */
public final class SyncEngine {

    /** 单次运行上限，防首启全量同步过长。 */
    private static final int MAX_PER_RUN = 200;

    private SyncEngine() {
    }

    /** @return 本次成功上传张数 */
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

        ContentResolver cr = ctx.getContentResolver();
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
        };
        String selection = MediaStore.Images.Media.DATE_ADDED + " > ?";
        String[] args = {String.valueOf(lastSync)};

        Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
                MediaStore.Images.Media.DATE_ADDED + " ASC");
        if (c == null) {
            throw new IOException("无法读取相册");
        }

        String deviceToken = ServerConfigStore.getDeviceToken(ctx);
        int count = 0;
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
                try (InputStream in = cr.openInputStream(uri)) {
                    if (in == null) {
                        continue;
                    }
                    uploadFile(base + "/files/upload", relFolder, name, mime, in, deviceToken);
                }
                maxTs = Math.max(maxTs, ts);
                count++;
            }
        } finally {
            c.close();
        }

        if (maxTs > lastSync) {
            ServerConfigStore.setLastSyncAt(ctx, maxTs);
        }
        return count;
    }

    /** multipart/form-data：path=目标文件夹，文件 part 名 file（后端以 filename 拼接完整路径）。 */
    private static void uploadFile(String uploadUrl, String folder, String fileName, String mime, InputStream in,
                                  String deviceToken) throws IOException {
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
            writePart(os, boundary, "path", folder, null, null);
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
}
