"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Capacitor } from "@capacitor/core";
import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  DatabaseZap,
  LoaderCircle,
  RefreshCw,
  RotateCcw,
  Square,
  X,
  XCircle,
} from "lucide-react";
import { ApiError } from "@/lib/api/client";
import {
  cancelTask,
  listTasks,
  rebuildIndex,
  retryTask,
  taskEventsUrl,
  type TaskOverview,
  type TaskRecord,
  type TaskStatus,
} from "@/lib/api/tasks";
import { EV, emitTasksChanged, emitToast } from "@/lib/events";

type Filter = "all" | "active" | "failed" | "done";

const FILTER_STATUS: Record<Filter, string> = {
  all: "",
  active: "queued,running,retry_wait,cancelling",
  failed: "failed,cancelled",
  done: "succeeded",
};

const TASK_LABELS: Record<string, string> = {
  "index.file": "文件索引",
  "index.rebuild": "重建搜索索引",
  "index.cleanup": "清理失效索引",
  "maintenance.daily": "系统维护",
  "automation.run": "自动化规则",
};

const STATUS_LABELS: Record<TaskStatus, string> = {
  queued: "等待中",
  running: "运行中",
  retry_wait: "等待重试",
  cancelling: "正在取消",
  succeeded: "已完成",
  failed: "失败",
  cancelled: "已取消",
};

const ACTIVE = new Set<TaskStatus>(["queued", "running", "retry_wait", "cancelling"]);

function StatusIcon({ status }: { status: TaskStatus }) {
  const className = "size-4 shrink-0";
  if (status === "succeeded") return <CheckCircle2 className={`${className} text-success`} />;
  if (status === "failed") return <XCircle className={`${className} text-danger`} />;
  if (status === "cancelled") return <X className={`${className} text-muted`} />;
  if (status === "running" || status === "cancelling") {
    return <LoaderCircle className={`${className} text-accent animate-spin`} />;
  }
  return <Clock3 className={`${className} text-warn`} />;
}

