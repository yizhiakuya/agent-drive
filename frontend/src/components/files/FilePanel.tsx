"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { listFiles, uploadFile, getFileInfo, FileInfo, fileDownloadUrl } from "@/lib/api/files";
import FilePreview from "./FilePreview";
import { indexStatusLabel } from "./FileDetails";
import { fmtSize } from "@/lib/format";
import { EV, emitToast, emitTasksChanged } from "@/lib/events";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ArrowLeft, ChevronRight, Download, File, FolderOpen, Home, PanelLeftClose, PanelLeftOpen, RefreshCw, Upload, X } from "lucide-react";

export default function FilePanel() {
  const [path, setPath] = useState("");
  const [items, setItems] = useState<{ name: string; path: string; is_dir: boolean; size: number }[]>([]);
  const [disk, setDisk] = useState<{ used: number; total: number; free: number } | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [selected, setSelected] = useState<{ path: string; info: FileInfo | null; text: string } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const pathRef = useRef("");

  const load = useCallback(async (p: string) => {
    try {
      const r = await listFiles(p);
      setItems(r.items);
      setDisk(r.disk);
      setPath(r.path);
      pathRef.current = r.path;
    } catch (e) {
      emitToast({ kind: "error", text: `文件列表加载失败：${String(e)}` });
    }
  }, []);

  useEffect(() => { load(""); }, [load]);

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

  async function openItem(it: { name: string; path: string; is_dir: boolean; size: number }) {
    if (it.is_dir) {
      load(it.path);
      setSelected(null);
      return;
    }
    setSelected({ path: it.path, info: null, text: "" });
    try {
      const data = await getFileInfo(it.path);
      setSelected((s) => ({ path: s?.path || it.path, info: data, text: data.preview_kind === "text" ? (data.snippet || "") : "" }));
    } catch { /* 忽略 */ }
  }

  const crumbs = path ? path.split("/").filter(Boolean) : [];
  const isMarkdown = selected?.info?.path?.toLowerCase().endsWith(".md");

  return (
    <aside className={`bg-panel flex flex-col border-l border-border ${collapsed ? "w-11 min-w-11" : "w-80"}`}>
      <div className="flex items-center justify-between border-b border-border px-3 py-3">
        <b className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.1em]"><FolderOpen className="size-3.5 text-muted" /> 文件</b>
        <span className="flex gap-1.5 items-center">
          <Button variant="default" size="sm" onClick={() => fileRef.current?.click()}><Upload className="size-3.5" /> 上传</Button>
          <Button variant="ghost" size="sm" onClick={() => load(path)} title="刷新" aria-label="刷新"><RefreshCw className="size-3.5" /></Button>
          <Button variant="ghost" size="sm" onClick={() => setCollapsed((v) => !v)}
                  title={collapsed ? "展开" : "收起"} aria-label={collapsed ? "展开" : "收起"}>{collapsed ? <PanelLeftOpen className="size-3.5" /> : <PanelLeftClose className="size-3.5" />}</Button>
        </span>
        <input ref={fileRef} type="file" style={{ display: "none" }} onChange={onUpload} />
      </div>
      {!collapsed && (
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
