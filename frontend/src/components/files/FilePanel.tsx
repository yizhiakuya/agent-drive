"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { listFiles, uploadFile, getFileInfo, type FileInfo, type FileItem, fileDownloadUrl } from "@/lib/api/files";
import FilePreview from "./FilePreview";
import { indexStatusLabel } from "./FileDetails";
import { fmtSize } from "@/lib/format";
import { EV, emitToast, emitTasksChanged } from "@/lib/events";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import PanelResizeHandle from "@/components/workspace/PanelResizeHandle";
import { WORKSPACE_PANEL_LIMITS } from "@/lib/workspace-layout";
import { ArrowLeft, ChevronRight, Download, File, FolderOpen, Home, PanelRightClose, PanelRightOpen, RefreshCw, Upload, X } from "lucide-react";

interface FilePanelProps {
  collapsed?: boolean;
  width?: number;
  onResize?: (width: number) => void;
  onToggle?: () => void;
}

export default function FilePanel({ collapsed, width = WORKSPACE_PANEL_LIMITS.files.defaultWidth, onResize, onToggle }: FilePanelProps) {
  const [path, setPath] = useState("");
  const [items, setItems] = useState<FileItem[]>([]);
  const [disk, setDisk] = useState<{ used: number; total: number; free: number } | null>(null);
  const [localCollapsed, setLocalCollapsed] = useState(false);
  const [selected, setSelected] = useState<{ path: string; info: FileInfo | null; text: string } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const pathRef = useRef("");
  const listRequestRef = useRef(0);
  const selectionRequestRef = useRef(0);
  const isCollapsed = collapsed ?? localCollapsed;
  const toggle = onToggle ?? (() => setLocalCollapsed((value) => !value));

  /**
   * 刷新当前目录。目录切换和全局文件变更可能并发发生，只有最后一次列表请求可以提交 items/path/disk。
   */
  const load = useCallback(async (p: string) => {
    const request = ++listRequestRef.current;
    try {
      const r = await listFiles(p);
      if (request !== listRequestRef.current) return;
      setItems(r.items);
      setDisk(r.disk);
      setPath(r.path);
      pathRef.current = r.path;
    } catch (e) {
      if (request === listRequestRef.current) {
        emitToast({ kind: "error", text: `文件列表加载失败：${String(e)}` });
      }
    }
  }, []);

  useEffect(() => { load(""); }, [load]);

  useEffect(() => () => {
    listRequestRef.current += 1;
    selectionRequestRef.current += 1;
  }, []);

  useEffect(() => {
    function onFilesChanged() { load(pathRef.current); }
    window.addEventListener(EV.filesChanged, onFilesChanged);
    return () => window.removeEventListener(EV.filesChanged, onFilesChanged);
  }, [load]);

  async function onUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) {
      try {
        const result = await uploadFile(file, path);
        if (result.indexed?.task_id) emitTasksChanged();
        emitToast({ kind: "ok", text: `已上传 ${file.name}，内容将在后台处理` });
        load(path);
      } catch (err) {
        emitToast({ kind: "error", text: `上传失败: ${err}` });
      }
      e.target.value = "";
    }
  }

  /**
   * 选择文件先立即显示占位，再异步读取详情；选择目录则切换列表并清空预览。
   * selectionRequestRef 防止用户快速连续点击时，旧文件详情覆盖新选择。
   */
  async function openItem(it: FileItem) {
    const request = ++selectionRequestRef.current;
    if (it.is_dir) {
      setSelected(null);
      void load(it.path);
      return;
    }
    setSelected({ path: it.path, info: null, text: "" });
    try {
      const data = await getFileInfo(it.path);
      if (request !== selectionRequestRef.current) return;
      setSelected((s) => s?.path === it.path
        ? { path: it.path, info: data, text: data.preview_kind === "text" ? (data.snippet || "") : "" }
        : s);
    } catch { /* 忽略 */ }
  }

  const crumbs = path ? path.split("/").filter(Boolean) : [];
  const isMarkdown = selected?.info?.path?.toLowerCase().endsWith(".md");

  const panelWidth = isCollapsed ? WORKSPACE_PANEL_LIMITS.files.collapsedWidth : width;

  return (
    <aside
      data-testid="file-panel"
      aria-label="文件栏"
      style={{ width: panelWidth, minWidth: panelWidth }}
      className="relative flex h-full shrink-0 flex-col border-l border-border bg-panel"
    >
      <PanelResizeHandle
        panel="files"
        width={width}
        minWidth={WORKSPACE_PANEL_LIMITS.files.min}
        maxWidth={WORKSPACE_PANEL_LIMITS.files.max}
        collapsed={isCollapsed}
        onResize={onResize ?? (() => {})}
        onToggle={toggle}
      />
      {isCollapsed ? (
        <div data-testid="file-panel-collapsed" className="flex flex-1 flex-col items-center gap-2 bg-panel py-3">
          <Button variant="ghost" size="icon-sm" onClick={toggle} title="展开文件栏" aria-label="展开文件栏">
            <PanelRightOpen className="size-4" aria-hidden="true" />
          </Button>
          <FolderOpen className="mt-1 size-4 text-muted" aria-hidden="true" />
        </div>
      ) : (
        <>
          <div className="flex items-center justify-between gap-2 border-b border-border px-3 py-3 pl-4">
            <b className="flex min-w-0 items-center gap-2 text-xs font-semibold uppercase tracking-[0.1em]">
              <FolderOpen className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
              <span className="truncate">文件</span>
            </b>
            <span className="flex shrink-0 items-center gap-1">
              <Button variant="default" size="sm" onClick={() => fileRef.current?.click()}>
                <Upload className="size-3.5" /> 上传
              </Button>
              <Button variant="ghost" size="icon-sm" onClick={() => load(path)} title="刷新文件列表" aria-label="刷新文件列表">
                <RefreshCw className="size-3.5" />
              </Button>
              <Button variant="ghost" size="icon-sm" onClick={toggle} title="收起文件栏" aria-label="收起文件栏">
                <PanelRightClose className="size-3.5" aria-hidden="true" />
              </Button>
            </span>
          </div>
          <input ref={fileRef} type="file" style={{ display: "none" }} onChange={onUpload} />
        </>
      )}
      {!isCollapsed && (
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="flex items-center px-3 py-1.5 text-xs flex-wrap gap-0.5 border-b border-border/60">
            <Button variant="ghost" size="sm" className={`px-1.5 ${!path ? "font-semibold text-text" : "text-muted"}`} onClick={() => load("")}><Home className="size-3.5" /></Button>
            {crumbs.map((c, i) => (
              <span key={i}>
                <ChevronRight className="mx-0.5 inline size-3 text-muted" />
                <Button variant="ghost" size="sm" className={`px-1.5 ${i === crumbs.length - 1 ? "font-semibold text-text" : "text-muted"}`}
                        onClick={() => load(crumbs.slice(0, i + 1).join("/"))}>{c}</Button>
              </span>
            ))}
            {path && <Button variant="ghost" size="sm" className="ml-1 font-bold px-1" title="返回上级"
                            onClick={() => load(crumbs.slice(0, -1).join("/"))}><ArrowLeft className="size-3.5" /></Button>}
          </div>
          <div className="flex-1 overflow-y-auto p-2">
            {items.length === 0 && (
              <div className="py-6 text-center text-muted text-xs">
                <FolderOpen className="mx-auto mb-2 size-6" />目录为空
              </div>
            )}
            {items.map((it) => (
              <div key={it.path}
                   className={`flex cursor-pointer items-center gap-2 border-b border-border/50 px-2.5 py-2 text-sm transition-colors hover:bg-card ${selected?.path === it.path ? "bg-accent-soft" : ""}`}
                   onClick={() => openItem(it)}
                   onDoubleClick={() => it.is_dir && load(it.path)}>
                <span className={it.is_dir ? "text-text" : "text-muted"}>{it.is_dir ? <FolderOpen className="size-4" /> : <File className="size-4" />}</span>
                <span className="flex-1 truncate" title={it.path}>{it.name}</span>
                <span className="text-muted text-xs">{it.is_dir ? "" : fmtSize(it.size)}</span>
              </div>
            ))}
          </div>
          {selected && (
            <div className="border-t border-border flex flex-col max-h-[38%]">
              <div className="flex justify-between items-center px-2.5 py-1.5 border-b border-border/60">
                <b className="text-xs truncate flex-1" title={selected.path}>{selected.path.split("/").pop()}</b>
                <span className="flex gap-1">
                  <Button variant="default" size="sm" asChild><a href={fileDownloadUrl(selected.path)} download aria-label="下载"><Download className="size-3.5" /></a></Button>
                  <Button variant="ghost" size="sm" onClick={() => setSelected(null)} aria-label="关闭预览"><X className="size-3.5" /></Button>
                </span>
              </div>
              {selected.info?.indexed && (
                <div className="px-2.5 py-0.5"><Badge variant={selected.info.indexed.vectorized ? "default" : "outline"}>{indexStatusLabel(selected.info.indexed)}</Badge></div>
              )}
              <div className="flex-1 overflow-auto">
                {selected.info && (
                  <FilePreview
                    info={selected.info}
                    path={selected.path}
                    text={selected.text}
                    isMarkdown={isMarkdown}
                    variant="panel"
                  />
                )}
              </div>
            </div>
          )}
          {disk && (
            <div className="px-4 py-2.5 border-t border-border text-muted text-xs">
              已用 {fmtSize(disk.used)} / {fmtSize(disk.total)} · 剩余 {fmtSize(disk.free)}
            </div>
          )}
        </div>
      )}
    </aside>
  );
}
