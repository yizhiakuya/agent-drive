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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 扫码连接页：解析 agentdrive://connect?server=...&pair=... 二维码。
 * 扫码即授权：配对码（一次性 5 分钟）直接兑换长期设备令牌，无需输入密码。
 */
public class ScanActivity extends Activity {

    public static final String EXTRA_RESCAN = "rescan";
    private static final int REQ_CAMERA = 99;

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

        final String fServer = server.trim();
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

    private static byte[] readAll(HttpURLConnection conn) throws java.io.IOException {
        try (java.io.InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            // 4xx/5xx：body 在 errorStream
            try (java.io.InputStream err = conn.getErrorStream()) {
                if (err != null) return err.readAllBytes();
            }
            throw e;
        }
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