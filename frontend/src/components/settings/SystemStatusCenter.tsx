"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertCircle, CheckCircle2, Database, HardDrive, RefreshCw, ServerCog, Smartphone } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { getConfig, getStatus } from "@/lib/api/config";
import { getDevices, type DeviceInfo } from "@/lib/api/devices";
import { listFiles } from "@/lib/api/files";
import { getReadiness, type ReadinessView } from "@/lib/api/readiness";
import { fmtSize, fmtTime } from "@/lib/format";

type CheckState = "ok" | "warn" | "error" | "unknown";

function stateLabel(state: CheckState) {
  return state === "ok" ? "正常" : state === "warn" ? "需关注" : state === "error" ? "异常" : "未检查";
}

function StateIcon({ state }: { state: CheckState }) {
  if (state === "ok") return <CheckCircle2 className="size-4 text-success" aria-hidden="true" />;
  if (state === "error") return <AlertCircle className="size-4 text-danger" aria-hidden="true" />;
  return <AlertCircle className="size-4 text-warn" aria-hidden="true" />;
}

function stateFor(value: boolean | undefined): CheckState {
  return value === true ? "ok" : value === false ? "error" : "unknown";
}

interface SystemStatusCenterProps {
  onOpenSettings?: () => void;
  onOpenSync?: () => void;
  onOpenBackup?: () => void;
  onOpenDevices?: () => void;
}

/**
 * 汇总真实服务状态；任何子请求失败都保留其余检查结果，不把局部故障伪装成全空状态。
 */
