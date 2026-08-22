package top.rainaki.agentdrive;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;

/**
 * 扫码连接页：解析 agentdrive://connect?server=...&pair=... 二维码。
 * 扫码即授权：配对码（一次性 5 分钟）直接兑换长期设备令牌，无需输入密码。
 */
public class ScanActivity extends Activity {

    public static final String EXTRA_RESCAN = "rescan";
    private static final int REQ_CAMERA = 99;
    /** 配对交换只返回很小的 JSON；限制响应体避免恶意服务器耗尽 App 内存。 */
    static final int MAX_EXCHANGE_RESPONSE_BYTES = 64 * 1024;

    private DecoratedBarcodeView barcodeView;
    private boolean handled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        barcodeView = findViewById(R.id.barcode_scanner);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (!handled) {
                    handled = true;
                    handleQr(result.getText());
                }
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {
            }
        });
    }

    private void handleQr(String text) {
        Uri uri = Uri.parse(text);
        if (!"agentdrive".equals(uri.getScheme()) || !"connect".equals(uri.getHost())) {
            handled = false;
            Toast.makeText(this, "二维码无效：请扫描网页「连接手机 App」的二维码", Toast.LENGTH_LONG).show();
            return;
        }
        String server = uri.getQueryParameter("server");
        String pair = uri.getQueryParameter("pair");
        if (server == null || server.trim().isEmpty()) {
            handled = false;
            Toast.makeText(this, "二维码缺少服务器地址", Toast.LENGTH_LONG).show();
            return;
        }
        if (pair == null || pair.trim().isEmpty()) {
            handled = false;
            Toast.makeText(this, "二维码缺少授权码：请在网页刷新二维码后重扫", Toast.LENGTH_LONG).show();
            return;
        }

        final String fServer = validateServer(server);
        if (fServer == null) {
            handled = false;
            Toast.makeText(this, "二维码服务器地址不安全：仅支持有效的 HTTPS 地址", Toast.LENGTH_LONG).show();
            return;
        }
        final String fPair = pair.trim();
        Toast.makeText(this, "正在授权连接…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            ExchangeResult result = tryExchange(fServer, fPair);
            runOnUiThread(() -> {
                if (result.token == null) {
                    handled = false;  // 失败可继续扫
                    Toast.makeText(this, result.error, Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    ServerConfigStore.setConnection(getApplicationContext(), fServer, result.token);
                } catch (RuntimeException e) {
                    handled = false;
                    Toast.makeText(this, "授权成功，但安全存储失败；未保存设备令牌", Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "已连接并授权", Toast.LENGTH_SHORT).show();
                if (getIntent().getBooleanExtra(EXTRA_RESCAN, false)) {
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                }
            });
        }).start();
    }

    /** 兑换配对码 → 设备令牌。失败返回可展示的错误信息。 */
    private ExchangeResult tryExchange(String server, String pair) {
        HttpURLConnection conn = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("code", pair);
            payload.put("device_id", ServerConfigStore.getDeviceId(this));
            payload.put("name", Build.MANUFACTURER + " " + Build.MODEL);
            String url = server.replaceAll("/+$", "") + "/api/v1/auth/pair-exchange";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            // 配对码是一次性授权凭据；绝不能跟随 30x 把它转发到另一台服务器。
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String body = new String(readAll(conn), StandardCharsets.UTF_8);
            if (code == 200) {
                String token = new JSONObject(body).optString("token", null);
                if (token != null && !token.isEmpty()) {
                    return new ExchangeResult(token, null);
                }
                return new ExchangeResult(null, "服务器响应异常");
            }
            String detail = new JSONObject(body).optString("detail", "HTTP " + code);
            return new ExchangeResult(null, detail);
        } catch (Exception e) {
            return new ExchangeResult(null, "无法连接服务器：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 校验并规范化二维码携带的服务器地址。
     *
     * <p>配对码会被发送到该地址，因此只接受 HTTPS、无用户凭据/查询片段的绝对 URI，
     * 并拒绝回环、链路本地和未指定地址。允许站点内网地址，便于自托管设备在局域网使用。</p>
     *
     * @param server 二维码中的服务器地址。
     * @return 去除首尾空白和末尾斜杠后的安全地址；非法时返回 {@code null}。
     */
    static String validateServer(String server) {
        if (server == null) return null;
        String candidate = server.trim();
        if (candidate.isEmpty() || candidate.length() > 2048) return null;
        try {
            URI uri = new URI(candidate);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() < -1
                    || uri.getPort() > 65535) {
                return null;
            }
            String host = uri.getHost();
            if (!isSafeHost(host)) return null;
            String normalized = candidate;
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isSafeHost(String host) {
        String lower = host.toLowerCase(Locale.US);
        if (lower.isEmpty()
                || lower.equals("localhost")
                || lower.endsWith(".localhost")
                || lower.endsWith(".local")
                || lower.equals("0.0.0.0")
                || lower.equals("::")) {
            return false;
        }
        // URI#getHost() returns bracket-free IPv6 on current Android releases; accept only
        // hexadecimal/colon literals and reject local/link-local/unspecified destinations.
        boolean ipv6 = host.indexOf(':') >= 0;
        boolean ipv4 = host.matches("[0-9.]+");
        if (!ipv6 && !ipv4 && !host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) {
            return false;
        }
        if (ipv6 || ipv4) {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readAll(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            return readLimited(in, MAX_EXCHANGE_RESPONSE_BYTES);
        } catch (java.io.IOException e) {
            // 4xx/5xx：body 在 errorStream
            try (InputStream err = conn.getErrorStream()) {
                if (err != null) return readLimited(err, MAX_EXCHANGE_RESPONSE_BYTES);
            }
            throw e;
        }
    }

    /** 以固定缓冲区读取最多 {@code maxBytes}，避免依赖 API 33 的 readAllBytes。 */
    static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        if (input == null || maxBytes <= 0) throw new IllegalArgumentException("无效响应读取上限");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > maxBytes - total) {
                throw new IOException("服务器响应过大");
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private static class ExchangeResult {
        final String token;
        final String error;

        ExchangeResult(String token, String error) {
            this.token = token;
            this.error = error;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            barcodeView.resume();
        } else if (requestCode == REQ_CAMERA) {
            handled = false;
            Toast.makeText(this, "需要相机权限才能扫码；可在系统设置中允许后重试", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        barcodeView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        barcodeView.pause();
    }
}
