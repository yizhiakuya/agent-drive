"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Capacitor } from "@capacitor/core";
import {
  AlertCircle,
  CheckCircle2,
  ChevronDown,
  Clock3,
  DatabaseZap,
  LoaderCircle,
  ListChecks,
  RefreshCw,
  RotateCcw,
  Square,
  X,
  XCircle,
} from "lucide-react";
import {
  cancelTask,
  listTasks,
  rebuildIndex,
  retryTask,
  taskEventsUrl,
  type TaskOverview,
  type TaskDetailResponse,
  type TaskRecord,
  type TaskStatus,
} from "@/lib/api/tasks";
import { EV, emitTasksChanged, emitToast } from "@/lib/events";
import { fmtTime } from "@/lib/format";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import TaskDetails from "./TaskDetails";
import TaskProgressBar from "./TaskProgressBar";
import { ACTIVE_STATUSES, resourceLabel, STATUS_LABELS, STATUS_VARIANT, taskLabel, taskStatusHint } from "./task-presenter";
import { useTaskDetails } from "./useTaskDetails";

type Filter = "all" | "active" | "failed" | "done";
const PAGE_SIZE = 50;

const FILTER_STATUS: Record<Filter, string> = {
  all: "",
  active: "queued,running,retry_wait,cancelling",
  failed: "failed,cancelled",
  done: "succeeded",
};

const ACTIVE = ACTIVE_STATUSES;

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
  return fmtTime(value, { short: true });
}

function emptyTaskMessage(filter: Filter) {
  if (filter === "active") return "当前没有进行中的任务";
  if (filter === "failed") return "当前没有异常任务";
  if (filter === "done") return "还没有完成的任务记录";
  return "还没有任务记录";
}

/**
 * 渲染一个顶层任务及其可选详情区域。详情单独挂在行内，避免异步详情加载改变其他任务的列表顺序。
 */
function TaskRow({ task, pending, onCancel, onRetry, expanded, detail, detailLoading, detailError, onToggleDetails, onReloadDetails }: {
  task: TaskRecord;
  pending: boolean;
  onCancel: (id: string) => void;
  onRetry: (id: string) => void;
  expanded: boolean;
  detail: TaskDetailResponse | null;
  detailLoading: boolean;
  detailError: string;
  onToggleDetails: (id: string) => void;
  onReloadDetails: (id: string) => void;
}) {
  return (
    <article className="min-w-0 border-b border-border px-3.5 py-3.5 last:border-b-0 sm:px-4">
      <div className="flex items-start gap-3 min-w-0">
        <StatusIcon status={task.status} />
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <h3 className="text-sm font-semibold break-words">{taskLabel(task)}</h3>
            <Badge variant={STATUS_VARIANT[task.status]}>{STATUS_LABELS[task.status]}</Badge>
            <span className="text-[11px] text-muted">{formatTime(task.created_at)}</span>
          </div>
          {task.resource_key && <p className="text-xs text-muted mt-1 break-all">{resourceLabel(task.resource_key)}</p>}
          {task.progress.message && <p className="mt-1 break-words text-xs text-muted" aria-live="polite">{task.progress.message}</p>}
          {(ACTIVE.has(task.status) || task.progress.total > 0 || task.progress.current > 0) && (
            <div className="mt-2">
              <TaskProgressBar progress={task.progress} active={ACTIVE.has(task.status)} compact fallbackText={taskStatusHint(task.status)} />
            </div>
          )}
          {task.error && <p className="text-xs text-danger mt-2 break-words">{task.error}</p>}
        </div>
        <div className="flex shrink-0 gap-1">
          <Button
            type="button"
            variant="ghost"
            size="icon-lg"
            className="text-muted hover:bg-card hover:text-text"
            title={expanded ? "收起任务详情" : "展开任务详情"}
            aria-label={expanded ? "收起任务详情" : "展开任务详情"}
            aria-expanded={expanded}
            aria-controls={`task-details-${task.id}`}
            onClick={() => onToggleDetails(task.id)}
          >
            <ChevronDown className={`size-4 transition-transform ${expanded ? "rotate-180" : ""}`} />
          </Button>
          {ACTIVE.has(task.status) && (
            <Button
              type="button"
              variant="ghost"
              size="icon-lg"
              className="text-muted hover:text-danger hover:bg-danger-soft"
              title="取消任务"
              aria-label="取消任务"
              disabled={pending || task.status === "cancelling"}
              onClick={() => onCancel(task.id)}
            >
              <Square className="size-4" />
            </Button>
          )}
          {(task.status === "failed" || task.status === "cancelled") && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-9 px-2 text-text hover:bg-accent-soft"
              title="重试任务"
              aria-label="重试任务"
              disabled={pending}
              onClick={() => onRetry(task.id)}
            >
              <RotateCcw className="size-4" />
              <span className="hidden sm:inline">重试</span>
            </Button>
          )}
        </div>
      </div>
      {expanded && (
        <TaskDetails
          task={task}
          detail={detail}
          loading={detailLoading}
          error={detailError}
          onRetry={() => onReloadDetails(task.id)}
        />
      )}
    </article>
  );
}

