"use client";
import { useEffect, useRef, useState } from "react";
import { getDevices, removeDevice, DeviceInfo } from "@/lib/api/devices";

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
  if (s.last_error) parts.push(`⚠ ${s.last_error}`);
  return parts.join(" · ");
}

/** 设备列表：App 扫码连接后登记于此（心跳刷新活跃时间，同步后更新同步状态）。 */
export default function DevicesCard() {
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  async function load() {
    try {
      const d = await getDevices();
      setDevices(d.devices);
      setMsg(null);
    } catch {
      /* 保持旧列表 */
    }
  }

  useEffect(() => {
    load();
    timer.current = setInterval(load, 30000); // 30s 轮询
    return () => { if (timer.current) clearInterval(timer.current); };
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
    <div className="bg-panel border border-border rounded-xl p-4 mb-4">
      <h3 className="font-bold text-sm mb-1">🖥️ 设备列表</h3>
      <p className="text-muted text-xs mb-3">手机 App 扫码连接后自动登记；活跃时间由 App 心跳与相册同步刷新。</p>
      {devices.length === 0 ? (
        <p className="text-muted text-xs">暂无设备。用手机 App 扫上方二维码连接后会出现在这里。</p>
      ) : (
        <table className="w-full text-xs border-collapse">
          <thead>
            <tr>
              <th className="text-left p-1.5 border-b border-border">设备</th>
              <th className="text-left p-1.5 border-b border-border hidden sm:table-cell">版本</th>
              <th className="text-left p-1.5 border-b border-border">活跃</th>
              <th className="text-left p-1.5 border-b border-border hidden md:table-cell">相册同步</th>
              <th className="text-right p-1.5 border-b border-border"></th>
            </tr>
          </thead>
          <tbody>
            {devices.map((d) => (
              <tr key={d.device_id}>
                <td className="p-1.5 border-b border-border/50">
                  <div className="font-medium">{d.name}</div>
                  <div className="text-muted">{d.model} · {d.platform}</div>
                </td>
                <td className="p-1.5 border-b border-border/50 text-muted hidden sm:table-cell">{d.app_version}</td>
                <td className="p-1.5 border-b border-border/50">
                  {Date.now() / 1000 - d.last_seen < 120
                    ? <span className="bg-success-soft text-success px-1.5 py-0.5 rounded-full">在线</span>
                    : <span className="text-muted">{relTime(d.last_seen)}</span>}
                </td>
                <td className="p-1.5 border-b border-border/50 text-muted hidden md:table-cell">{fmtSync(d)}</td>
                <td className="p-1.5 border-b border-border/50 text-right">
                  <button className="text-danger text-[11px] cursor-pointer disabled:opacity-50"
                          onClick={() => rm(d.device_id)} disabled={busy !== null}>移除</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {msg && <p className="text-danger text-xs mt-2">{msg}</p>}
    </div>
  );
}
