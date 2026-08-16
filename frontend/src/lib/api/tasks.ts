import { api, apiPath } from "./client";

export type TaskStatus =
  | "queued"
  | "running"
  | "retry_wait"
  | "cancelling"
  | "succeeded"
  | "failed"
  | "cancelled";

export interface TaskRecord {
  id: string;
  type: string;
  lane: string;
  status: TaskStatus;
  result: Record<string, unknown> | null;
  error: string | null;
  priority: number;
  resource_key: string | null;
  parent_id: string | null;
  origin: string;
  attempts: number;
  max_attempts: number;
  cancel_requested: boolean;
  progress: { current: number; total: number; message: string };
  created_at: number;
  updated_at: number;
  started_at: number | null;
  finished_at: number | null;
}

export interface TaskOverview {
  counts: Partial<Record<TaskStatus, number>>;
  workers: { online: boolean; count: number };
  index: {
    eligible_files: number;
    extracted_files: number;
    vector_files: number;
    non_vectorizable_files: number;
    missing_vectors: number;
    stale_vectors: number;
    embedding_configured: boolean;
    model: string;
  };
}

interface TaskListResponse {
  items: TaskRecord[];
  overview: TaskOverview;
}

export const listTasks = (status = "") =>
  api<TaskListResponse>(`/tasks${status ? `?status=${encodeURIComponent(status)}` : ""}`, { cache: "no-store" });

export const cancelTask = (id: string) =>
  api<{ task: TaskRecord }>(`/tasks/${encodeURIComponent(id)}/cancel`, { method: "POST" });

export const retryTask = (id: string) =>
  api<{ task: TaskRecord }>(`/tasks/${encodeURIComponent(id)}/retry`, { method: "POST" });

export const rebuildIndex = (force: boolean) =>
  api<{ queued: boolean; task: TaskRecord }>("/tasks/rebuild-index", {
    method: "POST",
    body: JSON.stringify({ force }),
  });

export const taskEventsUrl = () => apiPath("/tasks/events");
