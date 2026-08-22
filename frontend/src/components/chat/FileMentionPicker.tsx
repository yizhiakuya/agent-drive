"use client";

import { AtSign, ChevronRight, CornerUpLeft, FileText, FolderOpen, FolderPlus, Loader2 } from "lucide-react";
import type { FileItem } from "@/lib/api/files";
import { Button } from "@/components/ui/button";

interface FileMentionPickerProps {
  open: boolean;
  browsePath: string | null;
  historyDepth: number;
  loading: boolean;
  items: FileItem[];
  onBack: () => void;
  onChoose: (item: FileItem) => void;
  onEnterFolder: (item: FileItem) => void;
  onChooseCurrentFolder: () => void;
}

/** 展示在聊天输入框上方的文件/文件夹候选列表。 */
export default function FileMentionPicker({
  open,
  browsePath,
  historyDepth,
  loading,
  items,
  onBack,
  onChoose,
  onEnterFolder,
  onChooseCurrentFolder,
}: FileMentionPickerProps) {
  if (!open) return null;

  return (
    <div role="listbox" aria-label="文件引用候选" className="absolute bottom-full left-0 z-20 mb-2 max-h-64 w-full overflow-auto rounded-md border border-border bg-panel p-1 shadow-lg">
      {browsePath !== null ? (
        <div className="flex items-center gap-1 border-b border-border px-1 pb-1.5">
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            className="size-7 shrink-0 text-muted"
            aria-label={historyDepth > 0 ? "返回上一级" : "返回文件引用搜索"}
            title={historyDepth > 0 ? "返回上一级" : "返回搜索结果"}
            onMouseDown={(event) => event.preventDefault()}
            onClick={onBack}
          >
            <CornerUpLeft className="size-3.5" aria-hidden="true" />
          </Button>
          <div className="min-w-0 flex-1 px-1">
            <div className="text-[10px] font-semibold text-muted">选择文件</div>
            <div className="truncate text-xs text-text" title={browsePath}>{browsePath}</div>
          </div>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-7 shrink-0 gap-1 px-2 text-[11px] text-muted hover:text-text"
            aria-label={`引用文件夹 ${browsePath}`}
            title="引用整个文件夹"
            onMouseDown={(event) => event.preventDefault()}
            onClick={onChooseCurrentFolder}
          >
            <FolderPlus className="size-3.5" aria-hidden="true" />
            <span>引用文件夹</span>
          </Button>
        </div>
      ) : (
        <div className="flex items-center gap-2 border-b border-border px-2 pb-1.5 text-[10px] text-muted">
          <AtSign className="size-3.5 shrink-0" aria-hidden="true" />
          <span>选择文件，或进入文件夹继续浏览</span>
        </div>
      )}
      {loading && <div className="flex items-center gap-2 px-3 py-2 text-xs text-muted"><Loader2 className="size-3.5 animate-spin" /> 搜索文件…</div>}
      {!loading && items.length === 0 && <div className="px-3 py-2 text-xs text-muted">{browsePath !== null ? "文件夹为空" : "没有匹配的文件或文件夹"}</div>}
      {!loading && items.map((item) => item.is_dir ? (
        <div key={item.path} role="option" aria-selected="false" aria-label={`文件夹 ${item.path}`} className="flex items-center gap-1 rounded-sm px-1 hover:bg-card">
          <button
            type="button"
            className="flex min-w-0 flex-1 items-center gap-2 px-2 py-2 text-left text-xs"
            aria-label={`进入文件夹 ${item.path}`}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onEnterFolder(item)}
          >
            <FolderOpen className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">{item.path}</span>
            <span className="shrink-0 text-[10px] text-muted">文件夹</span>
            <ChevronRight className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
          </button>
          <button
            type="button"
            className="shrink-0 rounded-sm p-1.5 text-muted hover:bg-panel hover:text-text"
            aria-label={`引用文件夹 ${item.path}`}
            title="引用整个文件夹"
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onChoose(item)}
          >
            <FolderPlus className="size-3.5" aria-hidden="true" />
          </button>
        </div>
      ) : (
        <button key={item.path} type="button" role="option" aria-selected="false" className="flex w-full items-center gap-2 rounded-sm px-3 py-2 text-left text-xs hover:bg-card" onMouseDown={(event) => event.preventDefault()} onClick={() => onChoose(item)}>
          <FileText className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
          <span className="min-w-0 flex-1 truncate">{item.path}</span>
          <span className="shrink-0 text-[10px] text-muted">文件</span>
        </button>
      ))}
    </div>
  );
}
