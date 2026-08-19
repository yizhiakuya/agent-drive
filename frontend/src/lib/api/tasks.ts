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

/**
 * 为指定文件排入全文抽取和文本 embedding 任务。
 *
 * @param files owner 根目录下的相对文件路径列表。
 * @param force 是否忽略已有的当前版本向量并重新生成。
 * @returns 是否创建了新任务及任务记录；已有相同活跃任务时 queued 为 false。
 */
export const enqueueEmbedIndex = (files: string[], force = false) =>
  api<{ queued: boolean; task: TaskRecord }>("/tasks/embed-index", {
    method: "POST",
    body: JSON.stringify({ files, force }),
  });

/**
 * 为指定图片排入视觉描述、全文索引和 embedding 任务。
 *
 * @param files owner 根目录下的相对图片路径列表。
 * @param force 是否忽略已有的当前视觉模型向量并重新识别。
 * @returns 是否创建了新任务及任务记录；视觉识别在 Worker 中异步执行。
 */
export const enqueueVisionIndex = (files: string[], force = false) =>
  api<{ queued: boolean; task: TaskRecord }>("/tasks/vision-index", {
    method: "POST",
    body: JSON.stringify({ files, force }),
  });

export const taskEventsUrl = () => apiPath("/tasks/events");
