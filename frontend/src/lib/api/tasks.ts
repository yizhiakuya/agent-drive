import { api, apiPath } from "./client";

export type TaskStatus =
  | "queued"
  | "running"
  | "retry_wait"
  | "cancelling"
  | "succeeded"
  | "failed"
  | "cancelled";

export interface TaskProgress {
  current: number;
  total: number;
  message: string;
}

/** 列表和详情共用的任务快照；payload/result/error 供详情页解释实际执行结果。 */
export interface TaskRecord {
  id: string;
  type: string;
  lane: string;
  status: TaskStatus;
  payload: Record<string, unknown> | null;
  result: Record<string, unknown> | null;
  error: string | null;
  priority: number;
  resource_key: string | null;
  parent_id: string | null;
  origin: string;
  attempts: number;
  max_attempts: number;
  cancel_requested: boolean;
  progress: TaskProgress;
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
    text_vector_files?: number;
    vision_vector_files?: number;
    text_missing_vectors?: number;
    vision_missing_vectors?: number;
    embedding_configured: boolean;
    model: string;
  };
}

interface TaskListResponse {
  items: TaskRecord[];
  has_more: boolean;
  overview: TaskOverview;
}

export interface TaskPruneResult {
  /** HTTP 契约中的直观字段；旧的维护结果也保留 jobs 作为兼容字段。 */
  removed: number;
  jobs: number;
  events: number;
  workers: number;
  older_than_days: number;
  keep_recent: number;
}

export interface TaskClearResult {
  /** 删除的任务记录总数，包含被一并清理的子任务。 */
  removed: number;
  jobs: number;
  events: number;
  workers: number;
}

export interface TaskDeleteResult {
  task_id: string;
  /** 删除的任务记录总数；删除父任务时可能包含其终态子任务。 */
  removed: number;
  jobs: number;
}

/** owner-scoped 详情接口返回的完整任务和父任务下的子任务摘要。 */
export interface TaskDetailResponse {
  task: TaskRecord;
  children: TaskRecord[];
}

/** 读取顶层任务列表；列表只用于概览，详情字段通过 getTaskDetail 按需读取。 */
export const listTasks = (status = "", options?: { limit?: number; offset?: number }) => {
  const params = new URLSearchParams();
  if (status) params.set("status", status);
  if (options?.limit !== undefined) params.set("limit", String(options.limit));
  if (options?.offset !== undefined) params.set("offset", String(options.offset));
  const query = params.toString();
  return api<TaskListResponse>(`/tasks${query ? `?${query}` : ""}`, { cache: "no-store" });
};

/** 按任务 ID 读取 payload/result/error/children；不缓存，避免展开后看到过期执行结果。 */
export const getTaskDetail = (id: string) =>
  api<TaskDetailResponse>(`/tasks/${encodeURIComponent(id)}`, { cache: "no-store" });

export const cancelTask = (id: string) =>
  api<{ task: TaskRecord }>(`/tasks/${encodeURIComponent(id)}/cancel`, { method: "POST" });

export const retryTask = (id: string) =>
  api<{ task: TaskRecord }>(`/tasks/${encodeURIComponent(id)}/retry`, { method: "POST" });

/** 自动维护入口：按服务端固定的 30 天/2000 条策略清理历史。任务页不调用此入口。 */
export const pruneTaskHistory = () =>
  api<TaskPruneResult>("/tasks/prune-history", { method: "POST" });

/** 清理当前 owner 所有可安全回收的已结束任务；不会触碰活动任务。 */
export const clearTerminalTasks = () =>
  api<TaskClearResult>("/tasks/clear-terminal", { method: "POST" });

/** 删除一条已结束任务；父任务会在安全时连同已结束子任务一起删除。 */
export const deleteTask = (id: string) =>
  api<TaskDeleteResult>(`/tasks/${encodeURIComponent(id)}`, { method: "DELETE" });

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

/** 后台清空当前 owner 的文本/视觉向量，不删除原文件或正文索引。 */
export const clearVectors = () =>
  api<{ queued: boolean; execution_mode: "background"; message: string; task: TaskRecord }>("/tasks/clear-vectors", {
    method: "POST",
  });

export const taskEventsUrl = () => apiPath("/tasks/events");
