package top.rainaki.agentdrive;

import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** 服务器连接配置桥：扫码连接 / 重新扫码 / 读写服务器地址。 */
@CapacitorPlugin(name = "ServerConfig")
public class ServerConfigPlugin extends Plugin {

    private void rejectStorage(PluginCall call, RuntimeException error) {
        call.reject("安全配置存储不可用：" + error.getMessage());
    }

    @PluginMethod
    public void getServer(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("server", ServerConfigStore.getServer(getContext()));
            call.resolve(ret);
        } catch (RuntimeException e) {
            rejectStorage(call, e);
        }
    }

    @PluginMethod
    public void setServer(PluginCall call) {
        String server = call.getString("server");
        if (server == null || server.trim().isEmpty()) {
            call.reject("server 不能为空");
            return;
        }
        String normalized = ScanActivity.validateServer(server);
        if (normalized == null) {
            call.reject("server 必须是无凭据、无查询参数的 HTTPS 地址");
            return;
        }
        try {
            ServerConfigStore.setServer(getContext(), normalized);
            JSObject ret = new JSObject();
            ret.put("server", ServerConfigStore.getServer(getContext()));
            call.resolve(ret);
        } catch (RuntimeException e) {
            rejectStorage(call, e);
        }
    }

    @PluginMethod
    public void hasServer(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("has", ServerConfigStore.isConfigured(getContext()));
            call.resolve(ret);
        } catch (RuntimeException e) {
            rejectStorage(call, e);
        }
    }

    /** 心跳：App 回前台时由前端触发，刷新 web 端设备列表的活跃时间。 */
    @PluginMethod
    public void heartbeat(PluginCall call) {
        try {
            DeviceRegistrar.register(getContext(), true);
            JSObject ret = new JSObject();
            ret.put("sent", true);
            call.resolve(ret);
        } catch (RuntimeException e) {
            rejectStorage(call, e);
        }
    }

    @PluginMethod
    public void getDeviceId(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("deviceId", ServerConfigStore.getDeviceId(getContext()));
            call.resolve(ret);
        } catch (RuntimeException e) {
            rejectStorage(call, e);
        }
    }

    @PluginMethod
    public void getDeviceToken(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            ret.put("token", ServerConfigStore.getDeviceToken(getContext()));
            call.resolve(ret);
        } catch (RuntimeException e) {
            rejectStorage(call, e);
        }
    }

    @PluginMethod
    public void storeDeviceToken(PluginCall call) {
        String token = call.getString("token");
        if (token == null || token.trim().isEmpty()) {
            call.reject("token 不能为空");
            return;
        }
        try {
            ServerConfigStore.setDeviceToken(getContext(), token.trim());
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (RuntimeException e) {
            rejectStorage(call, e);
        }
    }

    @PluginMethod
    public void clearDeviceToken(PluginCall call) {
        try {
            ServerConfigStore.clearDeviceToken(getContext());
            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (RuntimeException e) {
            rejectStorage(call, e);
        }
    }

    /** 重新扫码：打开原生扫码页，成功后 MainActivity 重载 web 界面。 */
    @PluginMethod
    public void rescan(PluginCall call) {
        if (getActivity() == null) {
            call.reject("activity 不可用");
            return;
        }
        getActivity().runOnUiThread(() -> {
            Intent intent = new Intent(getActivity(), ScanActivity.class);
            intent.putExtra(ScanActivity.EXTRA_RESCAN, true);
            getActivity().startActivityForResult(intent, MainActivity.REQ_SCAN);
            JSObject ret = new JSObject();
            ret.put("started", true);
            call.resolve(ret);
        });
    }
}
