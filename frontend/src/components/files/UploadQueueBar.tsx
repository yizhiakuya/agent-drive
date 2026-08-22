"use client";

import { RefreshCw, X } from "lucide-react";
import type { UploadEntry } from "./useUploadQueue";

interface UploadQueueBarProps {
  entries: UploadEntry[];
  uploading: boolean;
  onCancel: (id: string) => void;
  onRetry: (id: string) => void;
}

export default function UploadQueueBar({ entries, uploading, onCancel, onRetry }: UploadQueueBarProps) {
  if (entries.length === 0) return null;
  const completed = entries.filter((entry) => entry.status === "succeeded" || entry.status === "cancelled").length;

  return (
    <div className="flex max-h-20 shrink-0 items-center gap-2 overflow-x-auto border-b border-border text-[11px] text-muted" aria-label="上传队列">
      <span className="shrink-0 px-1">完成 {completed}/{entries.length}</span>
      {entries.map((entry) => (
        <div key={entry.id} className="flex shrink-0 items-center gap-1 rounded border border-border bg-card/50 px-2 py-1">
          <span className="max-w-28 truncate" title={entry.name}>{entry.name}</span>
          <span className={entry.status === "failed" ? "text-danger" : entry.status === "succeeded" ? "text-success" : "text-muted"}>
            {entry.status === "queued" ? "排队" : entry.status === "uploading" ? `上传中 ${entry.progress}%` : entry.status === "succeeded" ? "完成" : entry.status === "cancelled" ? "已取消" : "失败"}
          </span>
          {(entry.status === "queued" || entry.status === "uploading") && (
            <button type="button" className="text-muted hover:text-danger" aria-label={`取消上传 ${entry.name}`} onClick={() => onCancel(entry.id)}>
              <X className="size-3" />
            </button>
          )}
          {entry.status === "failed" && (
            <button type="button" className="text-muted hover:text-text" aria-label={`重试上传 ${entry.name}`} onClick={() => onRetry(entry.id)} disabled={uploading}>
              <RefreshCw className="size-3" />
            </button>
          )}
        </div>
      ))}
    </div>
  );
}
