"use client";
import { useRescan } from "@/lib/native/useRescan";
import { Button } from "@/components/ui/button";

/** 原生 App：无有效凭据时的重新扫码卡（主路径扫码；密码登录作逃生口）。 */
export default function RescanCard({ hint, onPasswordFallback }: { hint?: string; onPasswordFallback: () => void }) {
  const { busy, msg, rescan: scan } = useRescan();

  return (
    <div className="h-screen flex items-center justify-center bg-panel p-4">
      <div className="bg-card border border-border rounded-2xl p-6 w-full max-w-sm text-center">
        <div className="text-4xl mb-2">📷</div>
        <h1 className="text-lg font-bold">连接服务器</h1>
        <p className="text-muted text-xs mb-4 mt-1">
          {hint || "在网页「设置 → 连接手机 App」打开二维码，扫码即授权（免密码）"}
        </p>
        <Button onClick={scan} disabled={busy}
                className="w-full py-2.5 text-sm font-semibold">
          {busy ? "等待扫码…" : "打开扫码"}
        </Button>
        <Button onClick={onPasswordFallback}
                variant="link"
                className="w-full text-muted text-xs mt-3 hover:text-text">
          无法扫码？使用密码登录
        </Button>
        {msg && <p className="text-danger text-xs mt-2">{msg}</p>}
      </div>
    </div>
  );
}
