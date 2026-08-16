"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { listFiles, uploadFile, getFileInfo, renameFile, moveFile, copyFile, mkdir,
  deleteToTrash, listTrash, restoreFromTrash, emptyTrash, FileInfo, fileRawUrl, fileDownloadUrl } from "@/lib/api/files";
import { fmtSize } from "@/lib/format";
import { EV, emitToast, emitFilesChanged, emitTasksChanged } from "@/lib/events";

export default function FilePage() {
  const [path, setPath] = useState("");
  const [items, setItems] = useState<{ name: string; path: string; is_dir: boolean; size: number; mtime?: number }[]>([]);
  const [disk, setDisk] = useState<{ used: number; total: number; free: number } | null>(null);
  const [selected, setSelected] = useState<{ path: string; info: FileInfo | null; text: string } | null>(null);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [showTrash, setShowTrash] = useState(false);
  const [trashItems, setTrashItems] = useState<{ path: string; trash_id: string; deleted_at: number; size: number; is_dir: boolean }[]>([]);
  const [action, setAction] = useState<{ type: string; item: { name: string; path: string; is_dir: boolean } } | null>(null);
  const [actionValue, setActionValue] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
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

  async function doUpload(file: File) {
    setUploading(true);
    try {
      const result = await uploadFile(file, path);
      if (result.indexed?.task_id) emitTasksChanged();
      emitToast({ kind: "ok", text: `已上传 ${file.name}，内容将在后台处理` });
      load(path);
    } catch (e) {
      emitToast({ kind: "error", text: `上传失败: ${e}` });
    } finally {
      setUploading(false);
    }
  }

  async function openItem(it: { name: string; path: string; is_dir: boolean; size: number }) {
    // 单击=选中（目录也可选中用于重命名/移动）；双击=进入目录
    setSelected({ path: it.path, info: it.is_dir ? null : null, text: "" });
    if (it.is_dir) return;
    try {
      const data = await getFileInfo(it.path);
      setSelected((s) => ({ path: s?.path || it.path, info: data, text: data.preview_kind === "text" ? (data.snippet || "") : "" }));
    } catch { /* 忽略 */ }
  }

  const crumbs = path ? path.split("/").filter(Boolean) : [];
  const isMarkdown = selected?.info?.path?.toLowerCase().endsWith(".md");

  async function openTrash() {
    setShowTrash(!showTrash);
    if (!showTrash) {
      try {
        const r = await listTrash();
        setTrashItems(r.items);
      } catch { /* 忽略 */ }
    }
  }

  async function doRestore(item: { path: string; trash_id: string }) {
    try {
      await restoreFromTrash(item.trash_id);
      emitToast({ kind: "ok", text: `已恢复 ${item.path}` });
      setTrashItems((t) => t.filter((x) => x.trash_id !== item.trash_id));
      load(path);
      emitFilesChanged();
    } catch (e) {
      emitToast({ kind: "error", text: `恢复失败: ${e}` });
    }
  }

  async function doEmptyTrash() {
    if (!window.confirm("确定彻底删除回收站所有内容？不可恢复！")) return;
    try {
      const r = await emptyTrash() as { removed?: number };
      emitToast({ kind: "ok", text: `已彻底删除 ${r.removed ?? 0} 项` });
      setTrashItems([]);
    } catch (e) {
      emitToast({ kind: "error", text: `清空失败: ${e}` });
    }
  }

  async function execAction() {
    if (!action) return;
    const { type, item } = action;
    try {
      if (type === "rename") {
        await renameFile(item.path, item.is_dir ? item.path.replace(/[^/]+$/, "") + actionValue : item.path.replace(/[^/]+$/, actionValue));
        emitToast({ kind: "ok", text: "已重命名" });
      } else if (type === "mkdir") {
        await mkdir(path ? path + "/" + actionValue : actionValue);
        emitToast({ kind: "ok", text: "文件夹已创建" });
      } else if (type === "move") {
        await moveFile(item.path, actionValue);
        emitToast({ kind: "ok", text: "已移动" });
      } else if (type === "copy") {
        await copyFile(item.path, actionValue + "/" + item.name);
        emitToast({ kind: "ok", text: "已复制" });
      } else if (type === "delete") {
        if (!window.confirm(`删除 ${item.name}？（移入回收站，30 天内可恢复）`)) return;
        await deleteToTrash(item.path);
        emitToast({ kind: "ok", text: `已删除到回收站` });
        setSelected(null);
      }
      setAction(null);
      setActionValue("");
      load(path);
      emitFilesChanged();
    } catch (e) {
      emitToast({ kind: "error", text: `操作失败: ${e}` });
    }
  }

  function selectedItem() {
    if (!selected) return null;
    const name = selected.path.split("/").pop() || "";
    const isDir = items.find((it) => it.path === selected.path)?.is_dir ?? false;
    return { name, path: selected.path, is_dir: isDir };
  }

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
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <b className="text-sm whitespace-nowrap">📁 文件</b>
          <span className="grid w-full grid-cols-3 gap-1.5 sm:flex sm:w-auto sm:items-center">
            <button className="flex min-w-0 items-center justify-center gap-1 whitespace-nowrap bg-accent text-white text-xs px-2 sm:px-3 py-1.5 rounded-lg cursor-pointer disabled:opacity-30 disabled:cursor-not-allowed"
                    onClick={() => load(path ? crumbs.slice(0, -1).join("/") : "")} disabled={!path} title={path ? "返回上级" : "已在根目录"}><span aria-hidden="true">⬆</span> 上级</button>
            <button className="flex min-w-0 items-center justify-center gap-1 whitespace-nowrap bg-accent text-white text-xs px-2 sm:px-3 py-1.5 rounded-lg cursor-pointer disabled:opacity-50"
                    onClick={() => fileRef.current?.click()} disabled={uploading}>
              {uploading ? <><span className="inline-block w-3 h-3 border-2 border-white/40 border-t-white rounded-full animate-spin-slow align-middle" /> 上传中</> : <><span aria-hidden="true">⬆</span> 上传</>}
            </button>
            <button className="flex min-w-0 items-center justify-center gap-1 whitespace-nowrap bg-accent text-white text-xs px-2 sm:px-3 py-1.5 rounded-lg cursor-pointer" onClick={() => load(path)} title="刷新" aria-label="刷新"><span aria-hidden="true">🔄</span><span className="sm:hidden">刷新</span></button>
            <button className="flex min-w-0 items-center justify-center gap-1 whitespace-nowrap bg-accent text-white text-xs px-2 py-1.5 rounded-lg cursor-pointer sm:hidden" title="拍照上传" aria-label="拍照上传"
                    onClick={() => cameraRef.current?.click()}><span aria-hidden="true">📷</span><span>拍照</span></button>
            <button className="flex min-w-0 items-center justify-center gap-1 whitespace-nowrap bg-accent text-white text-xs px-2 sm:px-3 py-1.5 rounded-lg cursor-pointer" title="新建文件夹" aria-label="新建文件夹"
                    onClick={() => { setAction({ type: "mkdir", item: { name: "", path: "", is_dir: true } }); setActionValue(""); }}><span aria-hidden="true">📁+</span><span className="sm:hidden">新建</span></button>
            <button className="flex min-w-0 items-center justify-center gap-1 whitespace-nowrap bg-accent text-white text-xs px-2 sm:px-3 py-1.5 rounded-lg cursor-pointer" title="回收站" aria-label="回收站"
                    onClick={openTrash}><span aria-hidden="true">♻️</span><span className="sm:hidden">回收站</span></button>
          </span>
          <input ref={fileRef} type="file" style={{ display: "none" }}
                 onChange={async (e) => { const f = e.target.files?.[0]; if (f) { await doUpload(f); e.target.value = ""; } }} />
          <input ref={cameraRef} type="file" accept="image/*" capture="environment" style={{ display: "none" }}
                 onChange={async (e) => { const f = e.target.files?.[0]; if (f) { await doUpload(f); e.target.value = ""; } }} />
        </div>

        {selected && (
          <div className="flex items-center gap-1.5 text-xs flex-wrap">
            <span className="text-muted mr-1 truncate max-w-40" title={selected.path}>已选: {selected.path.split("/").pop()}</span>
            <button className="border border-border px-2 py-1 rounded-lg cursor-pointer hover:border-accent hover:text-accent"
                    onClick={() => { setAction({ type: "rename", item: selectedItem()! }); setActionValue(selectedItem()!.name); }}>✏️ 重命名</button>
            <button className="border border-border px-2 py-1 rounded-lg cursor-pointer hover:border-accent hover:text-accent"
                    onClick={() => { setAction({ type: "move", item: selectedItem()! }); setActionValue(""); }}>🚚 移动</button>
            <button className="border border-border px-2 py-1 rounded-lg cursor-pointer hover:border-accent hover:text-accent"
                    onClick={() => { setAction({ type: "copy", item: selectedItem()! }); setActionValue(""); }}>📄 复制到</button>
            <button className="border border-danger/50 text-danger px-2 py-1 rounded-lg cursor-pointer hover:bg-danger-soft"
                    onClick={() => { setAction({ type: "delete", item: selectedItem()! }); setActionValue(""); }}>🗑️ 删除</button>
          </div>
        )}
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
              <tr><th className="text-left p-2 border-b border-border bg-card sticky top-0">名称</th><th className="text-left p-2 border-b border-border bg-card sticky top-0 w-20 sm:w-24">大小</th><th className="hidden sm:table-cell text-left p-2 border-b border-border bg-card sticky top-0 w-44">修改时间</th></tr>
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
                  <td className="hidden sm:table-cell p-1.5 px-2 border-b border-border/50 text-muted">{it.mtime ? new Date(it.mtime * 1000).toLocaleString() : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {disk && (
          <div className="text-muted text-xs">已用 {fmtSize(disk.used)} / {fmtSize(disk.total)} · 剩余 {fmtSize(disk.free)}</div>
        )}

        {action && (
          <div className="border border-accent/40 bg-panel rounded-lg p-3 text-sm animate-slide-in">
            <div className="font-semibold mb-2">
              {action.type === "rename" && `重命名: ${action.item.name}`}
              {action.type === "move" && `移动: ${action.item.name} → 目标目录`}
              {action.type === "copy" && `复制: ${action.item.name} → 目标目录`}
              {action.type === "delete" && `确认删除: ${action.item.name}（移入回收站）`}
            </div>
            {action.type !== "delete" ? (
              <div className="flex gap-2">
                <input autoFocus value={actionValue}
                       onChange={(e) => setActionValue(e.target.value)}
                       onKeyDown={(e) => { if (e.key === "Enter") execAction(); if (e.key === "Escape") { setAction(null); setActionValue(""); } }}
                       placeholder={action.type === "rename" ? "新名称" : "目标目录（如 资料/合同）"}
                       className="flex-1 border border-border rounded-lg px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-accent-soft" />
                <button className="bg-accent text-white px-3 py-1.5 rounded-lg cursor-pointer" onClick={execAction}>确定</button>
                <button className="border border-border px-3 py-1.5 rounded-lg cursor-pointer" onClick={() => { setAction(null); setActionValue(""); }}>取消</button>
              </div>
            ) : (
              <div className="flex gap-2">
                <button className="bg-danger text-white px-3 py-1.5 rounded-lg cursor-pointer" onClick={execAction}>确认删除</button>
                <button className="border border-border px-3 py-1.5 rounded-lg cursor-pointer" onClick={() => { setAction(null); setActionValue(""); }}>取消</button>
              </div>
            )}
          </div>
        )}
      </div>

      {/* 回收站面板：移动端为全屏覆盖层，桌面端固定侧栏 */}
      {showTrash && (
        <div className="fixed inset-0 z-40 flex flex-col bg-panel animate-fade-in lg:static lg:z-auto lg:w-[42%] lg:min-w-72 lg:border-l lg:border-border">
          <div className="flex justify-between items-center px-3 py-2.5 border-b border-border">
            <b className="text-sm">♻️ 回收站</b>
            <span className="flex gap-1.5">
              <button className="bg-danger text-white text-xs px-2.5 py-1.5 rounded-lg cursor-pointer" onClick={doEmptyTrash}>清空</button>
              <button className="border border-border text-xs px-2 py-1.5 rounded-lg cursor-pointer" onClick={openTrash}>✕</button>
            </span>
          </div>
          <div className="flex-1 overflow-auto p-2">
            {trashItems.length === 0 && (
              <div className="py-8 text-center text-muted text-xs">
                <span className="text-3xl block mb-2">♻️</span>回收站为空
              </div>
            )}
            {trashItems.map((t) => (
              <div key={t.trash_id} className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-sm hover:bg-card">
                <span>{t.is_dir ? "📂" : "📄"}</span>
                <span className="flex-1 truncate text-xs" title={t.path}>{t.path}</span>
                <span className="text-muted text-xs">{new Date(t.deleted_at * 1000).toLocaleDateString()}</span>
                <button className="text-accent text-xs cursor-pointer" onClick={() => doRestore(t)}>恢复</button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 预览面板：移动端选中后全屏覆盖（含关闭按钮），桌面端固定侧栏 */}
      <div className={`${selected ? "fixed inset-0 z-40" : "hidden"} flex flex-col bg-panel lg:static lg:z-auto lg:flex lg:w-[42%] lg:min-w-72 lg:border-l lg:border-border`}>
        {!selected && (
          <div className="p-5 text-sm flex flex-col items-center gap-2 text-center">
            <span className="text-3xl">👁️</span>
            <div className="text-muted">点击左侧文件进行预览</div>
            <div className="text-muted text-xs">支持：文本 · Markdown · 图片 · PDF</div>
          </div>
        )}
        {selected && (
          <>
            <div className="flex justify-between items-center gap-2 px-3 py-2.5 border-b border-border">
              <b className="text-sm truncate flex-1" title={selected.path}>{selected.path.split("/").pop()}</b>
              <a className="bg-accent text-white text-xs px-3 py-1.5 rounded-lg" href={fileDownloadUrl(selected.path)} download>⬇ 下载</a>
              <button className="lg:hidden border border-border text-xs px-2 py-1.5 rounded-lg cursor-pointer" onClick={() => setSelected(null)} aria-label="关闭预览">✕</button>
            </div>
            {selected.info && (
              <div className="px-3 py-1 text-muted text-xs">
                {fmtSize(selected.info.size)} · {new Date(selected.info.modified * 1000).toLocaleString()}
                {selected.info.indexed && ` · 已索引(${selected.info.indexed.method}, ${selected.info.indexed.chars}字)`}
              </div>
            )}
            <div className="flex-1 overflow-auto">
              {selected.info?.preview_kind === "image" && <img src={fileRawUrl(selected.path)} alt={selected.path} className="max-w-full mx-auto" />}
              {selected.info?.preview_kind === "video" && (
                <video src={fileRawUrl(selected.path)} controls className="w-full max-h-full" />
              )}
              {selected.info?.preview_kind === "audio" && (
                <div className="p-6 flex flex-col items-center gap-3">
                  <span className="text-4xl">🎵</span>
                  <div className="text-sm">{selected.path.split("/").pop()}</div>
                  <audio src={fileRawUrl(selected.path)} controls className="w-full" />
                </div>
              )}
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
