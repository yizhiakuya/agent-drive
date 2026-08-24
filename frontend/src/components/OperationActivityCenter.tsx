"use client";

import { useEffect, useMemo, useState } from "react";
import { Activity, AlertCircle, CheckCircle2, CircleX, LoaderCircle, Trash2, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  clearFinishedOperationActivities,
  markOperationActivitiesRead,
  removeOperationActivity,
  useOperationActivities,
  type OperationActivity,
} from "@/lib/operation-activity";

function statusLabel(status: OperationActivity["status"]) {
  if (status === "running") return "进行中";
  if (status === "succeeded") return "已完成";
  if (status === "partial") return "部分完成";
  if (status === "cancelled") return "已取消";
  return "失败";
}

function statusIcon(status: OperationActivity["status"]) {
  if (status === "running") return <LoaderCircle className="size-4 animate-spin text-info" aria-hidden="true" />;
  if (status === "succeeded") return <CheckCircle2 className="size-4 text-success" aria-hidden="true" />;
  if (status === "partial") return <AlertCircle className="size-4 text-warn" aria-hidden="true" />;
  return <CircleX className="size-4 text-danger" aria-hidden="true" />;
}

function elapsed(activity: OperationActivity) {
  const end = activity.status === "running" ? Date.now() : activity.updatedAt;
  const seconds = Math.max(0, Math.round((end - activity.startedAt) / 1000));
  if (seconds < 60) return `${seconds} 秒`;
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}

function progress(activity: OperationActivity) {
  if (typeof activity.total !== "number" || activity.total <= 0 || typeof activity.completed !== "number") return null;
  return Math.max(0, Math.min(100, (activity.completed / activity.total) * 100));
}

function ActivityRow({ activity }: { activity: OperationActivity }) {
  const value = progress(activity);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (activity.status !== "running") return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [activity.status]);
  const runningElapsed = activity.status === "running" ? Math.max(0, Math.round((now - activity.startedAt) / 1000)) : null;
  const timeLabel = runningElapsed == null ? elapsed(activity) : runningElapsed < 60 ? `${runningElapsed} 秒` : `${Math.floor(runningElapsed / 60)} 分 ${runningElapsed % 60} 秒`;

  return (
    <li className="border-b border-border/70 px-3 py-3 last:border-b-0">
      <div className="flex items-start gap-2">
        <span className="mt-0.5 shrink-0">{statusIcon(activity.status)}</span>
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <div className="flex items-center gap-1.5">
                <div className="min-w-0 truncate text-sm font-medium text-text">{activity.title}</div>
                <span className="shrink-0 text-[10px] text-muted">{statusLabel(activity.status)}</span>
              </div>
              <div className="mt-0.5 truncate text-[11px] text-muted">{activity.target || activity.operation || activity.source}</div>
            </div>
            {activity.status !== "running" && (
              <Button type="button" variant="ghost" size="icon-sm" className="-mr-1 -mt-1 shrink-0 text-muted hover:text-text"
                      onClick={() => removeOperationActivity(activity.id)} aria-label={`移除${activity.title}`} title="移除记录">
                <X className="size-3.5" />
              </Button>
            )}
          </div>
          <div className="mt-1 flex items-center justify-between gap-2 text-[11px] text-muted">
            <span className="truncate">{activity.message || statusLabel(activity.status)}</span>
            <span className="shrink-0">{timeLabel}</span>
          </div>
          {value == null ? (
            activity.status === "running" && <div className="mt-2 h-1 overflow-hidden rounded-full bg-border"><div className="h-full w-1/3 animate-pulse rounded-full bg-info" /></div>
          ) : (
            <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-border" aria-label={`进度 ${Math.round(value)}%`}>
              <div className={`h-full rounded-full transition-[width] ${activity.status === "failed" ? "bg-danger" : activity.status === "partial" ? "bg-warn" : "bg-success"}`} style={{ width: `${value}%` }} />
            </div>
          )}
          {(activity.succeeded !== undefined || activity.failed !== undefined || activity.error) && (
            <div className="mt-1.5 text-[11px] text-muted">
              {activity.succeeded !== undefined && `成功 ${activity.succeeded}`}
              {activity.failed !== undefined && ` · 失败 ${activity.failed}`}
              {activity.error && <span className="text-danger"> · {activity.error}</span>}
            </div>
          )}
        </div>
      </div>
    </li>
  );
}

/** 全局操作活动入口；运行中状态仅在当前页面进程内保留，完成记录短期落在浏览器本地。 */
export default function OperationActivityCenter() {
  const activities = useOperationActivities();
  const [open, setOpen] = useState(false);
  const active = useMemo(() => activities.filter((item) => item.status === "running"), [activities]);
  const finished = useMemo(() => activities.filter((item) => item.status !== "running"), [activities]);
  const unread = activities.filter((item) => item.unread).length;

  useEffect(() => {
    if (!open) return;
    markOperationActivitiesRead();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open]);

  return (
    <div className="relative shrink-0">
      <Button type="button" variant="ghost" size="icon-lg" className="relative text-muted hover:bg-card hover:text-text"
              onClick={() => setOpen((value) => !value)} aria-label="打开操作活动中心" title="操作活动">
        <Activity className="size-4" />
        {(active.length > 0 || unread > 0) && (
          <span className={`absolute right-1 top-1 grid min-w-4 place-items-center rounded-full px-1 text-[9px] font-bold leading-4 text-white ${active.length > 0 ? "bg-info" : "bg-danger"}`}>
            {active.length > 0 ? active.length : unread}
          </span>
        )}
      </Button>
      {open && (
        <section className="absolute right-0 top-12 z-50 w-[min(25rem,calc(100vw-1rem))] overflow-hidden rounded-lg border border-border bg-panel shadow-2xl" role="dialog" aria-label="操作活动中心">
          <div className="flex items-center justify-between border-b border-border px-3 py-2.5">
            <div>
              <h2 className="text-sm font-semibold text-text">操作活动</h2>
              <p className="mt-0.5 text-[11px] text-muted">当前执行和最近结果</p>
            </div>
            <div className="flex items-center gap-1">
              {finished.length > 0 && <Button type="button" variant="ghost" size="icon-sm" onClick={clearFinishedOperationActivities} aria-label="清除已完成操作记录" title="清除已完成记录"><Trash2 className="size-3.5" /></Button>}
              <Button type="button" variant="ghost" size="icon-sm" onClick={() => setOpen(false)} aria-label="关闭操作活动中心" title="关闭"><X className="size-4" /></Button>
            </div>
          </div>
          {activities.length === 0 ? (
            <div className="px-4 py-8 text-center text-xs text-muted">暂无操作记录</div>
          ) : (
            <ul className="max-h-[min(28rem,calc(100vh-8rem))] overflow-y-auto" aria-live="polite">
              {active.map((activity) => <ActivityRow key={activity.id} activity={activity} />)}
              {finished.map((activity) => <ActivityRow key={activity.id} activity={activity} />)}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}
