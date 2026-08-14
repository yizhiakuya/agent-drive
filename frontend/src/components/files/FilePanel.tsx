"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { listFiles, uploadFile, getFileInfo, FileInfo, fileRawUrl, fileDownloadUrl } from "@/lib/api/files";
import { fmtSize } from "@/lib/format";
import { EV, emitToast, emitTasksChanged } from "@/lib/events";

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
      console.error(e);
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
      <div className="flex justify-between items-center px-4 py-3.5 border-b border-border">
        <b className="text-sm">📁 文件</b>
        <span className="flex gap-1.5 items-center">
          <button className="bg-accent text-white text-xs px-3 py-1.5 rounded-lg cursor-pointer" onClick={() => fileRef.current?.click()}>⬆ 上传</button>
          <button className="bg-accent text-white text-xs px-2 py-1.5 rounded-lg cursor-pointer" onClick={() => load(path)} title="刷新">🔄</button>
          <button className="bg-accent text-white text-xs px-2 py-1.5 rounded-lg cursor-pointer" onClick={() => setCollapsed((v) => !v)}
                  title={collapsed ? "展开" : "收起"}>{collapsed ? "▶" : "▼"}</button>
        </span>
        <input ref={fileRef} type="file" style={{ display: "none" }} onChange={onUpload} />
      </div>
      {!collapsed && (
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="flex items-center px-3 py-1.5 text-xs flex-wrap gap-0.5 border-b border-border/60">
            <button className={`px-1 rounded cursor-pointer hover:bg-card ${!path ? "font-bold" : "text-accent"}`} onClick={() => load("")}>🏠</button>
            {crumbs.map((c, i) => (
              <span key={i}>
                <span className="text-muted mx-0.5">/</span>
                <button className={`px-1 rounded cursor-pointer hover:bg-card ${i === crumbs.length - 1 ? "font-bold" : "text-accent"}`}
                        onClick={() => load(crumbs.slice(0, i + 1).join("/"))}>{c}</button>
              </span>
            ))}
            {path && <button className="ml-1 font-bold cursor-pointer hover:bg-card px-1 rounded" title="返回上级"
                            onClick={() => load(crumbs.slice(0, -1).join("/"))}>⬆</button>}
          </div>
          <div className="flex-1 overflow-y-auto p-2">
            {items.length === 0 && (
              <div className="py-6 text-center text-muted text-xs">
                <span className="text-3xl block mb-2">📂</span>目录为空
              </div>
            )}
            {items.map((it) => (
              <div key={it.path}
                   className={`flex items-center gap-2 px-2.5 py-1.5 rounded-lg cursor-pointer text-sm hover:bg-card ${selected?.path === it.path ? "bg-accent-soft" : ""}`}
                   onClick={() => openItem(it)}
                   onDoubleClick={() => it.is_dir && load(it.path)}>
                <span className={it.is_dir ? "text-warn" : ""}>{it.is_dir ? "📂" : "📄"}</span>
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
                  <a className="bg-accent text-white text-xs px-2 py-1 rounded cursor-pointer" href={fileDownloadUrl(selected.path)} download>⬇</a>
                  <button className="bg-border text-text text-xs px-2 py-1 rounded cursor-pointer" onClick={() => setSelected(null)}>✕</button>
                </span>
              </div>
              {selected.info?.indexed && (
                <div className="px-2.5 py-0.5 text-muted text-xs">已索引({selected.info.indexed.method}, {selected.info.indexed.chars}字)</div>
              )}
              <div className="flex-1 overflow-auto">
                {selected.info?.preview_kind === "image" && <img src={fileRawUrl(selected.path)} alt="" className="max-w-full mx-auto" />}
                {selected.info?.preview_kind === "video" && <video src={fileRawUrl(selected.path)} controls className="w-full max-h-56" />}
                {selected.info?.preview_kind === "audio" && (
                  <div className="p-3"><audio src={fileRawUrl(selected.path)} controls className="w-full" /></div>
                )}
                {selected.info?.preview_kind === "pdf" && <iframe src={fileRawUrl(selected.path)} title="pdf" className="w-full h-56" />}
                {selected.info?.preview_kind === "text" && isMarkdown ? (
                  <div className="markdown-body px-3 py-2 text-xs"><ReactMarkdown remarkPlugins={[remarkGfm]}>{selected.text}</ReactMarkdown></div>
                ) : (
                  <pre className="px-3 py-2 text-xs whitespace-pre-wrap break-all">{selected.text}</pre>
                )}
                {selected.info?.preview_kind === "binary" && <div className="p-3 text-muted text-xs">二进制文件，下载查看</div>}
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
