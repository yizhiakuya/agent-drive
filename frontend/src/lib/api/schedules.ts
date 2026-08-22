import { api } from "./client";

export interface ScheduleRecord {
  name: string;
  cron: string | null;
  schedule_kind: "cron" | "interval" | "daily" | string;
  schedule_value: string | null;
  task_type: string;
  lane: string;
  payload: Record<string, unknown> | null;
  enabled: boolean;
  priority: number;
  max_attempts: number;
  timezone: string;
  next_run_at: number | string | null;
  last_run_at: number | string | null;
  last_error: string | null;
}

export const listSchedules = () =>
  api<{ schedules: ScheduleRecord[] }>("/schedules", { cache: "no-store" });

export const runSchedule = (name: string) =>
  api<{ queued: boolean; task: Record<string, unknown>; schedule: string }>(
    `/schedules/${encodeURIComponent(name)}/run`,
    { method: "POST" },
  );

export const saveSchedule = (name: string, body: Partial<ScheduleRecord>) =>
  api<{ schedule: ScheduleRecord }>(`/schedules/${encodeURIComponent(name)}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });

export const deleteSchedule = (name: string) =>
  api<{ deleted: string }>(`/schedules/${encodeURIComponent(name)}`, { method: "DELETE" });
