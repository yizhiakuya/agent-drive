import { AlertCircle, CheckCircle2, Clock3, Database, Info, LoaderCircle, RefreshCw, XCircle } from "lucide-react";
import type { TaskDetailResponse, TaskRecord } from "@/lib/api/tasks";
import { formatJson, fmtTime } from "@/lib/format";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import TaskProgressBar from "./TaskProgressBar";
import { ACTIVE_STATUSES, laneLabel, resourceLabel, STATUS_LABELS, STATUS_VARIANT, taskLabel, taskStatusHint } from "./task-presenter";

const ACTIVE = ACTIVE_STATUSES;

function valueOrDash(value: string | number | null | undefined) {
  return value === null || value === undefined || value === "" ? "-" : String(value);
}

function timeOrDash(value: number | null) {
  return value ? fmtTime(value) : "-";
}

function DetailField({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] text-muted">{label}</dt>
      <dd className={`mt-0.5 break-words text-xs text-text ${mono ? "font-mono" : ""}`}>{value}</dd>
    </div>
  );
}

function JsonBlock({ label, value, emptyText }: { label: string; value: unknown; emptyText: string }) {
  // 任务 payload/result 可能包含 provider 参数，统一走脱敏格式化，详情页不能直接回显原始 JSON。
  const content = formatJson(value);
  return (
    <section className="min-w-0">
      <h4 className="mb-1.5 text-xs font-semibold text-text">{label}</h4>
      {content ? (
        <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-card p-3 font-mono text-[11px] leading-relaxed text-text">
          {content}
        </pre>
      ) : (
        <p className="rounded-md border border-dashed border-border p-3 text-xs text-muted">{emptyText}</p>
      )}
    </section>
  );
}

function ProgressSummary({ task }: { task: TaskRecord }) {
  const { current, total, message } = task.progress;
  const hasProgress = ACTIVE.has(task.status) || current > 0 || total > 0 || Boolean(message);
  if (!hasProgress) return null;
  return (
    <section className="mt-3 rounded-md border border-border bg-card p-3" data-testid={`task-progress-${task.id}`} aria-live="polite">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <h4 className="text-xs font-semibold text-text">执行进度</h4>
          <Badge variant={STATUS_VARIANT[task.status]}>{STATUS_LABELS[task.status]}</Badge>
        </div>
        <span className="shrink-0 text-[11px] text-muted">更新于 {timeOrDash(task.updated_at)}</span>
      </div>
      <div className="mt-3"><TaskProgressBar progress={task.progress} active={ACTIVE.has(task.status)} fallbackText={taskStatusHint(task.status)} /></div>
      <p className="mt-2 break-words text-xs leading-relaxed text-muted">{message || taskStatusHint(task.status)}</p>
    </section>
  );
}

function childSummary(children: TaskRecord[]) {
  const completed = children.filter((child) => child.status === "succeeded").length;
  const active = children.filter((child) => ACTIVE.has(child.status)).length;
  const failed = children.filter((child) => child.status === "failed" || child.status === "cancelled").length;
  const parts = [`已完成 ${completed}/${children.length}`];
  if (active > 0) parts.push(`进行中 ${active}`);
  if (failed > 0) parts.push(`异常 ${failed}`);
  return parts.join(" · ");
}

function ChildTask({ task }: { task: TaskRecord }) {
  return (
    <li className="min-w-0 border-t border-border py-2 first:border-t-0">
      <div className="flex min-w-0 items-start gap-2">
        <div className="mt-0.5 shrink-0">
          {task.status === "succeeded" ? <CheckCircle2 className="size-3.5 text-success" />
            : task.status === "failed" ? <XCircle className="size-3.5 text-danger" />
              : ACTIVE.has(task.status) ? <LoaderCircle className="size-3.5 animate-spin text-accent" />
                : <Clock3 className="size-3.5 text-muted" />}
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="break-all text-xs font-medium text-text">{taskLabel(task)}</span>
            <Badge variant={STATUS_VARIANT[task.status]}>{STATUS_LABELS[task.status]}</Badge>
          </div>
          {(ACTIVE.has(task.status) || task.progress.total > 0 || task.progress.current > 0) && (
            <div className="mt-1 max-w-sm"><TaskProgressBar progress={task.progress} active={ACTIVE.has(task.status)} compact fallbackText={taskStatusHint(task.status)} /></div>
          )}
          {task.resource_key && <p className="mt-0.5 break-all text-[11px] text-muted">{resourceLabel(task.resource_key)}</p>}
          {task.progress.message && <p className="mt-0.5 break-words text-[11px] text-muted">{task.progress.message}</p>}
          {task.error && <p className="mt-0.5 break-words text-[11px] text-danger">{task.error}</p>}
        </div>
      </div>
    </li>
  );
}

