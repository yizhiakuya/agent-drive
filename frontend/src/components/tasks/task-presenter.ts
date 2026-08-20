import type { TaskProgress, TaskRecord, TaskStatus } from "@/lib/api/tasks";

export const TASK_LABELS: Record<string, string> = {
  "index.file": "文件索引",
  "index.rebuild": "重建搜索索引",
  "index.embed": "文件向量化",
  "index.vision": "图片视觉索引",
  "index.cleanup": "清理失效索引",
  "maintenance.daily": "系统维护",
  "automation.run": "自动化规则",
};

export const STATUS_LABELS: Record<TaskStatus, string> = {
  queued: "等待中",
  running: "运行中",
  retry_wait: "等待重试",
  cancelling: "正在取消",
  succeeded: "已完成",
  failed: "失败",
  cancelled: "已取消",
};

export const STATUS_VARIANT: Record<TaskStatus, "default" | "secondary" | "destructive" | "outline"> = {
  queued: "secondary",
  running: "secondary",
  retry_wait: "secondary",
  cancelling: "secondary",
  succeeded: "outline",
  failed: "destructive",
  cancelled: "outline",
};

export const ACTIVE_STATUSES = new Set<TaskStatus>(["queued", "running", "retry_wait", "cancelling"]);

export const STATUS_HINTS: Record<TaskStatus, string> = {
  queued: "等待 Worker 领取",
  running: "正在执行",
  retry_wait: "等待下一次重试",
  cancelling: "正在等待当前步骤结束",
  succeeded: "任务已完成",
  failed: "执行失败",
  cancelled: "任务已取消",
};

export const LANE_LABELS: Record<string, string> = {
  index: "索引队列",
  orchestration: "编排队列",
  maintenance: "维护队列",
};

export function taskLabel(task: Pick<TaskRecord, "type"> | string) {
  const type = typeof task === "string" ? task : task.type;
  return TASK_LABELS[type] || type;
}

export function taskStatusHint(status: TaskStatus) {
  return STATUS_HINTS[status];
}

export function laneLabel(lane: string) {
  return LANE_LABELS[lane] || lane;
}

export function taskPercent(progress: TaskProgress) {
  if (progress.total <= 0) return null;
  return Math.min(100, Math.round((progress.current / progress.total) * 100));
}

export function taskProgressText(progress: TaskProgress, active = true, fallbackText?: string) {
  const percent = taskPercent(progress);
  if (percent !== null) return `${progress.current}/${progress.total} · ${percent}%`;
  if (progress.current > 0) return `已处理 ${progress.current}`;
  return fallbackText || (active ? "处理中" : "-");
}

export function resourceLabel(resourceKey: string | null) {
  return resourceKey?.replace(/^file:|^index:/, "") || "";
}
