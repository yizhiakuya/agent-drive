"use client";
import { CheckCircle2, Circle, CircleX, ListChecks, LoaderCircle, SkipForward } from "lucide-react";

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
} as const;

export function PlanCard({ plan }: { plan: PlanStep[] }) {
  const doneCount = plan.filter((s) => s.status === "done").length;
  return (
    <div className="rounded-lg border border-border bg-panel px-4 py-3 text-sm">
      <div className="mb-2 flex items-center justify-between font-semibold">
        <span className="flex items-center gap-1.5"><ListChecks className="size-3.5 text-muted" /> 执行计划（{doneCount}/{plan.length}）</span>
        <span className="font-mono text-[10px] text-muted">后台执行</span>
      </div>
      {plan.map((s, i) => (
        <div key={i} className={`flex items-center gap-2 border-t border-border/70 py-1.5 ${s.status === "failed" ? "text-danger" : s.status === "in_progress" ? "text-warn" : ""}`}>
          {(() => { const Icon = ICONS[s.status as keyof typeof ICONS] || Circle; return <Icon className={`size-3.5 shrink-0 ${s.status === "failed" ? "text-danger" : s.status === "in_progress" ? "animate-spin text-warn" : s.status === "done" ? "text-success" : "text-muted"}`} />; })()}
          <span className="min-w-0 break-words">{s.text}</span>
        </div>
      ))}
    </div>
  );
}
