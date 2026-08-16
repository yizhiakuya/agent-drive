"use client";
import { useEffect, useRef, useState } from "react";
import QRCode from "qrcode";
import { Capacitor } from "@capacitor/core";
import { getPairing } from "@/lib/api/auth";
import { currentServer } from "@/lib/native/server-config";
import { useRescan } from "@/lib/native/useRescan";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

/**
 * 连接手机 App：web 端展示带一次性授权码的二维码（扫码即授权，免密码）；
 * 原生端显示当前连接并可重扫。
 */
export default function ConnectAppCard() {
  const [qr, setQr] = useState<string | null>(null);
  const [server, setServer] = useState("");
  const [native] = useState(() => Capacitor.isNativePlatform());
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [left, setLeft] = useState(0); // 二维码剩余秒数
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const { busy: rescanBusy, msg: rescanMsg, rescan } = useRescan();

  useEffect(() => {
    currentServer().then(setServer);
    if (!Capacitor.isNativePlatform()) refresh();
    return () => { if (timer.current) clearInterval(timer.current); };
  }, []);

  async function refresh() {
    setBusy(true);
    setMsg(null);
    try {
      const info = await getPairing();
      const origin = window.location.origin;
      const content = `agentdrive://connect?server=${encodeURIComponent(origin)}&pair=${info.code}`;
      setQr(await QRCode.toDataURL(content, { margin: 1, width: 260 }));
      setLeft(info.expires_in);
    } catch (e) {
      setMsg(String(e));
    } finally {
      setBusy(false);
    }
  }

  // 倒计时 + 到期自动换新码
  useEffect(() => {
    if (timer.current) clearInterval(timer.current);
    if (left <= 0 || native) return;
    timer.current = setInterval(() => {
      setLeft((s) => {
        if (s <= 1) {
          refresh();
          return 0;
        }
        return s - 1;
      });
    }, 1000);
    return () => { if (timer.current) clearInterval(timer.current); };
  }, [left, native]);

  return (
    <div className="bg-panel border border-border rounded-xl p-4 mb-4">
      <h3 className="font-bold text-sm mb-1">📱 连接手机 App</h3>
      {native ? (
        <div className="text-sm">
          <p className="text-muted text-xs mb-2">
            当前服务器：<span className="text-text font-mono">{server || "未配置"}</span>
          </p>
          <Button onClick={rescan} disabled={rescanBusy}>
            {rescanBusy ? "等待扫码…" : "重新扫码连接"}
          </Button>
          {rescanMsg && <p className="text-danger text-xs mt-2">{rescanMsg}</p>}
        </div>
      ) : (
        <div className="flex flex-col items-center gap-2">
          <p className="text-muted text-xs text-center">
            手机 App 打开后扫此码即可连接并授权（免输入密码）：
            <br />服务器地址 <span className="font-mono text-text">{server}</span>
          </p>
          {qr
            ? <>
                <img src={qr} alt="扫码连接服务器" className="w-52 h-52 rounded-lg border border-border" />
                <div className="flex items-center gap-2">
                  <Badge variant="outline" className="text-muted">
                    {left > 0 ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")} 后过期` : "已过期"}
                  </Badge>
                  <Button variant="ghost" size="sm" onClick={refresh} disabled={busy}>
                    {busy ? "生成中…" : "刷新二维码"}
                  </Button>
                </div>
              </>
            : <p className="text-muted text-xs">生成中…</p>}
          <Button asChild>
            <a href="/app/agent-drive.apk" download>
              📲 下载安卓 App（APK）
            </a>
          </Button>
          <p className="text-muted text-[10px]">扫码即授权（一次性 5 分钟）；App 内重扫：设置 → 连接手机 App</p>
          {msg && <p className="text-danger text-xs">{msg}</p>}
        </div>
      )}
    </div>
  );
}