export default function TaskPage() {
  const [filter, setFilter] = useState<Filter>("all");
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [overview, setOverview] = useState<TaskOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState("");
  const [pending, setPending] = useState<string | null>(null);
  const [confirmRebuild, setConfirmRebuild] = useState(false);
  const reloadTimer = useRef<number | null>(null);
  const listRequestRef = useRef(0);
  const listLimitRef = useRef(PAGE_SIZE);
  const { expandedTaskId, taskDetails, detailErrors, detailLoadingId, loadDetail, toggleDetails } = useTaskDetails();

  /**
   * 刷新任务列表和顶层概览。请求代次是必要的：SSE、定时器和筛选切换可能同时触发请求，
   * 迟到响应不能把新筛选结果或新状态覆盖回旧列表。
   */
  const load = useCallback(async (quiet = false, limit = listLimitRef.current) => {
    const request = ++listRequestRef.current;
    if (!quiet) setLoading(true);
    else setSyncing(true);
    try {
      const data = await listTasks(FILTER_STATUS[filter], { limit });
      if (request !== listRequestRef.current) return;
      setTasks(data.items);
      setOverview(data.overview);
      setHasMore(data.has_more);
      setError("");
    } catch (reason) {
      if (request === listRequestRef.current) {
        setError(reason instanceof Error ? reason.message : String(reason));
      }
    } finally {
      if (!quiet && request === listRequestRef.current) setLoading(false);
      if (quiet && request === listRequestRef.current) setSyncing(false);
    }
  }, [filter]);

  useEffect(() => {
    listLimitRef.current = PAGE_SIZE;
    setHasMore(false);
    void load(false, PAGE_SIZE);
  }, [load]);

  useEffect(() => () => {
    listRequestRef.current += 1;
  }, []);

  useEffect(() => {
    // SSE/轮询只刷新列表；当前详情也要跟随同一个任务事件更新，才能看到运行中的阶段和错误。
    if (!expandedTaskId || !tasks.some((task) => task.id === expandedTaskId)) return;
    void loadDetail(expandedTaskId);
  }, [expandedTaskId, loadDetail, tasks]);

  useEffect(() => {
    // 浏览器端以 SSE 作为低延迟通知，定时刷新作为兜底；短暂合并事件避免连续写入列表。
    const refresh = () => load(true);
    window.addEventListener(EV.refresh, refresh);
    window.addEventListener(EV.tasksChanged, refresh);
    const interval = window.setInterval(refresh, 5000);
    let events: EventSource | null = null;
    if (!Capacitor.isNativePlatform()) {
      events = new EventSource(taskEventsUrl(), { withCredentials: true });
      events.addEventListener("task", () => {
        if (reloadTimer.current !== null) return;
        reloadTimer.current = window.setTimeout(() => {
          reloadTimer.current = null;
          refresh();
        }, 350);
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

  async function loadMore() {
    const nextLimit = listLimitRef.current + PAGE_SIZE;
    listLimitRef.current = nextLimit;
    setLoadingMore(true);
    try {
      await load(true, nextLimit);
    } finally {
      setLoadingMore(false);
    }
  }

  async function cancel(id: string) {
    setPending(id);
    try {
      await cancelTask(id);
      emitTasksChanged();
    } catch (reason) {
      emitToast({ kind: "error", text: reason instanceof Error ? reason.message : String(reason) });
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
      emitToast({ kind: "error", text: reason instanceof Error ? reason.message : String(reason) });
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
      emitToast({ kind: "error", text: reason instanceof Error ? reason.message : String(reason) });
    } finally {
      setPending(null);
    }
  }

  const counts = overview?.counts || {};
  const processingCount = (counts.running || 0) + (counts.cancelling || 0);
  const waitingCount = (counts.queued || 0) + (counts.retry_wait || 0);
  const activeCount = processingCount + waitingCount;
  const failedCount = (counts.failed || 0) + (counts.cancelled || 0);
  const index = overview?.index;

  return (
    <section className="flex-1 overflow-auto bg-bg min-w-0">
      <header className="bg-panel border-b border-border">
        <div className="max-w-5xl mx-auto px-4 sm:px-6 py-4 flex flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <h2 className="flex items-center gap-2 text-base font-bold"><ListChecks className="size-4 text-muted" /> 后台任务</h2>
            <p className="text-xs text-muted mt-0.5">
              {activeCount > 0 ? `${processingCount > 0 ? `${processingCount} 个正在执行` : "暂无正在执行"}，${waitingCount} 个等待处理` : "当前没有等待中的任务"}
            </p>
          </div>
          <div className="flex items-center gap-1.5">
            <Button
              type="button"
              variant="ghost"
              size="icon-lg"
              className="text-muted hover:text-text hover:bg-card"
              title="刷新"
              aria-label="刷新任务"
              onClick={() => load()}
            >
              <RefreshCw className={`size-4 ${loading || syncing ? "animate-spin" : ""}`} />
            </Button>
            <Button
              type="button"
              variant="default"
              size="default"
              className="h-10 px-3 gap-2 font-semibold"
              disabled={index?.embedding_configured !== true || pending !== null}
              title={index?.embedding_configured === true ? "重建搜索索引" : index ? "请先在设置中配置向量服务" : "正在读取索引状态"}
              onClick={() => setConfirmRebuild(true)}
            >
              <DatabaseZap className="size-4" />
              <span>重建索引</span>
            </Button>
          </div>
        </div>
      </header>

      <div className="max-w-5xl mx-auto px-4 sm:px-6 py-4">
        {!overview?.workers.online && (
          <Alert className="mb-4 border-warn/30 bg-warn-soft text-warn">
            <AlertCircle className="size-4 shrink-0" />
            <AlertDescription>任务 Worker 未连接，队列会保留，服务恢复后继续执行。</AlertDescription>
          </Alert>
        )}

        <div className="mb-5 grid grid-cols-2 border-y border-border bg-panel lg:grid-cols-4">
          <div className="border-b border-border p-3 lg:border-b-0 lg:border-r">
            <div className="text-[11px] text-muted">正在执行</div>
            <div className="text-lg font-semibold tabular-nums mt-0.5">{processingCount}</div>
          </div>
          <div className="border-b border-border p-3 lg:border-b-0 lg:border-r">
            <div className="text-[11px] text-muted">等待处理</div>
            <div className="text-lg font-semibold tabular-nums mt-0.5">{waitingCount}</div>
          </div>
          <div className="border-r border-border p-3">
            <div className="text-[11px] text-muted">有效向量</div>
            <div className="text-lg font-semibold tabular-nums mt-0.5">{index?.vector_files ?? "-"}</div>
          </div>
          <div className="p-3">
            <div className="text-[11px] text-muted">异常记录</div>
            <div className="text-lg font-semibold tabular-nums mt-0.5">{failedCount}</div>
          </div>
        </div>

        <div className="flex items-center justify-between gap-3 mb-3">
          <div className="inline-flex rounded-md border border-border bg-card p-0.5" role="tablist" aria-label="任务筛选">
            {(["all", "active", "failed", "done"] as Filter[]).map((item) => (
              <button
                key={item}
                type="button"
                role="tab"
                aria-selected={filter === item}
                className={`h-8 rounded-sm px-3 text-xs ${filter === item ? "bg-panel font-semibold text-text shadow-sm" : "text-muted hover:text-text"}`}
                onClick={() => setFilter(item)}
              >
                {{ all: "全部", active: "进行中", failed: "异常", done: "已完成" }[item]}
              </button>
            ))}
          </div>
          {index && (
            <span className="hidden max-w-[50%] truncate text-[11px] text-muted sm:block">
              {index.embedding_configured === false
                ? "未配置向量服务"
                : `${index.model || "向量服务"} · 待向量化 ${index.missing_vectors ?? "-"}`}
            </span>
          )}
        </div>

        <div className="overflow-hidden border-y border-border bg-panel">
          {error && (
            <div className="flex items-center gap-2 border-b border-danger/20 bg-danger-soft/40 p-3 text-sm text-danger">
              <AlertCircle className="size-4 shrink-0" />
              <span className="min-w-0 flex-1 break-words">{error}</span>
              <Button type="button" variant="ghost" size="sm" className="h-8 shrink-0 px-2 text-danger hover:bg-danger/10" onClick={() => load()}>
                重试
              </Button>
            </div>
          )}
          {loading && tasks.length === 0 ? (
            <div className="p-8 text-sm text-muted text-center">正在加载…</div>
          ) : tasks.length === 0 ? (
            <div className="p-10 text-center">
              <ListChecks className="mx-auto size-7 text-muted/70" />
              <p className="mt-2 text-sm font-medium text-text">{emptyTaskMessage(filter)}</p>
              <p className="mt-1 text-xs text-muted">任务开始执行后，进度和结果会显示在这里。</p>
            </div>
          ) : (
            <>
              {tasks.map((task) => (
                <TaskRow
                  key={task.id}
                  task={task}
                  pending={pending === task.id}
                  onCancel={cancel}
                  onRetry={retry}
                  expanded={expandedTaskId === task.id}
                  detail={taskDetails[task.id] ?? null}
                  detailLoading={detailLoadingId === task.id}
                  detailError={detailErrors[task.id] ?? ""}
                  onToggleDetails={toggleDetails}
                  onReloadDetails={loadDetail}
                />
              ))}
              {hasMore && (
                <div className="flex items-center justify-center border-t border-border p-3">
                  <Button type="button" variant="ghost" className="h-9 px-3 text-xs" disabled={loadingMore} onClick={loadMore}>
                    {loadingMore ? "正在加载…" : "加载更多任务"}
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {confirmRebuild && (
        <div className="fixed inset-0 z-50 bg-black/35 grid place-items-center p-4" role="dialog" aria-modal="true" aria-labelledby="rebuild-title">
          <div className="w-full max-w-sm rounded-lg border border-border bg-panel p-4 shadow-xl">
            <h3 id="rebuild-title" className="font-bold text-sm">重建全部搜索索引</h3>
            <p className="text-sm text-muted mt-2">现有全文与向量索引会在后台重新生成，文件本身不会被修改。</p>
            <div className="flex justify-end gap-2 mt-4">
              <Button type="button" variant="ghost" className="h-10 px-3" onClick={() => setConfirmRebuild(false)}>取消</Button>
              <Button
                type="button"
                variant="default"
                className="h-10 px-3 font-semibold"
                disabled={pending === "rebuild"}
                onClick={startRebuild}
              >
                {pending === "rebuild" ? "正在提交…" : "开始重建"}
              </Button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
