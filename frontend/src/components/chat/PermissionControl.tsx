"use client";
import { useEffect, useRef } from "react";
import { Check, ChevronDown, Hand, ShieldCheck, Zap } from "lucide-react";
import type { PermissionMode } from "@/lib/permission";

interface PermissionControlProps {
  value: PermissionMode;
  onChange: (value: PermissionMode) => void;
  disabled?: boolean;
}

const MODES: {
  value: PermissionMode;
  label: string;
  compactLabel: string;
  description: string;
  icon: typeof Hand;
}[] = [
  {
    value: "ask",
    label: "请求批准",
    compactLabel: "请求",
    description: "修改文件、配置或调用外部服务前先询问",
    icon: Hand,
  },
  {
    value: "auto",
    label: "帮我批准",
    compactLabel: "帮我批准",
    description: "仅对检测到的风险操作请求批准",
    icon: ShieldCheck,
  },
  {
    value: "full",
    label: "完全访问",
    compactLabel: "完全访问",
    description: "自动执行所有已授权操作，包括删除和覆盖",
    icon: Zap,
  },
];

export function PermissionControl({ value, onChange, disabled = false }: PermissionControlProps) {
  const detailsRef = useRef<HTMLDetailsElement>(null);
  const selected = MODES.find((mode) => mode.value === value) || MODES[1];
  const SelectedIcon = selected.icon;

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
    <details ref={detailsRef} data-testid="permission-control" className="group relative shrink-0">
      <summary
        data-testid="permission-summary"
        aria-label={`权限模式：${selected.label}`}
        title="修改权限模式"
        className={`flex h-6 list-none items-center gap-1.5 rounded-md border border-transparent px-1.5 text-xs text-muted transition-colors hover:bg-panel hover:text-text [&::-webkit-details-marker]:hidden ${disabled ? "pointer-events-none opacity-50" : "cursor-pointer"}`}
      >
        <SelectedIcon className="size-3.5 shrink-0" aria-hidden="true" />
        <span className="hidden text-[10px] font-semibold uppercase tracking-[0.12em] sm:inline">权限</span>
        <span className="whitespace-nowrap text-[11px]">{selected.compactLabel}</span>
        <ChevronDown className="size-3 shrink-0 transition-transform group-open:rotate-180" aria-hidden="true" />
      </summary>
      <div
        data-testid="permission-menu"
        role="dialog"
        aria-label="权限模式"
        className="absolute bottom-full left-0 z-30 mb-1 w-[min(19rem,calc(100vw-2rem))] rounded-lg border border-border bg-panel p-2 shadow-lg sm:left-auto sm:right-0"
      >
        <div className="px-2 pb-1.5 pt-1 text-sm font-medium text-text">应如何批准 Agent 操作？</div>
        <div className="space-y-0.5">
          {MODES.map((mode) => {
            const Icon = mode.icon;
            const active = value === mode.value;
            return (
              <button
                key={mode.value}
                type="button"
                role="menuitemradio"
                aria-checked={active}
                aria-label={mode.label}
                className={`flex w-full items-start gap-2.5 rounded-md px-2 py-2 text-left transition-colors ${active ? "bg-card text-text" : "text-muted hover:bg-card/70 hover:text-text"}`}
                onClick={() => {
                  onChange(mode.value);
                  if (detailsRef.current) detailsRef.current.open = false;
                }}
              >
                <Icon className={`mt-0.5 size-4 shrink-0 ${active ? "text-accent" : "text-muted"}`} aria-hidden="true" />
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-1.5 text-sm font-medium">
                    {mode.label}
                    {active && <Check className="size-3.5 text-accent" aria-hidden="true" />}
                  </span>
                  <span className="mt-0.5 block text-[11px] leading-snug text-muted">{mode.description}</span>
                </span>
              </button>
            );
          })}
        </div>
        <div className="mt-1.5 border-t border-border px-2 pt-2 text-[10px] leading-snug text-muted">
          完全访问会直接执行删除、覆盖等破坏性操作，请确认权限模式后再发送消息。
        </div>
      </div>
    </details>
  );
}
