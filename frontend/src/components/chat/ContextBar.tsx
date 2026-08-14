"use client";
import { fmtTokens } from "@/lib/format";

export function ContextBar({ usage }: { usage: { used: number; total: number; percent: number } }) {
  const { used = 0, total = 262144, percent = 0 } = usage;
  const pct = Math.min(100, percent);
  const color = pct > 80 ? "var(--danger)" : pct > 50 ? "var(--warn)" : "var(--accent2)";
  return (
    <div className="flex items-center gap-2 px-4 py-2 border-t border-border bg-panel text-xs" title={`上下文占用: 已用 ${fmtTokens(used)} / ${fmtTokens(total)}`}>
      <span className="text-muted whitespace-nowrap">上下文</span>
      <div className="flex-1 h-1.5 bg-card rounded-full overflow-hidden">
        <div className="h-full rounded-full" style={{ width: `${pct}%`, background: color }} />
      </div>
      <span className="text-muted whitespace-nowrap">{fmtTokens(used)} / {fmtTokens(total)}</span>
    </div>
  );
}
