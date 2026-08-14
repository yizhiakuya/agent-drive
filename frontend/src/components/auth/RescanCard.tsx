"use client";
import { useState } from "react";
import { ServerConfig } from "@/lib/native/server-config";

/** 原生 App：无有效凭据时的重新扫码卡（主路径扫码；密码登录作逃生口）。 */
export default function RescanCard({ hint, onPasswordFallback }: { hint?: string; onPasswordFallback: () => void }) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  async function scan() {
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
    <div className="h-screen flex items-center justify-center bg-panel p-4">
      <div className="bg-card border border-border rounded-2xl p-6 w-full max-w-sm text-center">
        <div className="text-4xl mb-2">📷</div>
        <h1 className="text-lg font-bold">连接服务器</h1>
        <p className="text-muted text-xs mb-4 mt-1">
          {hint || "在网页「设置 → 连接手机 App」打开二维码，扫码即授权（免密码）"}
        </p>
        <button onClick={scan} disabled={busy}
                className="w-full bg-accent text-white px-4 py-2.5 rounded-lg text-sm font-semibold cursor-pointer disabled:opacity-60">
          {busy ? "等待扫码…" : "打开扫码"}
        </button>
        <button onClick={onPasswordFallback}
                className="w-full text-muted text-xs mt-3 cursor-pointer hover:text-text">
          无法扫码？使用密码登录
        </button>
        {msg && <p className="text-danger text-xs mt-2">{msg}</p>}
      </div>
    </div>
  );
}
