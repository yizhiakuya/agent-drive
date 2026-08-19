"use client";
import { useEffect, useRef, useState } from "react";
import { getDevices, removeDevice, DeviceInfo } from "@/lib/api/devices";
import { EV } from "@/lib/events";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { MonitorSmartphone, Trash2 } from "lucide-react";

function relTime(ts: number): string {
  const diff = Math.max(0, Date.now() / 1000 - ts);
  if (diff < 60) return "刚刚";
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
  return `${Math.floor(diff / 86400)} 天前`;
}

function fmtSync(d: DeviceInfo): string {
  const s = d.sync;
  if (!s?.enabled) return "未启用";
  const parts = [`每 ${s.interval_hours}h`, s.wifi_only ? "仅Wi-Fi" : "任意网络"];
  if (s.last_sync_at) parts.push(`上次 ${relTime(s.last_sync_at)} · ${s.last_synced_count} 张`);
  if (s.last_error) parts.push(`错误：${s.last_error}`);
  return parts.join(" · ");
}

/** 设备列表：App 扫码连接后登记于此（心跳刷新活跃时间，同步后更新同步状态）。 */
export default function DevicesCard() {
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  // 渲染期禁止 Date.now()（非纯函数）；由 30s 轮询驱动 now，保证“在线”判定新鲜。
  const [now, setNow] = useState(() => Date.now() / 1000);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  async function load() {
    try {
      const d = await getDevices();
      setDevices(d.devices);
      setNow(Date.now() / 1000);
      setMsg(null);
    } catch {
      /* 保持旧列表 */
    }
  }

  useEffect(() => {
    load();
    timer.current = setInterval(load, 30000); // 30s 轮询
    const h = () => load(); // 全局刷新
    window.addEventListener(EV.refresh, h);
    return () => { if (timer.current) clearInterval(timer.current); window.removeEventListener(EV.refresh, h); };
  }, []);

  async function rm(id: string) {
    if (!confirm(`移除设备 ${id.slice(0, 8)}…？该设备下次打开 App 会重新登记。`)) return;
    setBusy(id);
    try {
      await removeDevice(id);
      await load();
    } catch (e) {
      setMsg(String(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="border-b border-border py-5">
      <h3 className="flex items-center gap-2 text-sm font-bold"><MonitorSmartphone className="size-4 text-muted" /> 设备列表</h3>
      <p className="text-muted text-xs mb-3">手机 App 扫码连接后自动登记；活跃时间由 App 心跳与相册同步刷新。</p>
      {devices.length === 0 ? (
        <p className="text-muted text-xs">暂无设备。用手机 App 扫上方二维码连接后会出现在这里。</p>
      ) : (
        <table className="w-full text-xs border-collapse">
          <thead>
            <tr>
              <th className="border-b border-border p-2 text-left font-semibold">设备</th>
              <th className="hidden border-b border-border p-2 text-left font-semibold sm:table-cell">版本</th>
              <th className="border-b border-border p-2 text-left font-semibold">活跃</th>
              <th className="hidden border-b border-border p-2 text-left font-semibold md:table-cell">相册同步</th>
              <th className="border-b border-border p-2 text-right font-semibold"></th>
            </tr>
          </thead>
          <tbody>
            {devices.map((d) => (
              <tr key={d.device_id}>
                <td className="border-b border-border/60 p-2">
                  <div className="font-medium">{d.name}</div>
                  <div className="text-muted">{d.model} · {d.platform}</div>
                </td>
                <td className="hidden border-b border-border/60 p-2 text-muted sm:table-cell">{d.app_version}</td>
                <td className="border-b border-border/60 p-2">
                  {now - d.last_seen < 120
                    ? <Badge variant="outline" className="bg-success-soft text-success border-success/30">在线</Badge>
                    : <span className="text-muted">{relTime(d.last_seen)}</span>}
                </td>
                <td className="hidden border-b border-border/60 p-2 text-muted md:table-cell">{fmtSync(d)}</td>
                <td className="border-b border-border/60 p-2 text-right">
                  <Button variant="destructive" size="sm" onClick={() => rm(d.device_id)} disabled={busy !== null}><Trash2 className="size-3.5" /><span className="hidden sm:inline">移除</span></Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {msg && <p className="text-danger text-xs mt-2">{msg}</p>}
    </section>
  );
}