/**
 * 展示任务的执行上下文，而不是只重复列表摘要：输入、结果、错误和子任务都来自详情接口。
 * 列表中的 task 作为降级快照保留，使详情请求失败或尚未返回时行本身仍可读。
 */
export default function TaskDetails({
  task,
  detail,
  loading,
  error,
  onRetry,
}: {
  task: TaskRecord;
  detail: TaskDetailResponse | null;
  loading: boolean;
  error: string;
  onRetry: () => void;
}) {
  const displayTask = detail?.task ?? task;
  const children = detail?.children ?? [];
  return (
    <div
      id={`task-details-${task.id}`}
      role="region"
      aria-label="任务详情"
      className="mt-3 border-t border-border pt-3"
      data-testid={`task-details-${task.id}`}
    >
      <div className="mb-3 flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <Info className="size-4 shrink-0 text-muted" />
          <h4 className="text-xs font-semibold">任务详情</h4>
          {loading && <LoaderCircle className="size-3.5 animate-spin text-muted" aria-label="正在加载任务详情" />}
        </div>
        <Button type="button" variant="outline" size="sm" className="h-8 px-2 text-xs" onClick={onRetry}>
          <RefreshCw className="size-3.5" />
          刷新详情
        </Button>
      </div>

      {error && (
        <Alert className="mb-3 border-danger/30 bg-danger-soft text-danger">
          <AlertCircle className="size-4 shrink-0" />
          <AlertDescription className="flex min-w-0 items-center justify-between gap-2">
            <span className="break-words">{error}</span>
            <Button type="button" variant="ghost" size="sm" className="h-8 shrink-0 px-2 text-danger hover:bg-danger/10" onClick={onRetry}>
              重试
            </Button>
          </AlertDescription>
        </Alert>
      )}

      <dl className="grid grid-cols-2 gap-x-4 gap-y-3 border-b border-border pb-3 sm:grid-cols-3 lg:grid-cols-4">
        <DetailField label="任务 ID" value={valueOrDash(displayTask.id)} mono />
        <DetailField label="任务类型" value={valueOrDash(taskLabel(displayTask))} />
        <DetailField label="执行队列" value={valueOrDash(laneLabel(displayTask.lane))} />
        <DetailField label="来源" value={valueOrDash(displayTask.origin)} />
        <DetailField label="尝试次数" value={`${displayTask.attempts}/${displayTask.max_attempts}`} />
        <DetailField label="创建时间" value={timeOrDash(displayTask.created_at)} />
        <DetailField label="开始时间" value={timeOrDash(displayTask.started_at)} />
        <DetailField label="结束时间" value={timeOrDash(displayTask.finished_at)} />
        <DetailField label="更新时间" value={timeOrDash(displayTask.updated_at)} />
        {displayTask.resource_key && <DetailField label="资源" value={resourceLabel(displayTask.resource_key)} mono />}
        {displayTask.parent_id && <DetailField label="父任务" value={displayTask.parent_id} mono />}
      </dl>

      <ProgressSummary task={displayTask} />

      <div className="mt-3 grid gap-3 lg:grid-cols-2">
        <JsonBlock label="执行输入" value={displayTask.payload} emptyText="该任务没有可展示的输入参数。" />
        <JsonBlock label="执行结果" value={displayTask.result} emptyText={ACTIVE.has(displayTask.status) ? "任务尚未完成，结果会在执行结束后出现。" : "该任务没有返回结构化结果。"} />
      </div>

      {displayTask.error && (
        <section className="mt-3 rounded-md border border-danger/30 bg-danger-soft p-3">
          <h4 className="mb-1 flex items-center gap-1.5 text-xs font-semibold text-danger"><AlertCircle className="size-3.5" />失败原因</h4>
          <p className="whitespace-pre-wrap break-words text-xs leading-relaxed text-danger">{displayTask.error}</p>
        </section>
      )}

      {children.length > 0 && (
        <section className="mt-3 rounded-md border border-border bg-card p-3">
          <h4 className="mb-1.5 flex flex-wrap items-center gap-1.5 text-xs font-semibold">
            <Database className="size-3.5 text-muted" />
            <span>子任务（{children.length}）</span>
            <span className="font-normal text-muted">{childSummary(children)}</span>
          </h4>
          <ul>{children.map((child) => <ChildTask key={child.id} task={child} />)}</ul>
        </section>
      )}
    </div>
  );
}
