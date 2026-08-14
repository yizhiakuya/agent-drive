package top.rainaki.agentdrive;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.util.List;

/** 扫码连接页：解析 agentdrive://connect?server=... 二维码（网页「连接手机 App」生成）。 */
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
        if (server == null || server.trim().isEmpty()) {
            handled = false;
            Toast.makeText(this, "二维码缺少服务器地址", Toast.LENGTH_LONG).show();
            return;
        }
        ServerConfigStore.setServer(getApplicationContext(), server.trim());
        Toast.makeText(this, "已连接：" + server, Toast.LENGTH_SHORT).show();

        if (getIntent().getBooleanExtra(EXTRA_RESCAN, false)) {
            setResult(RESULT_OK);
            finish();
        } else {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
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