export default function SystemStatusCenter({ onOpenSettings, onOpenSync, onOpenBackup, onOpenDevices }: SystemStatusCenterProps) {
  const [ready, setReady] = useState<ReadinessView | null>(null);
  const [config, setConfig] = useState<Awaited<ReturnType<typeof getConfig>> | null>(null);
  const [embeddingConfigured, setEmbeddingConfigured] = useState<boolean | undefined>(undefined);
  const [devices, setDevices] = useState<DeviceInfo[] | null>(null);
  const [disk, setDisk] = useState<{ used: number; total: number; free: number } | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [checkedAt, setCheckedAt] = useState(() => Date.now());
  const loadRequestRef = useRef(0);

  const load = useCallback(async () => {
    const request = ++loadRequestRef.current;
    setLoading(true);
    setError("");
    const results = await Promise.allSettled([
      getReadiness(),
      getConfig(),
      getStatus(),
      getDevices(),
      listFiles(""),
    ]);
    const [readiness, cfg, status, devices, files] = results;
    let failures = 0;
    if (request !== loadRequestRef.current) return;
    if (readiness.status === "fulfilled") setReady(readiness.value);
    else failures++;
    if (cfg.status === "fulfilled") setConfig(cfg.value);
    else failures++;
    if (status.status === "fulfilled") {
      const value = status.value as { embeddings?: { configured?: boolean } | null };
      setEmbeddingConfigured(value.embeddings?.configured);
    } else failures++;
    if (devices.status === "fulfilled") setDevices(devices.value.devices);
    else failures++;
    if (files.status === "fulfilled") setDisk(files.value.disk);
    else failures++;
    if (failures > 0) setError(`${failures} 项状态检查暂时不可用，显示的其余状态仍来自服务端最新响应。`);
    setCheckedAt(Date.now());
    setLoading(false);
  }, []);

  useEffect(() => { void load(); }, [load]);

  const dbState = ready ? stateFor(ready.database.ok) : "unknown";
  const providerState = config ? stateFor(config.configured) : "unknown";
  const embeddingState: CheckState = embeddingConfigured === undefined
    ? "unknown"
    : !embeddingConfigured
      ? "warn"
      : "ok";
  const readinessStorage = ready?.storage;
  const storageFree = readinessStorage?.free_bytes ?? disk?.free;
  const storageTotal = readinessStorage?.total_bytes ?? disk?.total;
  const storageState = readinessStorage
    ? stateFor(readinessStorage.ok)
    : storageFree !== undefined && storageTotal !== undefined && storageTotal > 0
      ? (storageFree / storageTotal < 0.1 ? "warn" : "ok")
      : "unknown";
  const syncDevices = devices?.filter((device) => device.sync) ?? [];
  const enabledSyncDevices = syncDevices.filter((device) => device.sync?.enabled);
  const syncFailure = syncDevices.find((device) => device.sync?.last_error);
  const syncState: CheckState = devices === null
    ? "unknown"
    : syncFailure ? "error" : enabledSyncDevices.length > 0 ? "ok" : "warn";
  const backupState = ready?.backup ? stateFor(ready.backup.ok) : "unknown";
  const backupDetail = ready?.backup?.ok && ready.backup.last_backup_at != null
    ? `最近成功 ${fmtTime(Number(ready.backup.last_backup_at))} · 保留 ${ready.backup.retained ?? 0} 份`
    : ready?.backup?.error || "等待检查";
  const databaseDetail = ready?.database.detail || ready?.database.error
    || (dbState === "error" ? "数据库不可用" : dbState === "unknown" ? "等待检查" : "PostgreSQL 可用");
  const indexDetail = embeddingConfigured === undefined ? "等待检查" : embeddingConfigured ? "Jina embedding 已配置" : "尚未配置 Jina embedding";

  const rows = [
    { key: "db", icon: Database, label: "数据库", state: dbState, detail: databaseDetail, onClick: undefined },
    { key: "provider", icon: ServerCog, label: "对话 Provider", state: providerState, detail: config?.llm?.model || "未配置", onClick: onOpenSettings },
    { key: "embedding", icon: Database, label: "语义索引", state: embeddingState, detail: indexDetail, onClick: onOpenSettings },
    { key: "sync", icon: Smartphone, label: "相册同步", state: syncState, detail: devices === null ? "等待检查" : syncFailure?.sync?.last_error || `${enabledSyncDevices.length}/${syncDevices.length || devices.length} 台设备已启用`, onClick: onOpenSync },
    { key: "backup", icon: HardDrive, label: "备份", state: backupState, detail: backupDetail, onClick: onOpenBackup },
    { key: "storage", icon: HardDrive, label: "存储空间", state: storageState, detail: readinessStorage?.error || (storageFree !== undefined && storageTotal !== undefined ? `${fmtSize(storageFree)} 可用 / ${fmtSize(storageTotal)}` : "等待检查"), onClick: onOpenSettings },
    { key: "devices", icon: Smartphone, label: "手机设备", state: devices === null ? "unknown" : "ok", detail: devices === null ? "等待检查" : `${devices.length} 台已登记`, onClick: onOpenDevices },
  ] satisfies { key: string; icon: typeof Database; label: string; state: CheckState; detail: string; onClick?: () => void }[];

  return (
    <section className="border-y border-border bg-panel">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3 sm:px-5">
        <div>
          <h3 className="flex items-center gap-2 text-sm font-bold"><ServerCog className="size-4 text-muted" /> 系统状态</h3>
          <p className="mt-0.5 text-xs text-muted">索引、Provider、设备和存储的当前服务端状态。</p>
        </div>
        <Button type="button" variant="ghost" size="icon-sm" onClick={() => void load()} disabled={loading} aria-label="刷新系统状态" title="刷新">
          <RefreshCw className={loading ? "size-4 animate-spin" : "size-4"} />
        </Button>
      </div>
      {error && <Alert className="m-3 border-warn/30 bg-warn-soft text-warn"><AlertDescription>{error}</AlertDescription></Alert>}
      <div className="grid grid-cols-1 divide-y divide-border sm:grid-cols-2 sm:divide-y-0">
        {rows.map(({ key, icon: Icon, label, state, detail, onClick }) => (
          <button key={key} type="button" disabled={!onClick} onClick={onClick}
                  className="flex w-full items-center gap-3 border-b border-border/60 px-4 py-3 text-left last:border-b-0 enabled:cursor-pointer enabled:hover:bg-card/50 disabled:cursor-default sm:px-5">
            <Icon className="size-4 shrink-0 text-muted" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 text-sm"><span>{label}</span><StateIcon state={state} /><Badge variant={state === "error" ? "destructive" : state === "ok" ? "outline" : "secondary"}>{stateLabel(state)}</Badge></div>
              <div className="mt-0.5 truncate text-xs text-muted">{detail}</div>
            </div>
          </button>
        ))}
      </div>
      <div className="flex flex-wrap items-center gap-2 border-t border-border px-4 py-2.5 text-xs text-muted sm:px-5">
        <span>检查时间：{fmtTime(checkedAt / 1000)}</span>
        {!config?.configured && <Button type="button" variant="link" size="sm" className="h-7 px-1" onClick={onOpenSettings}>去配置 Provider</Button>}
      </div>
    </section>
  );
}
