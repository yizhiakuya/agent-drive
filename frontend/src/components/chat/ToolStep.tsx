"use client";
import { useEffect, useState } from "react";
import { BookOpen, ChevronRight, CircleAlert, CircleCheck, Code2, File, FolderOpen, LoaderCircle } from "lucide-react";
import { fileStatsSummary, fmtElapsedMs, fmtSize, fmtToolArgs, fmtToolProgress, fmtToolTitle, STEP_STATUS } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { failedToolResult, toolFailureDetail } from "./chat-stream-state";

export interface ToolStepData {
  tool: string;
  step?: number;
  arguments?: Record<string, unknown>;
  status: "running" | "done" | "error";
  startedAt?: number;
  progressMessage?: string;
  progressPhase?: string;
  elapsedMs?: number;
  output?: string;
  parsed?: Record<string, unknown> | unknown[];
}

export function ToolStep({ step }: { step: ToolStepData }) {
  const [open, setOpen] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const argsBrief = fmtToolArgs(step.tool, step.arguments || {});

  useEffect(() => {
    if (step.status !== "running") return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [step.status]);

  const parsed = step.parsed as Record<string, unknown> | undefined;
  const hasResult = Boolean(step.output) || step.parsed !== undefined;
  const isListFiles = Array.isArray(step.parsed) && step.tool === "list_files";
  const isError = failedToolResult(step.parsed) !== null;
  const effectiveStatus = isError ? "error" : step.status;
  const [, statusText] = STEP_STATUS[effectiveStatus] || ["•", ""];
  const isSkill = step.tool === "read_skill";
  const skillName = step.arguments?.action === "read" && typeof step.arguments.name === "string"
    ? step.arguments.name
    : null;
  const toolLabel = isSkill ? (skillName ? `Skill · ${skillName}` : "Skill 目录") : step.tool;
  const activityLabel = fmtToolTitle(step.tool, step.arguments || {});
  const progressMessage = step.progressMessage || fmtToolProgress(step.tool, step.arguments || {});
  const elapsedMs = step.elapsedMs ?? (step.startedAt ? Math.max(0, now - step.startedAt) : null);
  const elapsedLabel = elapsedMs === null ? null : `耗时 ${fmtElapsedMs(elapsedMs)}`;
  const statsSummary = fileStatsSummary(step.tool, step.arguments || {}, step.parsed);

  const statusBadge =
    effectiveStatus === "error" ? (
      <span className="flex items-center gap-1 text-[10px] font-mono text-danger"><CircleAlert className="size-3.5" aria-hidden="true" />{statusText}</span>
    ) : effectiveStatus === "running" ? (
      <span className="flex items-center gap-1 text-[10px] font-mono text-warn"><LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />{statusText}</span>
    ) : (
      <span className="flex items-center gap-1 text-[10px] font-mono text-success"><CircleCheck className="size-3.5" aria-hidden="true" />{statusText}</span>
    );

  return (
    <div className={`mb-2 overflow-hidden rounded-lg border text-sm ${effectiveStatus === "error" ? "border-danger/40 bg-danger-soft/40" : "border-border bg-panel"}`}>
      <Button
        variant="ghost"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="h-auto w-full justify-start gap-2 rounded-none border-0 bg-card/50 px-3 py-2.5 font-medium hover:bg-card"
      >
        {isSkill
          ? <BookOpen className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
          : <Code2 className="size-3.5 shrink-0 text-muted" aria-hidden="true" />}
        <span className="min-w-0 truncate text-xs font-semibold text-text">{activityLabel}</span>
        {activityLabel !== toolLabel && <span className="font-mono text-[10px] text-muted">{toolLabel}</span>}
        <code className="min-w-0 flex-1 truncate text-left text-[11px] font-normal text-muted">{argsBrief}</code>
        {statusBadge}
        {elapsedLabel && effectiveStatus !== "running" && <span className="shrink-0 font-mono text-[10px] text-muted" aria-label={`工具耗时 ${elapsedLabel}`}>{elapsedLabel}</span>}
        <ChevronRight className={`size-3.5 shrink-0 text-muted transition-transform ${open ? "rotate-90" : ""}`} aria-hidden="true" />
      </Button>
      {effectiveStatus === "running" && (
        <div className="border-t border-border bg-card/40 px-3 py-2.5" aria-live="polite">
          <div className="flex items-center gap-2 text-xs text-warn">
            <LoaderCircle className="size-3.5 shrink-0 animate-spin" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">{progressMessage}</span>
            {elapsedMs !== null && <span className="shrink-0 font-mono text-[11px] text-muted">{fmtElapsedMs(elapsedMs)}</span>}
          </div>
          <div className="mt-2 h-1 overflow-hidden rounded-full bg-muted/60" role="progressbar" aria-label={`${progressMessage}，进行中`}>
            <div className="h-full w-1/3 animate-[progress-slide_1.4s_ease-in-out_infinite] rounded-full bg-warn" />
          </div>
        </div>
      )}
      {open && hasResult && (
        <div className="max-h-64 overflow-auto border-t border-border bg-text px-3 py-3 text-panel">
          {isError ? (
            <div className="flex items-center gap-1.5 text-danger-soft"><CircleAlert className="size-3.5" /> {toolFailureDetail(step.parsed)}</div>
          ) : isListFiles ? (
            <table className="w-full border-collapse text-xs">
              <thead><tr><th className="border-b border-panel/20 p-1 text-left font-normal text-panel/60">名称</th><th className="border-b border-panel/20 p-1 text-left font-normal text-panel/60">类型</th><th className="border-b border-panel/20 p-1 text-left font-normal text-panel/60">大小</th></tr></thead>
              <tbody>
                {(step.parsed as { name: string; is_dir: boolean; size: number }[]).map((f, i) => (
                  <tr key={i}>
                    <td className="border-b border-panel/10 p-1 font-mono text-panel"><span className="mr-1 inline-flex align-middle">{f.is_dir ? <FolderOpen className="size-3.5" /> : <File className="size-3.5" />}</span>{f.name}</td>
                    <td className="border-b border-panel/10 p-1 text-panel/75">{f.is_dir ? "文件夹" : "文件"}</td>
                    <td className="border-b border-panel/10 p-1 text-panel/75">{f.is_dir ? "—" : fmtSize(f.size)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : statsSummary ? (
            <div className="space-y-2">
              <div className="text-sm font-semibold text-success-soft">{statsSummary}</div>
              <code className="whitespace-pre-wrap break-all font-mono text-[11px] text-panel/70">{JSON.stringify(parsed, null, 2)}</code>
            </div>
          ) : parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (
            // 对象型结果渲染完整结构化 JSON（output 字段按 500B 截断存储，
            // 直接显示会从中间截断——get_system_status 等长输出此前就是半截原文）
            <code className="whitespace-pre-wrap break-all font-mono text-[11px] text-success-soft">{JSON.stringify(parsed, null, 2)}</code>
          ) : (
            <code className="whitespace-pre-wrap break-all font-mono text-[11px] text-panel/85">{step.output}</code>
          )}
        </div>
      )}
      {open && !hasResult && effectiveStatus !== "running" && (
        <div className="border-t border-border bg-card/40 px-3 py-3 text-xs text-muted">
          此步骤没有可展示的返回内容
        </div>
      )}
    </div>
  );
}
