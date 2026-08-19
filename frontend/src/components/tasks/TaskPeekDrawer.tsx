"use client";

import { useEffect, useState } from "react";
import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  LoaderCircle,
  ListChecks,
  RefreshCw,
  X,
  XCircle,
} from "lucide-react";
import { ApiError } from "@/lib/api/client";
import { listTasks, type TaskRecord, type TaskStatus } from "@/lib/api/tasks";
import { EV } from "@/lib/events";
import { Button } from "@/components/ui/button";

const TASK_LABELS: Record<string, string> = {
  "index.file": "文件索引",
  "index.rebuild": "重建搜索索引",
  "index.embed": "文件向量化",
  "index.vision": "图片视觉索引",
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

const ACTIVE_STATUSES = new Set<TaskStatus>(["queued", "running", "retry_wait", "cancelling"]);

function taskLabel(task: TaskRecord) {
  return TASK_LABELS[task.type] || task.type;
}

function taskPercent(task: TaskRecord) {
  if (task.progress.total <= 0) return 0;
  return Math.min(100, Math.round((task.progress.current / task.progress.total) * 100));
}

function StatusMark({ status }: { status: TaskStatus }) {
  if (status === "running" || status === "cancelling") {
    return <LoaderCircle className="size-4 shrink-0 animate-spin text-accent" aria-hidden="true" />;
  }
  if (status === "succeeded") return <CheckCircle2 className="size-4 shrink-0 text-success" aria-hidden="true" />;
  if (status === "failed") return <XCircle className="size-4 shrink-0 text-danger" aria-hidden="true" />;
  if (status === "cancelled") return <X className="size-4 shrink-0 text-muted" aria-hidden="true" />;
  return <Clock3 className="size-4 shrink-0 text-warn" aria-hidden="true" />;
}

function TaskPreview({ task }: { task: TaskRecord }) {
  const percent = taskPercent(task);
  const active = ACTIVE_STATUSES.has(task.status);
  return (
    <article className="border-b border-border py-4 last:border-b-0">
      <div className="flex items-start gap-2.5">
        <StatusMark status={task.status} />
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <h3 className="min-w-0 break-words text-sm font-semibold text-text">{taskLabel(task)}</h3>
            <span className={`shrink-0 text-[10px] font-mono ${task.status === "failed" ? "text-danger" : active ? "text-warn" : "text-muted"}`}>
              {STATUS_LABELS[task.status]}
            </span>
          </div>
          {task.resource_key && <p className="mt-1 break-all font-mono text-[10px] text-muted">{task.resource_key.replace(/^file:|^index:/, "")}</p>}
          {task.progress.message && <p className="mt-1 break-words text-xs text-muted">{task.progress.message}</p>}
          {(active || task.progress.total > 0) && (
            <div className="mt-2 flex items-center gap-2">
              <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-card" aria-label={`进度 ${percent}%`}>
                <div className="h-full rounded-full bg-text transition-[width] duration-300" style={{ width: `${percent}%` }} />
              </div>
              {task.progress.total > 0 && <span className="w-8 text-right font-mono text-[10px] text-muted">{percent}%</span>}
            </div>
          )}
          {task.error && (
            <div className="mt-2 flex gap-1.5 break-words rounded-md border border-danger/20 bg-danger-soft/60 p-2 text-[11px] text-danger">
              <AlertCircle className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
              <span>{task.error}</span>
            </div>
          )}
        </div>
      </div>
    </article>
  );
}

interface TaskPeekDrawerProps {
  open: boolean;
  onClose: () => void;
  onViewAll: () => void;
}

export default function TaskPeekDrawer({ open, onClose, onViewAll }: TaskPeekDrawerProps) {
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    let mounted = true;
    const load = async () => {
      setLoading(true);
      try {
        const response = await listTasks("queued,running,retry_wait,cancelling,failed");
        if (!mounted) return;
        setTasks(response.items);
        setError("");
      } catch (reason) {
        if (!mounted) return;
        setError(reason instanceof ApiError ? reason.message : String(reason));
      } finally {
        if (mounted) setLoading(false);
      }
    };
    load();
    const refresh = () => { void load(); };
    window.addEventListener(EV.tasksChanged, refresh);
    window.addEventListener(EV.refresh, refresh);
    const interval = window.setInterval(refresh, 8000);
    return () => {
      mounted = false;
      window.removeEventListener(EV.tasksChanged, refresh);
      window.removeEventListener(EV.refresh, refresh);
      window.clearInterval(interval);
    };
  }, [open]);

  if (!open) return null;

  const visibleTasks = tasks.slice(0, 6);
  const hiddenTaskCount = Math.max(0, tasks.length - visibleTasks.length);

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label="后台任务队列">
      <button
        type="button"
        className="absolute inset-0 cursor-default bg-text/20 backdrop-blur-[1px]"
        aria-label="关闭后台任务"
        onClick={onClose}
      />
      <aside className="relative flex h-full w-[min(25rem,calc(100vw-1rem))] flex-col border-l border-border bg-panel shadow-2xl animate-slide-in">
        <div className="flex h-14 shrink-0 items-center justify-between border-b border-border bg-card/60 px-4">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.12em] text-text">
            <ListChecks className="size-4" aria-hidden="true" />
            后台任务队列
          </div>
          <Button type="button" variant="ghost" size="icon-sm" className="text-muted hover:bg-card hover:text-text" aria-label="关闭后台任务" title="关闭" onClick={onClose}>
            <X />
          </Button>
        </div>

        <div className="flex-1 overflow-y-auto px-4">
          {loading && tasks.length === 0 && (
            <div className="flex items-center gap-2 py-6 text-xs text-muted" role="status">
              <RefreshCw className="size-3.5 animate-spin" aria-hidden="true" />
              正在读取任务状态…
            </div>
          )}
          {!loading && error && (
            <div className="flex gap-2 py-6 text-xs text-danger">
              <AlertCircle className="size-4 shrink-0" aria-hidden="true" />
              <span className="break-words">{error}</span>
            </div>
          )}
          {!loading && !error && tasks.length === 0 && (
            <div className="py-10 text-center text-xs text-muted">当前没有待处理或失败任务</div>
          )}
          {visibleTasks.map((task) => <TaskPreview key={task.id} task={task} />)}
          {hiddenTaskCount > 0 && (
            <div className="border-b border-border py-3 text-center text-[11px] text-muted">
              还有 {hiddenTaskCount} 条任务，进入任务页查看完整记录
            </div>
          )}
        </div>

        <div className="shrink-0 border-t border-border bg-card/50 p-4">
          <Button type="button" variant="outline" className="w-full justify-center gap-2" onClick={onViewAll}>
            <ListChecks className="size-4" aria-hidden="true" />
            查看全部任务
          </Button>
        </div>
      </aside>
    </div>
  );
}
