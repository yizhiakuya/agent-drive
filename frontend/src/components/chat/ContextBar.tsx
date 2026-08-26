"use client";
import { useEffect, useRef } from "react";
import { fmtTokens } from "@/lib/format";
import { ChevronDown } from "lucide-react";

type ContextUsage = {
  used: number;
  total: number;
  percent: number;
  input?: number;
  output?: number;
  estimated?: boolean;
  compacted?: boolean;
};

export function ContextBar({ usage }: { usage: ContextUsage }) {
  const detailsRef = useRef<HTMLDetailsElement>(null);
  const { used = 0, total = 262144, percent = 0 } = usage;
  const pct = Math.min(100, Math.max(0, Number.isFinite(percent) ? percent : 0));
  const color = pct > 80 ? "var(--danger)" : pct > 50 ? "var(--warn)" : "var(--accent2)";
  const radius = 15;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference * (1 - pct / 100);
  const remaining = Math.max(0, total - used);
  const estimatePrefix = usage.estimated ? "估算 " : "";
  const compactPrefix = usage.compacted ? "已自动压缩 · " : "";
  const label = `${compactPrefix}${estimatePrefix}上下文窗口 ${Math.round(pct)}%，已用 ${fmtTokens(used)} / ${fmtTokens(total)}`;

  useEffect(() => {
    const details = detailsRef.current;
    if (!details) return;
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (details.open && event.target instanceof Node && !details.contains(event.target)) {
        details.open = false;
      }
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (details.open && event.key === "Escape") {
        event.preventDefault();
        details.open = false;
        details.querySelector<HTMLElement>("summary")?.focus();
      }
    };
    document.addEventListener("pointerdown", closeOnOutsidePointer, true);
    document.addEventListener("keydown", closeOnEscape, true);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutsidePointer, true);
      document.removeEventListener("keydown", closeOnEscape, true);
    };
  }, []);

  return (
    <details ref={detailsRef} data-testid="context-usage" className="group relative shrink-0">
      <summary
        data-testid="context-usage-summary"
        className="flex h-7 list-none cursor-pointer items-center gap-1.5 rounded-md px-1.5 text-[10px] text-muted transition-colors hover:bg-panel hover:text-text [&::-webkit-details-marker]:hidden"
        aria-label={label}
        title={label}
      >
        <span className="relative grid size-5 shrink-0 place-items-center" role="img" aria-label={label}>
          <svg viewBox="0 0 40 40" className="size-5 -rotate-90" aria-hidden="true">
            <circle cx="20" cy="20" r={radius} fill="none" stroke="var(--border)" strokeWidth="4" />
            <circle
              data-testid="context-progress-ring"
              cx="20"
              cy="20"
              r={radius}
              fill="none"
              stroke={color}
              strokeLinecap="round"
              strokeWidth="4"
              style={{ strokeDasharray: circumference, strokeDashoffset: offset }}
              className="transition-[stroke-dashoffset,stroke] duration-300"
            />
          </svg>
          <span className="absolute inset-0 grid place-items-center font-mono text-[7px] font-semibold tabular-nums text-muted">{Math.round(pct)}%</span>
        </span>
        <span className="whitespace-nowrap font-medium">上下文</span>
        <span className="whitespace-nowrap font-mono tabular-nums">{compactPrefix}{estimatePrefix}{fmtTokens(used)} / {fmtTokens(total)}</span>
        <ChevronDown className="size-3 shrink-0 transition-transform group-open:rotate-180" aria-hidden="true" />
      </summary>
      <div
        data-testid="context-usage-details"
        role="dialog"
        aria-label="上下文窗口详情"
        className="absolute bottom-full left-0 right-auto z-30 mb-1 w-64 max-w-[calc(100vw-2rem)] rounded-md border border-border bg-panel p-3 text-xs shadow-lg sm:left-auto sm:right-0"
      >
        <div className="flex items-center justify-between gap-3">
          <span className="font-medium text-text">上下文窗口</span>
          <span className="font-mono tabular-nums text-muted">{compactPrefix}{estimatePrefix}{fmtTokens(used)} / {fmtTokens(total)} ({Math.round(pct)}%)</span>
        </div>
        <div
          role="progressbar"
          aria-label={label}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(pct)}
          className="mt-2 h-1 overflow-hidden rounded-full bg-card"
        >
          <div className="h-full rounded-full transition-[width] duration-300" style={{ width: `${pct}%`, background: color }} />
        </div>
        <dl className="mt-3 space-y-1.5 text-[11px]">
          <div className="flex items-center justify-between gap-3"><dt className="text-muted">已使用</dt><dd className="font-mono tabular-nums text-text">{fmtTokens(used)}</dd></div>
          {typeof usage.input === "number" && usage.input > 0 && <div className="flex items-center justify-between gap-3"><dt className="text-muted">本轮输入</dt><dd className="font-mono tabular-nums text-text">{fmtTokens(usage.input)}</dd></div>}
          {typeof usage.output === "number" && usage.output > 0 && <div className="flex items-center justify-between gap-3"><dt className="text-muted">本轮输出</dt><dd className="font-mono tabular-nums text-text">{fmtTokens(usage.output)}</dd></div>}
          <div className="flex items-center justify-between gap-3"><dt className="text-muted">可用空间</dt><dd className="font-mono tabular-nums text-text">{fmtTokens(remaining)}</dd></div>
          <div className="flex items-center justify-between gap-3"><dt className="text-muted">窗口上限</dt><dd className="font-mono tabular-nums text-text">{fmtTokens(total)}</dd></div>
        </dl>
      </div>
    </details>
  );
}
