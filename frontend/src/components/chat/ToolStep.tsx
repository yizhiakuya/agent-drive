"use client";
import { useState } from "react";
import { fmtSize, fmtToolArgs, TOOL_ICONS, STEP_STATUS } from "@/lib/format";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

export interface ToolStepData {
  tool: string;
  arguments?: Record<string, unknown>;
  status: "running" | "done" | "error";
  output?: string;
  parsed?: Record<string, unknown> | unknown[];
}

export function ToolStep({ step }: { step: ToolStepData }) {
  const [open, setOpen] = useState(false);
  const [statusIcon, statusText] = STEP_STATUS[step.status] || ["•", ""];
  const icon = TOOL_ICONS[step.tool] || "🔧";
  const argsBrief = fmtToolArgs(step.tool, step.arguments || {});

  const parsed = step.parsed as Record<string, unknown> | undefined;
  const isListFiles = Array.isArray(step.parsed) && step.tool === "list_files";
  const isError = parsed && parsed.ok === false;

  const statusBadge =
    step.status === "error" ? (
      <Badge variant="destructive">{statusIcon} {statusText}</Badge>
    ) : step.status === "running" ? (
      <Badge variant="secondary" className="text-warn">{statusIcon} {statusText}</Badge>
    ) : (
      <Badge variant="secondary" className="text-success">{statusIcon} {statusText}</Badge>
    );

  return (
    <div className={`border rounded-lg mb-2 text-sm ${step.status === "error" ? "border-danger/40 bg-danger-soft/40" : "border-border bg-panel"}`}>
      <Button
        variant="ghost"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="w-full justify-start gap-2 rounded-lg px-3 py-2 h-auto font-medium hover:bg-card/60"
      >
        <span>{icon}</span>
        <span className="font-semibold">{step.tool}</span>
        <code className="text-muted text-xs truncate flex-1 text-left font-normal">{argsBrief}</code>
        {statusBadge}
        <span className="text-muted text-xs">{open ? "▲" : "▼"}</span>
      </Button>
      {open && step.output && (
        <div className="border-t border-border px-3 py-2 max-h-64 overflow-auto">
          {isError ? (
            <div className="text-danger">❌ {parsed?.error as string}</div>
          ) : isListFiles ? (
            <table className="w-full text-xs border-collapse">
              <thead><tr><th className="text-left p-1 border-b border-border">名称</th><th className="text-left p-1 border-b border-border">类型</th><th className="text-left p-1 border-b border-border">大小</th></tr></thead>
              <tbody>
                {(step.parsed as { name: string; is_dir: boolean; size: number }[]).map((f, i) => (
                  <tr key={i}>
                    <td className="p-1 border-b border-border/50">{f.is_dir ? "📂" : "📄"} {f.name}</td>
                    <td className="p-1 border-b border-border/50">{f.is_dir ? "文件夹" : "文件"}</td>
                    <td className="p-1 border-b border-border/50">{f.is_dir ? "—" : fmtSize(f.size)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <code className="text-xs whitespace-pre-wrap break-all">{step.output}</code>
          )}
        </div>
      )}
    </div>
  );
}
