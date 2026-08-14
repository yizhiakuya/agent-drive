"use client";

export interface PlanStep {
  text: string;
  status: string;
}

const ICONS: Record<string, string> = { pending: "⏳", in_progress: "🔄", done: "✅", skipped: "⏭️", failed: "❌" };

export function PlanCard({ plan }: { plan: PlanStep[] }) {
  const doneCount = plan.filter((s) => s.status === "done").length;
  return (
    <div className="border border-border rounded-lg bg-panel px-4 py-3 text-sm">
      <div className="font-semibold mb-2">📋 执行计划（{doneCount}/{plan.length}）</div>
      {plan.map((s, i) => (
        <div key={i} className={`flex items-center gap-2 py-1 ${s.status === "failed" ? "text-danger" : s.status === "in_progress" ? "text-warn" : ""}`}>
          <span>{ICONS[s.status] || "⏳"}</span>
          <span>{s.text}</span>
        </div>
      ))}
    </div>
  );
}
