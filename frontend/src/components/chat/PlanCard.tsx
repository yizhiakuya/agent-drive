"use client";
import { CheckCircle2, Circle, CircleX, ChevronDown, ListChecks, LoaderCircle, SkipForward } from "lucide-react";
import { fmtElapsedMs } from "@/lib/format";
import { useState } from "react";

export interface PlanStep {
  text: string;
  status: string;
}

const ICONS = {
  pending: Circle,
  in_progress: LoaderCircle,
  done: CheckCircle2,
  skipped: SkipForward,
  failed: CircleX,
  cancelled: CircleX,
} as const;

export function PlanCard({ plan, phase, modelElapsedMs }: {
  plan: PlanStep[];
  phase?: "model" | "tool";
  modelElapsedMs?: number | null;
}) {
  const doneCount = plan.filter((s) => s.status === "done").length;
  const [open, setOpen] = useState(true);
  const hasCancelled = plan.some((s) => s.status === "cancelled");
  const hasFailed = plan.some((s) => s.status === "failed");
  const planStatus = hasCancelled ? "已停止" : hasFailed ? "失败" : null;
  return (
    <details open={open} onToggle={(event) => setOpen(event.currentTarget.open)} className="group rounded-lg border border-border bg-panel px-4 py-3 text-sm">
      <summary className="flex cursor-pointer list-none items-center justify-between font-semibold [&::-webkit-details-marker]:hidden">
        <span className="flex min-w-0 items-center gap-1.5"><ListChecks className="size-3.5 shrink-0 text-muted" /> 执行计划（{doneCount}/{plan.length}）{planStatus && <span className="text-[10px] font-normal text-warn">· {planStatus}</span>}</span>
        <span className="flex shrink-0 items-center gap-2">
          <span className="font-mono text-[10px] text-muted">
          {phase === "model" && typeof modelElapsedMs === "number"
            ? `模型思考 ${fmtElapsedMs(modelElapsedMs)}`
            : phase === "tool" ? "执行工具中" : "当前会话"}
          </span>
          <ChevronDown className="size-3.5 text-muted transition-transform group-open:rotate-180" aria-hidden="true" />
        </span>
      </summary>
      <div className="mt-2">
        {plan.map((s, i) => (
          <div key={i} className={`flex items-center gap-2 border-t border-border/70 py-1.5 ${s.status === "failed" ? "text-danger" : s.status === "cancelled" ? "text-muted" : s.status === "in_progress" ? "text-warn" : ""}`}>
            {(() => { const Icon = ICONS[s.status as keyof typeof ICONS] || Circle; return <Icon className={`size-3.5 shrink-0 ${s.status === "failed" ? "text-danger" : s.status === "cancelled" ? "text-muted" : s.status === "in_progress" ? "animate-spin text-warn" : s.status === "done" ? "text-success" : "text-muted"}`} />; })()}
            <span className="min-w-0 break-words">{s.text}</span>
          </div>
        ))}
      </div>
    </details>
  );
}
