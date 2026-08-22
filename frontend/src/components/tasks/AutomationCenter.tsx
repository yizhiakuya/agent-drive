"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { CalendarClock, Play, RefreshCw, RotateCcw, ToggleLeft, ToggleRight } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { emitTasksChanged } from "@/lib/events";
import { fmtTime } from "@/lib/format";
import { deleteSchedule, listSchedules, runSchedule, saveSchedule, type ScheduleRecord } from "@/lib/api/schedules";

function timestamp(value: ScheduleRecord["next_run_at"]): number | null {
  if (value == null) return null;
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function scheduleLabel(schedule: ScheduleRecord) {
  if (schedule.schedule_kind === "daily") return `每天 ${schedule.schedule_value || "未设置"}`;
  if (schedule.schedule_kind === "interval") return `每 ${schedule.schedule_value || "未设置"}`;
  return `Cron ${schedule.cron || schedule.schedule_value || "未设置"}`;
}

function requestBody(schedule: ScheduleRecord) {
  return {
    cron: schedule.cron || "",
    scheduleKind: schedule.schedule_kind,
    scheduleValue: schedule.schedule_value || "",
    taskType: schedule.task_type,
    lane: schedule.lane,
    payload: schedule.payload || {},
    enabled: schedule.enabled,
    priority: schedule.priority,
    maxAttempts: schedule.max_attempts,
    timezone: schedule.timezone,
  };
}

interface AutomationCenterProps {
  onViewTasks?: () => void;
}

/** 计划控制面：所有状态来自 schedule API，执行仍由持久任务 Worker 完成。 */
export default function AutomationCenter({ onViewTasks }: AutomationCenterProps) {
  const [schedules, setSchedules] = useState<ScheduleRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const loadRequestRef = useRef(0);

  const load = useCallback(async () => {
    const request = ++loadRequestRef.current;
    setLoading(true);
    try {
      const result = await listSchedules();
      if (request !== loadRequestRef.current) return;
      setSchedules(result.schedules || []);
      setError("");
    } catch (reason) {
      if (request !== loadRequestRef.current) return;
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      if (request === loadRequestRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function toggle(schedule: ScheduleRecord) {
    setBusy(schedule.name);
    setMessage("");
    try {
      await saveSchedule(schedule.name, { ...requestBody(schedule), enabled: !schedule.enabled });
      await load();
      setMessage(`${schedule.name} 已${schedule.enabled ? "停用" : "启用"}`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setBusy(null);
    }
  }

  async function run(schedule: ScheduleRecord) {
    setBusy(schedule.name);
    setMessage("");
    try {
      const result = await runSchedule(schedule.name);
      emitTasksChanged();
      setMessage(result.queued ? `${schedule.name} 已进入任务队列` : `${schedule.name} 已有相同任务`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setBusy(null);
    }
  }

  async function remove(schedule: ScheduleRecord) {
    if (!window.confirm(`删除计划「${schedule.name}」？`)) return;
    setBusy(schedule.name);
    try {
      await deleteSchedule(schedule.name);
      await load();
      setMessage(`已删除 ${schedule.name}`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="border-y border-border bg-panel">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3 sm:px-5">
        <div>
          <h3 className="flex items-center gap-2 text-sm font-bold"><CalendarClock className="size-4 text-muted" /> 自动化中心</h3>
          <p className="mt-0.5 text-xs text-muted">查看计划、控制启停，并把一次执行交给后台任务队列。</p>
        </div>
        <div className="flex items-center gap-1.5">
          <Button type="button" variant="ghost" size="icon-sm" onClick={() => void load()} disabled={loading} aria-label="刷新自动化计划" title="刷新">
            <RefreshCw className={loading ? "size-4 animate-spin" : "size-4"} />
          </Button>
          <Button type="button" variant="outline" size="sm" onClick={onViewTasks}>查看任务历史</Button>
        </div>
      </div>
      {error && <Alert variant="destructive" className="m-3 bg-danger-soft text-danger"><AlertDescription>{error}</AlertDescription></Alert>}
      {message && <div className="px-4 py-2 text-xs text-success">{message}</div>}
      {loading && schedules.length === 0 ? (
        <div className="p-5 text-sm text-muted">正在加载计划…</div>
      ) : schedules.length === 0 ? (
        <div className="p-5 text-sm text-muted">暂无自动化计划。可以在对话中让 Agent 创建计划。</div>
      ) : (
        <div className="divide-y divide-border">
          {schedules.map((schedule) => {
            const next = timestamp(schedule.next_run_at);
            const last = timestamp(schedule.last_run_at);
            const pending = busy === schedule.name;
            return (
              <div key={schedule.name} className="flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:px-5">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium text-sm">{schedule.name}</span>
                    <Badge variant={schedule.enabled ? "default" : "outline"}>{schedule.enabled ? "启用" : "停用"}</Badge>
                    {schedule.last_error && <Badge variant="destructive">需关注</Badge>}
                  </div>
                  <div className="mt-1 text-xs text-muted">{scheduleLabel(schedule)} · {schedule.timezone || "服务端时区"} · {schedule.task_type}</div>
                  <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted">
                    <span>下次：{next == null ? "未计算" : fmtTime(next)}</span>
                    <span>上次：{last == null ? "从未" : fmtTime(last)}</span>
                    {schedule.last_error && <span className="break-words text-danger">失败：{schedule.last_error}</span>}
                  </div>
                </div>
                <div className="flex shrink-0 flex-wrap items-center gap-1.5">
                  <Button type="button" variant="outline" size="sm" disabled={pending} onClick={() => void toggle(schedule)}>
                    {schedule.enabled ? <ToggleRight className="size-3.5" /> : <ToggleLeft className="size-3.5" />}
                    {schedule.enabled ? "停用" : "启用"}
                  </Button>
                  <Button type="button" variant="default" size="sm" disabled={pending} onClick={() => void run(schedule)}>
                    {schedule.last_error ? <RotateCcw className="size-3.5" /> : <Play className="size-3.5" />}
                    {schedule.last_error ? "重试" : "立即运行"}
                  </Button>
                  <Button type="button" variant="ghost" size="sm" disabled={pending} onClick={() => void remove(schedule)}>删除</Button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
