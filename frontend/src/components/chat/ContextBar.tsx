"use client";
import { fmtTokens } from "@/lib/format";

export function ContextBar({ usage }: { usage: { used: number; total: number; percent: number } }) {
  const { used = 0, total = 262144, percent = 0 } = usage;
  const pct = Math.min(100, percent);
  const color = pct > 80 ? "var(--danger)" : pct > 50 ? "var(--warn)" : "var(--accent2)";
  return (
    <div className="flex items-center gap-2 border-t border-border bg-card/40 px-4 py-2 text-xs" title={`上下文占用: 已用 ${fmtTokens(used)} / ${fmtTokens(total)}`}>
      <span className="whitespace-nowrap font-mono text-[10px] uppercase tracking-[0.1em] text-muted">上下文</span>
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-card">
        <div className="h-full rounded-full transition-[width] duration-300" style={{ width: `${Math.max(pct, 1.5)}%`, background: color }} />
      </div>
      <span className="whitespace-nowrap font-mono text-[10px] text-muted">{fmtTokens(used)} / {fmtTokens(total)}</span>
    </div>
  );
}