function formatTime(value: number | null) {
  if (!value) return "";
  return new Date(value * 1000).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function TaskRow({ task, pending, onCancel, onRetry }: {
  task: TaskRecord;
  pending: boolean;
  onCancel: (id: string) => void;
  onRetry: (id: string) => void;
}) {
  const total = task.progress.total;
  const percent = total > 0 ? Math.min(100, Math.round((task.progress.current / total) * 100)) : 0;
  return (
    <article className="px-3.5 sm:px-4 py-3 border-b border-border last:border-b-0 min-w-0">
      <div className="flex items-start gap-3 min-w-0">
        <StatusIcon status={task.status} />
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <h3 className="text-sm font-semibold break-words">{TASK_LABELS[task.type] || task.type}</h3>
            <span className="text-[11px] text-muted">{STATUS_LABELS[task.status]}</span>
            <span className="text-[11px] text-muted">{formatTime(task.created_at)}</span>
          </div>
          {task.resource_key && (
            <p className="text-xs text-muted mt-1 break-all">{task.resource_key.replace(/^file:|^index:/, "")}</p>
          )}
          {task.progress.message && <p className="text-xs mt-1 break-words">{task.progress.message}</p>}
          {(ACTIVE.has(task.status) || total > 0) && (
            <div className="mt-2 flex items-center gap-2">
              <div className="h-1.5 flex-1 bg-card rounded-full overflow-hidden" aria-label={`进度 ${percent}%`}>
                <div className="h-full bg-accent transition-[width] duration-300" style={{ width: `${percent}%` }} />
              </div>
              {total > 0 && <span className="text-[11px] tabular-nums text-muted w-9 text-right">{percent}%</span>}
            </div>
          )}
          {task.error && <p className="text-xs text-danger mt-2 break-words">{task.error}</p>}
        </div>
        <div className="flex shrink-0 gap-1">
          {ACTIVE.has(task.status) && (
            <button
              type="button"
              className="size-9 grid place-items-center text-muted hover:text-danger hover:bg-danger-soft rounded-lg disabled:opacity-50"
              title="取消任务"
              aria-label="取消任务"
              disabled={pending || task.status === "cancelling"}
              onClick={() => onCancel(task.id)}
            >
              <Square className="size-4" />
            </button>
          )}
          {(task.status === "failed" || task.status === "cancelled") && (
            <button
              type="button"
              className="size-9 grid place-items-center text-muted hover:text-accent hover:bg-accent-soft rounded-lg disabled:opacity-50"
              title="重试任务"
              aria-label="重试任务"
              disabled={pending}
              onClick={() => onRetry(task.id)}
            >
              <RotateCcw className="size-4" />
            </button>
          )}
        </div>
      </div>
    </article>
  );
}

export default function TaskPage() {
  const [filter, setFilter] = useState<Filter>("all");
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [overview, setOverview] = useState<TaskOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [pending, setPending] = useState<string | null>(null);
  const [confirmRebuild, setConfirmRebuild] = useState(false);
  const reloadTimer = useRef<number | null>(null);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      const data = await listTasks(FILTER_STATUS[filter]);
      setTasks(data.items);
      setOverview(data.overview);
      setError("");
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : String(reason));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [filter]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    const refresh = () => load(true);
    window.addEventListener(EV.refresh, refresh);
    window.addEventListener(EV.tasksChanged, refresh);
    const interval = window.setInterval(refresh, 5000);
    let events: EventSource | null = null;
    if (!Capacitor.isNativePlatform()) {
      events = new EventSource(taskEventsUrl(), { withCredentials: true });
      events.addEventListener("task", () => {
        if (reloadTimer.current !== null) window.clearTimeout(reloadTimer.current);
        reloadTimer.current = window.setTimeout(refresh, 200);
      });
    }
    return () => {
      window.removeEventListener(EV.refresh, refresh);
      window.removeEventListener(EV.tasksChanged, refresh);
      window.clearInterval(interval);
      if (reloadTimer.current !== null) window.clearTimeout(reloadTimer.current);
      events?.close();
    };
  }, [load]);

  async function cancel(id: string) {
    setPending(id);
    try {
      await cancelTask(id);
      emitTasksChanged();
    } catch (reason) {
      emitToast({ kind: "error", text: reason instanceof ApiError ? reason.message : String(reason) });
    } finally {
      setPending(null);
    }
  }

  async function retry(id: string) {
    setPending(id);
    try {
      await retryTask(id);
      emitTasksChanged();
    } catch (reason) {
      emitToast({ kind: "error", text: reason instanceof ApiError ? reason.message : String(reason) });
    } finally {
      setPending(null);
    }
  }

  async function startRebuild() {
    setPending("rebuild");
    try {
      const result = await rebuildIndex(true);
      setConfirmRebuild(false);
      emitToast({ kind: "ok", text: result.queued ? "索引重建已进入任务队列" : "索引重建已经在运行" });
      emitTasksChanged();
    } catch (reason) {
      emitToast({ kind: "error", text: reason instanceof ApiError ? reason.message : String(reason) });
    } finally {
      setPending(null);
    }
  }

  const counts = overview?.counts || {};
  const activeCount = (counts.queued || 0) + (counts.running || 0) + (counts.retry_wait || 0) + (counts.cancelling || 0);
  const index = overview?.index;

  return (
    <section className="flex-1 overflow-auto bg-bg min-w-0">
      <header className="bg-panel border-b border-border">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-4 flex flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-base font-bold">后台任务</h2>
            <p className="text-xs text-muted mt-0.5">
              {activeCount > 0 ? `${activeCount} 个任务正在处理` : "当前没有运行中的任务"}
            </p>
          </div>
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              className="size-10 grid place-items-center text-muted hover:text-text hover:bg-card rounded-lg"
              title="刷新"
              aria-label="刷新任务"
              onClick={() => load()}
            >
              <RefreshCw className={`size-4 ${loading ? "animate-spin" : ""}`} />
            </button>
            <button
              type="button"
              className="h-10 px-3 inline-flex items-center gap-2 bg-accent text-white rounded-lg text-sm font-semibold disabled:opacity-50"
              disabled={!index?.embedding_configured || pending !== null}
              title={index?.embedding_configured ? "重建搜索索引" : "请先在设置中配置向量服务"}
              onClick={() => setConfirmRebuild(true)}
            >
              <DatabaseZap className="size-4" />
              <span>重建索引</span>
            </button>
          </div>
        </div>
      </header>

      <div className="max-w-5xl mx-auto px-4 sm:px-6 py-4">
        {!overview?.workers.online && (
          <div className="mb-4 px-3 py-2.5 border border-warn/30 bg-warn-soft text-warn rounded-lg text-sm flex items-center gap-2">
            <AlertCircle className="size-4 shrink-0" />
            <span>任务 Worker 未连接，队列会保留，服务恢复后继续执行。</span>
          </div>
        )}

        <div className="grid grid-cols-2 lg:grid-cols-4 border-y border-border bg-panel mb-4">
          <div className="p-3 border-r border-b lg:border-b-0 border-border">
            <div className="text-[11px] text-muted">运行中</div>
            <div className="text-lg font-semibold tabular-nums mt-0.5">{activeCount}</div>
          </div>
          <div className="p-3 lg:border-r border-b lg:border-b-0 border-border">
            <div className="text-[11px] text-muted">有效向量</div>
            <div className="text-lg font-semibold tabular-nums mt-0.5">{index?.vector_files ?? 0}</div>
          </div>
          <div className="p-3 border-r border-border">
            <div className="text-[11px] text-muted">待索引</div>
            <div className="text-lg font-semibold tabular-nums mt-0.5">{index?.missing_vectors ?? 0}</div>
          </div>
          <div className="p-3">
            <div className="text-[11px] text-muted">失败</div>
            <div className="text-lg font-semibold tabular-nums mt-0.5">{counts.failed || 0}</div>
          </div>
        </div>

        <div className="flex items-center justify-between gap-3 mb-3">
          <div className="inline-flex bg-card p-0.5 rounded-lg" role="tablist" aria-label="任务筛选">
            {(["all", "active", "failed", "done"] as Filter[]).map((item) => (
              <button
                key={item}
                type="button"
                role="tab"
                aria-selected={filter === item}
                className={`h-9 px-3 rounded-md text-xs ${filter === item ? "bg-panel text-text shadow-sm font-semibold" : "text-muted"}`}
                onClick={() => setFilter(item)}
              >
                {{ all: "全部", active: "运行中", failed: "异常", done: "已完成" }[item]}
              </button>
            ))}
          </div>
          {index?.model && <span className="hidden sm:block text-[11px] text-muted truncate">{index.model}</span>}
        </div>

        <div className="bg-panel border border-border rounded-lg overflow-hidden">
          {error ? (
            <div className="p-5 text-sm text-danger flex items-center gap-2">
              <AlertCircle className="size-4 shrink-0" />
              <span className="break-words">{error}</span>
            </div>
          ) : loading && tasks.length === 0 ? (
            <div className="p-8 text-sm text-muted text-center">正在加载…</div>
          ) : tasks.length === 0 ? (
            <div className="p-8 text-sm text-muted text-center">暂无任务</div>
          ) : tasks.map((task) => (
            <TaskRow key={task.id} task={task} pending={pending === task.id} onCancel={cancel} onRetry={retry} />
          ))}
        </div>
      </div>

      {confirmRebuild && (
        <div className="fixed inset-0 z-50 bg-black/35 grid place-items-center p-4" role="dialog" aria-modal="true" aria-labelledby="rebuild-title">
          <div className="w-full max-w-sm bg-panel border border-border rounded-lg shadow-xl p-4">
            <h3 id="rebuild-title" className="font-bold text-sm">重建全部搜索索引</h3>
            <p className="text-sm text-muted mt-2">现有全文与向量索引会在后台重新生成，文件本身不会被修改。</p>
            <div className="flex justify-end gap-2 mt-4">
              <button type="button" className="h-10 px-3 text-sm rounded-lg hover:bg-card" onClick={() => setConfirmRebuild(false)}>取消</button>
              <button
                type="button"
                className="h-10 px-3 text-sm font-semibold bg-accent text-white rounded-lg disabled:opacity-50"
                disabled={pending === "rebuild"}
                onClick={startRebuild}
              >
                {pending === "rebuild" ? "正在提交…" : "开始重建"}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
