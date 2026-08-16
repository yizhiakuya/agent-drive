"use client";
import { useState } from "react";
import { Capacitor } from "@capacitor/core";
import { apiErrorMessage, authenticatedFetch, setDeviceToken } from "@/lib/api/client";
import { ServerConfig } from "@/lib/native/server-config";

/** 登录/设密页：web 与原生 App 共用（App 登录成功后额外颁发设备令牌存原生）。 */
export default function LoginCard({ mode, onDone }: { mode: "setup" | "login"; onDone: () => void }) {
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  const native = Capacitor.isNativePlatform();

  async function submit() {
    if (mode === "setup" && pw !== pw2) { setMsg("两次输入的密码不一致"); return; }
    if (mode === "setup" && pw.length < 8) { setMsg("密码至少 8 位"); return; }
    setBusy(true);
    setMsg(null);
    try {
      const endpoint = mode === "setup" ? "/auth/setup" : "/auth/login";
      const res = await authenticatedFetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password: pw }),
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) { setMsg(apiErrorMessage(body, `HTTP ${res.status}`)); return; }

      if (native) {
        // 跨域拿不到 Cookie：用响应体的 session 令牌换设备令牌（后台同步鉴权）
        const session = (body as { session?: unknown }).session;
        if (typeof session !== "string" || !session) {
          setMsg("服务器未返回可用会话令牌");
          return;
        }
        const { deviceId } = await ServerConfig.getDeviceId();
        const r2 = await authenticatedFetch("/auth/device-token", {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${session}` },
          body: JSON.stringify({ device_id: deviceId, name: "安卓设备" }),
        });
        const b2 = await r2.json().catch(() => ({}));
        const token = (b2 as { token?: unknown }).token;
        if (!r2.ok || typeof token !== "string" || !token) {
          setMsg(apiErrorMessage(b2, `设备授权失败 HTTP ${r2.status}`));
          return;
        }
        await ServerConfig.storeDeviceToken({ token });
        setDeviceToken(token);
      }
      onDone();
    } catch (e) {
      setMsg(String(e));
    } finally {
      setBusy(false);
    }
  }

  const isSetup = mode === "setup";
  return (
    <div className="h-screen flex items-center justify-center bg-panel p-4">
      <div className="bg-card border border-border rounded-2xl p-6 w-full max-w-sm">
        <div className="text-3xl mb-2">🦋</div>
        <h1 className="text-lg font-bold">Agent Drive</h1>
        <p className="text-muted text-xs mb-4 mt-1">
          {isSetup ? "首次使用：设置主人密码（第一个设置者成为主人）" : "输入密码解锁网盘"}
        </p>
        <input type="password" value={pw} placeholder="密码（至少 8 位）"
               onChange={(e) => setPw(e.target.value)} onKeyDown={(e) => e.key === "Enter" && submit()}
               className="w-full px-3 py-2.5 border border-border rounded-lg text-sm mb-2.5 focus:outline-none focus:ring-2 focus:ring-accent-soft" />
        {isSetup && (
          <input type="password" value={pw2} placeholder="确认密码"
                 onChange={(e) => setPw2(e.target.value)} onKeyDown={(e) => e.key === "Enter" && submit()}
                 className="w-full px-3 py-2.5 border border-border rounded-lg text-sm mb-2.5 focus:outline-none focus:ring-2 focus:ring-accent-soft" />
        )}
        <button onClick={submit} disabled={busy || !pw}
                className="w-full bg-accent text-white px-4 py-2.5 rounded-lg text-sm font-semibold cursor-pointer disabled:opacity-60">
          {busy ? "处理中…" : isSetup ? "设置密码并进入" : "登录"}
        </button>
        {msg && <p className="text-danger text-xs mt-2.5">{msg}</p>}
      </div>
    </div>
  );
}
