"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { listFiles, uploadFile, getFileInfo, FileInfo, fileRawUrl, fileDownloadUrl } from "@/lib/api/files";
import { fmtSize } from "@/lib/format";
import { EV, emitToast } from "@/lib/events";

export default function FilePage() {
  const [path, setPath] = useState("");
  const [items, setItems] = useState<{ name: string; path: string; is_dir: boolean; size: number; mtime?: number }[]>([]);
  const [disk, setDisk] = useState<{ used: number; total: number; free: number } | null>(null);
  const [selected, setSelected] = useState<{ path: string; info: FileInfo | null; text: string } | null>(null);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
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

  async function doUpload(file: File) {
    setUploading(true);
    try {
      await uploadFile(file, path);
      emitToast({ kind: "ok", text: `已上传 ${file.name}` });
      load(path);
    } catch (e) {
      emitToast({ kind: "error", text: `上传失败: ${e}` });
    } finally {
      setUploading(false);
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
    <section
      className={`flex-1 flex overflow-hidden ${dragOver ? "outline-dashed outline-2 outline-accent bg-accent-soft/50" : ""}`}
      onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragOver(false);
        const file = e.dataTransfer?.files?.[0];
        if (file) doUpload(file);
      }}
    >
      <div className="flex-1 flex flex-col min-w-0 p-3 gap-2">
        <div className="flex justify-between items-center">
          <b className="text-sm">📁 文件</b>
          <span className="flex gap-1.5 items-center">
            <button className="bg-accent text-white text-xs px-3 py-1.5 rounded-lg cursor-pointer disabled:opacity-50"
                    onClick={() => load(path ? crumbs.slice(0, -1).join("/") : "")} disabled={!path} title="返回上级">⬆ 上级</button>
            <button className="bg-accent text-white text-xs px-3 py-1.5 rounded-lg cursor-pointer disabled:opacity-50"
                    onClick={() => fileRef.current?.click()} disabled={uploading}>
              {uploading ? <><span className="inline-block w-3 h-3 border-2 border-white/40 border-t-white rounded-full animate-spin-slow align-middle" /> 上传中</> : "⬆ 上传"}
            </button>
            <button className="bg-accent text-white text-xs px-2 py-1.5 rounded-lg cursor-pointer" onClick={() => load(path)} title="刷新">🔄</button>
          </span>
          <input ref={fileRef} type="file" style={{ display: "none" }}
                 onChange={async (e) => { const f = e.target.files?.[0]; if (f) { await doUpload(f); e.target.value = ""; } }} />
        </div>

        <div className="flex items-center text-xs flex-wrap gap-0.5">
          <button className={`px-1 rounded cursor-pointer hover:bg-card ${!path ? "font-bold" : "text-accent"}`} onClick={() => load("")}>🏠 根目录</button>
          {crumbs.map((c, i) => (
            <span key={i}>
              <span className="text-muted mx-0.5">/</span>
              <button className={`px-1 rounded cursor-pointer hover:bg-card ${i === crumbs.length - 1 ? "font-bold" : "text-accent"}`}
                      onClick={() => load(crumbs.slice(0, i + 1).join("/"))}>{c}</button>
            </span>
          ))}
        </div>

        <div className="flex-1 overflow-auto border border-border rounded-lg bg-panel">
          <table className="w-full text-xs border-collapse">
            <thead>
              <tr><th className="text-left p-2 border-b border-border bg-card sticky top-0">名称</th><th className="text-left p-2 border-b border-border bg-card sticky top-0 w-24">大小</th><th className="text-left p-2 border-b border-border bg-card sticky top-0 w-44">修改时间</th></tr>
            </thead>
            <tbody>
              {items.length === 0 && (
                <tr><td colSpan={3} className="p-8">
                  <div className="text-center text-muted text-sm">
                    <span className="text-4xl block mb-2">{dragOver ? "📥" : "📂"}</span>
                    {dragOver ? "松开鼠标上传文件" : "目录为空 — 拖文件到这里，或点「上传」"}
                  </div>
                </td></tr>
              )}
              {items.map((it) => (
                <tr key={it.path}
                    className={`cursor-pointer hover:bg-card ${selected?.path === it.path ? "bg-accent-soft" : ""}`}
                    onClick={() => openItem(it)}
                    onDoubleClick={() => it.is_dir && load(it.path)}>
                  <td className="p-1.5 px-2 border-b border-border/50"><span className={it.is_dir ? "text-warn" : ""}>{it.is_dir ? "📂" : "📄"}</span> {it.name}</td>
                  <td className="p-1.5 px-2 border-b border-border/50">{it.is_dir ? "—" : fmtSize(it.size)}</td>
                  <td className="p-1.5 px-2 border-b border-border/50 text-muted">{it.mtime ? new Date(it.mtime * 1000).toLocaleString() : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {disk && (
          <div className="text-muted text-xs">已用 {fmtSize(disk.used)} / {fmtSize(disk.total)} · 剩余 {fmtSize(disk.free)}</div>
        )}
      </div>

      <div className="w-[42%] min-w-72 flex flex-col border-l border-border bg-panel">
        {!selected && <div className="p-4 text-muted text-sm">← 点击文件预览（文本/Markdown/图片/PDF）</div>}
        {selected && (
          <>
            <div className="flex justify-between items-center gap-2 px-3 py-2.5 border-b border-border">
              <b className="text-sm truncate flex-1" title={selected.path}>{selected.path.split("/").pop()}</b>
              <a className="bg-accent text-white text-xs px-3 py-1.5 rounded-lg" href={fileDownloadUrl(selected.path)} download>⬇ 下载</a>
            </div>
            {selected.info && (
              <div className="px-3 py-1 text-muted text-xs">
                {fmtSize(selected.info.size)} · {new Date(selected.info.modified * 1000).toLocaleString()}
                {selected.info.indexed && ` · 已索引(${selected.info.indexed.method}, ${selected.info.indexed.chars}字)`}
              </div>
            )}
            <div className="flex-1 overflow-auto">
              {selected.info?.preview_kind === "image" && <img src={fileRawUrl(selected.path)} alt={selected.path} className="max-w-full mx-auto" />}
              {selected.info?.preview_kind === "pdf" && <iframe src={fileRawUrl(selected.path)} title={selected.path} className="w-full h-full min-h-96" />}
              {selected.info?.preview_kind === "text" && isMarkdown ? (
                <div className="markdown-body px-3 py-2"><ReactMarkdown remarkPlugins={[remarkGfm]}>{selected.text}</ReactMarkdown></div>
              ) : (
                <pre className="px-3 py-2 text-xs whitespace-pre-wrap break-all">{selected.text}</pre>
              )}
              {selected.info?.preview_kind === "binary" && <div className="p-4 text-muted text-sm">二进制文件不支持预览，可下载后查看</div>}
            </div>
          </>
        )}
      </div>
    </section>
  );
}
