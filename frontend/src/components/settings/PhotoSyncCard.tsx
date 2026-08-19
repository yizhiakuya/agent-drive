"use client";
import { useEffect, useState } from "react";
import { Capacitor } from "@capacitor/core";
import { PhotoSync, PhotoSyncStatus, SyncProgress } from "@/lib/native/photo-sync";
import { EV } from "@/lib/events";
import { fmtTime } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Alert } from "@/components/ui/alert";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Camera, Clock3, RefreshCw, Upload, Wifi } from "lucide-react";

const INTERVALS = [1, 6, 12, 24];

/** 相册自动同步（仅原生 App 显示）：WorkManager 后台周期上传新照片到网盘。 */
export default function PhotoSyncCard() {
  const [native] = useState(() => Capacitor.isNativePlatform());
  const [st, setSt] = useState<PhotoSyncStatus | null>(null);
  const [progress, setProgress] = useState<SyncProgress | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    try {
      const s = await PhotoSync.getStatus();
      setSt(s);
      if (s.running) setProgress(s); // 打开页面时若正同步：恢复进度显示
    } catch (e) {
      setMsg(String(e));
    }
  }
  useEffect(() => {
    if (!native) return;
    load();
    // 实时进度事件
    let handle: { remove: () => Promise<void> } | null = null;
    PhotoSync.addListener("syncProgress", (d) => {
      setProgress(d);
      if (!d.running) load(); // 完成/结束：刷新最终状态
    }).then((h) => { handle = h; }).catch(() => {});
    // 兜底轮询（事件丢失时仍能恢复进度）
    const timer = setInterval(async () => {
      try {
        const s = await PhotoSync.getStatus();
        if (s.running) setProgress(s);
      } catch { /* 忽略 */ }
    }, 2000);
    const h = () => load(); // 全局刷新
    window.addEventListener(EV.refresh, h);
    return () => {
      handle?.remove().catch(() => {});
      clearInterval(timer);
      window.removeEventListener(EV.refresh, h);
    };
  }, [native]);

  async function apply(patch: { enabled?: boolean; wifiOnly?: boolean; intervalHours?: number }) {
    setBusy(true);
    setMsg(null);
    try {
      setSt(await PhotoSync.configure(patch));
      setMsg("已保存");
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
      setMsg(r.started ? "后台同步已启动，完成后通知" : "未启动");
      load();
    } catch (e) {
      setMsg(String(e));
    } finally {
      setBusy(false);
    }
  }

  if (!native) return null;

  const fmt = (ts: number | null) => (ts ? fmtTime(ts) : "从未");

  return (
    <section className="border-b border-border py-5">
      <h3 className="flex items-center gap-2 text-sm font-bold"><Camera className="size-4 text-muted" /> 相册自动同步</h3>
      <p className="text-muted text-xs mb-3">新照片后台自动上传到网盘（App 关闭也运行）。</p>

      {progress?.running && (
        <div className="mb-3 rounded-md border border-border bg-card/60 p-2.5">
          <div className="flex justify-between text-xs text-muted mb-1.5">
            <span className="flex items-center gap-1.5 font-medium">
              {progress.phase === "scanning" ? <RefreshCw className="size-3.5 animate-spin" /> : <Upload className="size-3.5" />}
              {progress.phase === "scanning" ? "扫描相册中…" : `正在上传 ${progress.uploaded}/${progress.total || "?"}`}
            </span>
            {progress.currentFile && <span className="truncate max-w-[55%]">{progress.currentFile}</span>}
          </div>
          <div className="h-1.5 bg-panel rounded-full overflow-hidden">
            <div
              className="h-full bg-accent rounded-full transition-all duration-300"
              style={{ width: progress.total > 0 ? `${Math.round((progress.uploaded / progress.total) * 100)}%` : "8%" }}
            />
          </div>
        </div>
      )}

      <label className="mb-2 flex cursor-pointer items-center gap-2 text-sm">
        <Switch checked={st?.enabled ?? false} disabled={busy}
                onCheckedChange={(v) => apply({ enabled: v })} />
        启用自动同步
      </label>

      {st?.enabled && (
        <>
          <label className="mb-2 flex cursor-pointer items-center gap-2 text-sm">
            <Switch checked={st.wifiOnly} disabled={busy}
                    onCheckedChange={(v) => apply({ wifiOnly: v })} />
            <span className="flex items-center gap-1.5"><Wifi className="size-3.5 text-muted" /> 仅 Wi-Fi 时同步</span>
          </label>
          <label className="mb-2 flex items-center gap-2 text-sm">
            <span className="flex w-16 items-center gap-1.5 text-xs text-muted"><Clock3 className="size-3.5" /> 频率</span>
            <Select value={String(st.intervalHours)} disabled={busy} onValueChange={(value) => apply({ intervalHours: Number(value) })}>
              <SelectTrigger size="sm" className="w-32"><SelectValue /></SelectTrigger>
              <SelectContent>
                {INTERVALS.map((h) => <SelectItem key={h} value={String(h)}>每 {h} 小时</SelectItem>)}
              </SelectContent>
            </Select>
          </label>
        </>
      )}

      <div className="flex items-center gap-2 mt-3">
        <Button onClick={syncNow} disabled={busy || !st?.enabled}>
          {busy ? "执行中…" : "立即同步"}
        </Button>
        {st && (
          <span className="text-muted text-[10px]">
            上次：{fmt(st.lastSyncAt)} · 同步 {Math.max(st.lastSyncedCount, 0)} 张
            {st.lastError ? ` · 最近错误: ${st.lastError}` : ""}
          </span>
        )}
      </div>
      {msg && (
        <Alert
          variant={msg === "已保存" || msg === "后台同步已启动，完成后通知" || msg === "未启动" ? "default" : "destructive"}
          className={`mt-2 text-xs ${msg === "已保存" || msg === "后台同步已启动，完成后通知" || msg === "未启动" ? "bg-success-soft text-success border-success/30" : "bg-danger-soft text-danger border-danger/30"}`}
        >{msg}</Alert>
      )}
    </section>
  );
}
