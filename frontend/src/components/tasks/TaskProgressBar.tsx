import type { TaskProgress } from "@/lib/api/tasks";
import { taskPercent, taskProgressText } from "./task-presenter";

/** 任务列表、抽屉和详情共用的确定/不定进度条，避免未知总量被误显示为 0%。 */
export default function TaskProgressBar({
  progress,
  active = true,
  compact = false,
  showValue = true,
  fallbackText,
}: {
  progress: TaskProgress;
  active?: boolean;
  compact?: boolean;
  showValue?: boolean;
  fallbackText?: string;
}) {
  const percent = taskPercent(progress);
  const indeterminate = percent === null;
  return (
    <div className={`flex items-center gap-2 ${compact ? "gap-1.5" : ""}`}>
      <div
        className={`relative flex-1 overflow-hidden rounded-full bg-card ${compact ? "h-1" : "h-1.5"}`}
        role="progressbar"
        aria-label={indeterminate ? "进度处理中" : `进度 ${percent}%`}
        aria-valuemin={0}
        aria-valuemax={100}
        {...(percent === null ? {} : { "aria-valuenow": percent })}
      >
        <div
          className={`h-full rounded-full bg-accent transition-[width] duration-300 ${indeterminate ? "w-1/3 animate-pulse" : ""}`}
          style={indeterminate ? undefined : { width: `${percent}%` }}
        />
      </div>
      {showValue && (
        <span className={`${compact ? "min-w-[3.5rem]" : "w-20"} shrink-0 text-right text-[11px] tabular-nums text-muted`}>
          {taskProgressText(progress, active, fallbackText)}
        </span>
      )}
    </div>
  );
}
