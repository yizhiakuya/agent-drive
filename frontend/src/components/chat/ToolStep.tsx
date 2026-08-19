"use client";
import { useState } from "react";
import { ChevronRight, CircleAlert, CircleCheck, Code2, File, FolderOpen, LoaderCircle } from "lucide-react";
import { fmtSize, fmtToolArgs, STEP_STATUS } from "@/lib/format";
import { Button } from "@/components/ui/button";

export interface ToolStepData {
  tool: string;
  arguments?: Record<string, unknown>;
  status: "running" | "done" | "error";
  output?: string;
  parsed?: Record<string, unknown> | unknown[];
}

export function ToolStep({ step }: { step: ToolStepData }) {
  const [open, setOpen] = useState(false);
  const [, statusText] = STEP_STATUS[step.status] || ["•", ""];
  const argsBrief = fmtToolArgs(step.tool, step.arguments || {});

  const parsed = step.parsed as Record<string, unknown> | undefined;
  const isListFiles = Array.isArray(step.parsed) && step.tool === "list_files";
  const isError = parsed && parsed.ok === false;

  const statusBadge =
    step.status === "error" ? (
      <span className="flex items-center gap-1 text-[10px] font-mono text-danger"><CircleAlert className="size-3.5" aria-hidden="true" />{statusText}</span>
    ) : step.status === "running" ? (
      <span className="flex items-center gap-1 text-[10px] font-mono text-warn"><LoaderCircle className="size-3.5 animate-spin" aria-hidden="true" />{statusText}</span>
    ) : (
      <span className="flex items-center gap-1 text-[10px] font-mono text-success"><CircleCheck className="size-3.5" aria-hidden="true" />{statusText}</span>
    );

  return (
    <div className={`mb-2 overflow-hidden rounded-lg border text-sm ${step.status === "error" ? "border-danger/40 bg-danger-soft/40" : "border-border bg-panel"}`}>
      <Button
        variant="ghost"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="h-auto w-full justify-start gap-2 rounded-none border-0 bg-card/50 px-3 py-2.5 font-medium hover:bg-card"
      >
        <Code2 className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
        <span className="font-mono text-xs font-semibold text-text">{step.tool}</span>
        <code className="min-w-0 flex-1 truncate text-left text-[11px] font-normal text-muted">{argsBrief}</code>
        {statusBadge}
        <ChevronRight className={`size-3.5 shrink-0 text-muted transition-transform ${open ? "rotate-90" : ""}`} aria-hidden="true" />
      </Button>
      {open && step.output && (
        <div className="max-h-64 overflow-auto border-t border-border bg-text px-3 py-3 text-panel">
          {isError ? (
            <div className="flex items-center gap-1.5 text-danger-soft"><CircleAlert className="size-3.5" /> {parsed?.error as string}</div>
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
          ) : parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (
            // 对象型结果渲染完整结构化 JSON（output 字段按 500B 截断存储，
            // 直接显示会从中间截断——get_system_status 等长输出此前就是半截原文）
            <code className="whitespace-pre-wrap break-all font-mono text-[11px] text-success-soft">{JSON.stringify(parsed, null, 2)}</code>
          ) : (
            <code className="whitespace-pre-wrap break-all font-mono text-[11px] text-panel/85">{step.output}</code>
          )}
        </div>
      )}
    </div>
  );
}
