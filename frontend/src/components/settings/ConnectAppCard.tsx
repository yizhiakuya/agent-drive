"use client";
import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { Capacitor } from "@capacitor/core";
import { ServerConfig, currentServer } from "@/lib/native/server-config";

/** 连接手机 App：web 端展示二维码（agentdrive://connect?server=...），原生端显示当前连接并可重扫。 */
export default function ConnectAppCard() {
  const [qr, setQr] = useState<string | null>(null);
  const [server, setServer] = useState("");
  const [native, setNative] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    setNative(Capacitor.isNativePlatform());
    (async () => {
      const s = await currentServer();
      setServer(s);
      if (s) {
        const content = `agentdrive://connect?server=${encodeURIComponent(s)}`;
        setQr(await QRCode.toDataURL(content, { margin: 1, width: 220 }));
      }
    })();
  }, []);

  async function rescan() {
    setBusy(true);
    setMsg(null);
    try {
      await ServerConfig.rescan();
    } catch (e) {
      setMsg(String(e));
    }
    setBusy(false);
  }

  return (
    <div className="bg-panel border border-border rounded-xl p-4 mb-4">
      <h3 className="font-bold text-sm mb-1">📱 连接手机 App</h3>
      {native ? (
        <div className="text-sm">
          <p className="text-muted text-xs mb-2">
            当前服务器：<span className="text-text font-mono">{server || "未配置"}</span>
          </p>
          <button className="bg-accent text-white px-4 py-2 rounded-lg text-sm font-semibold cursor-pointer disabled:opacity-60"
                  onClick={rescan} disabled={busy}>
            {busy ? "等待扫码…" : "重新扫码连接"}
          </button>
          {msg && <p className="text-danger text-xs mt-2">{msg}</p>}
        </div>
      ) : (
        <div className="flex flex-col items-center gap-2">
          <p className="text-muted text-xs text-center">
            手机 App 首次打开时扫码即可连到本服务器：
            <br />服务器地址 <span className="font-mono text-text">{server}</span>
          </p>
          {qr
            ? <img src={qr} alt="扫码连接服务器" className="w-44 h-44 rounded-lg border border-border" />
            : <p className="text-muted text-xs">生成中…</p>}
          <p className="text-muted text-[10px]">App 内重新扫码：设置 → 连接手机 App → 重新扫码连接</p>
        </div>
      )}
    </div>
  );
}
