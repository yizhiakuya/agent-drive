"use client";
import { useEffect, useState } from "react";
import { Capacitor } from "@capacitor/core";
import { PhotoSync, PhotoSyncStatus } from "@/lib/native/photo-sync";

const INTERVALS = [1, 6, 12, 24];

/** 相册自动同步（仅原生 App 显示）：WorkManager 后台周期上传新照片到网盘。 */
export default function PhotoSyncCard() {
  const [native] = useState(() => Capacitor.isNativePlatform());
  const [st, setSt] = useState<PhotoSyncStatus | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      setSt(await PhotoSync.getStatus());
    } catch (e) {
      setMsg(String(e));
    }
  }
  useEffect(() => {
    if (native) load();
  }, [native]);

  async function apply(patch: { enabled?: boolean; wifiOnly?: boolean; intervalHours?: number }) {
    setBusy(true);
    setMsg(null);
    try {
      setSt(await PhotoSync.configure(patch));
      setMsg("✅ 已保存");
    } catch (e) {
      setMsg(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function syncNow() {
    setBusy(true);
    setMsg(null);
    try {
      await PhotoSync.requestPermissions();
      const r = await PhotoSync.syncNow();
      setMsg(r.started ? "🔄 后台同步已启动，完成后通知" : "未启动");
      load();
    } catch (e) {
      setMsg(String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!native) return null;

  const fmt = (ts: number | null) => (ts ? new Date(ts * 1000).toLocaleString() : "从未");

  return (
    <div className="bg-panel border border-border rounded-xl p-4 mb-4">
      <h3 className="font-bold text-sm mb-1">📸 相册自动同步</h3>
      <p className="text-muted text-xs mb-3">新照片后台自动上传到网盘（App 关闭也运行）。</p>

      <label className="flex items-center gap-2 text-sm mb-2 cursor-pointer">
        <input type="checkbox" checked={st?.enabled ?? false} disabled={busy}
               onChange={(e) => apply({ enabled: e.target.checked })} />
        启用自动同步
      </label>

      {st?.enabled && (
        <>
          <label className="flex items-center gap-2 text-sm mb-2 cursor-pointer">
            <input type="checkbox" checked={st.wifiOnly} disabled={busy}
                   onChange={(e) => apply({ wifiOnly: e.target.checked })} />
            仅 Wi-Fi 时同步
          </label>
          <label className="flex items-center gap-2 text-sm mb-2">
            <span className="text-muted text-xs w-16">频率</span>
            <select value={st.intervalHours} disabled={busy}
                    onChange={(e) => apply({ intervalHours: Number(e.target.value) })}
                    className="px-2 py-1.5 border border-border rounded-lg text-sm bg-panel">
              {INTERVALS.map((h) => (
                <option key={h} value={h}>每 {h} 小时</option>
              ))}
            </select>
          </label>
        </>
      )}

      <div className="flex items-center gap-2 mt-3">
        <button className="bg-accent text-white px-4 py-2 rounded-lg text-sm font-semibold cursor-pointer disabled:opacity-60"
                onClick={syncNow} disabled={busy || !st?.enabled}>
          {busy ? "执行中…" : "立即同步"}
        </button>
        {st && (
          <span className="text-muted text-[10px]">
            上次：{fmt(st.lastSyncAt)} · 同步 {Math.max(st.lastSyncedCount, 0)} 张
            {st.lastError ? ` · 最近错误: ${st.lastError}` : ""}
          </span>
        )}
      </div>
      {msg && <p className="text-xs mt-2 text-muted">{msg}</p>}
    </div>
  );
}
