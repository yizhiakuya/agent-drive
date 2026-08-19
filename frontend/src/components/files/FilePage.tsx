"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { listFiles, uploadFile, getFileInfo, getFileContent, renameFile, moveFile, copyFile, mkdir,
  deleteToTrash, listTrash, restoreFromTrash, emptyTrash, FileInfo, FileItem, FileSearchMode, fileDownloadUrl } from "@/lib/api/files";
import FilePreview from "./FilePreview";
import FileDetails, { indexStatusLabel } from "./FileDetails";
import { fmtSize, fmtTime } from "@/lib/format";
import { EV, emitToast, emitFilesChanged, emitTasksChanged } from "@/lib/events";
import {
  dispatchFrontendAction,
  isSafeFrontendPath,
  registerFrontendActionHandler,
} from "@/lib/frontend-actions";
import type { PendingFrontendAction } from "@/lib/frontend-actions";
import { useAppStore } from "@/lib/store";
import { enqueueEmbedIndex, enqueueVisionIndex } from "@/lib/api/tasks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Download, Eye, FileText, Info, RefreshCw, Search, X } from "lucide-react";

/** 把后端返回的 cosine 相似度转换成适合文件列表展示的百分比。 */
function searchScoreLabel(score: number | null | undefined) {
  if (typeof score !== "number" || !Number.isFinite(score)) return "";
  return `${Math.max(0, Math.min(100, score * 100)).toFixed(1)}%`;
}

export default function FilePage() {
  const [path, setPath] = useState("");
  const [items, setItems] = useState<FileItem[]>([]);
  const [disk, setDisk] = useState<{ used: number; total: number; free: number } | null>(null);
  const [selected, setSelected] = useState<{ path: string; info: FileInfo | null; text: string } | null>(null);
  const [view, setView] = useState<"preview" | "content" | "details">("preview");
  const [contentLoading, setContentLoading] = useState(false);
  const [contentTruncated, setContentTruncated] = useState(false);
  const [indexing, setIndexing] = useState<"embed" | "vision" | null>(null);
  const [searchInput, setSearchInput] = useState("");
  const [searchMode, setSearchMode] = useState<FileSearchMode>("name");
  const [activeSearchMode, setActiveSearchMode] = useState<FileSearchMode>("name");
  const [activeQuery, setActiveQuery] = useState("");
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [showTrash, setShowTrash] = useState(false);
  const [trashItems, setTrashItems] = useState<{ path: string; trash_id: string; deleted_at: number; size: number; is_dir: boolean }[]>([]);
  const [action, setAction] = useState<{ type: string; item: { name: string; path: string; is_dir: boolean } } | null>(null);
  const [actionValue, setActionValue] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const pathRef = useRef("");
  const queryRef = useRef("");
  const searchModeRef = useRef<FileSearchMode>("name");
  const listRequestRef = useRef(0);
  const selectionRequestRef = useRef(0);
  const contentRequestRef = useRef(0);
  const indexingRequestRef = useRef(0);
  const frontendActions = useAppStore((s) => s.frontendActions);
  const consumeFrontendAction = useAppStore((s) => s.consumeFrontendAction);
  const processingFrontendActionRef = useRef<string | null>(null);

  const invalidateSelectionWork = useCallback(() => {
    const request = ++selectionRequestRef.current;
    contentRequestRef.current += 1;
    indexingRequestRef.current += 1;
    setContentLoading(false);
    setIndexing(null);
    return request;
  }, []);

  const load = useCallback(async (p: string, q = queryRef.current, mode = searchModeRef.current) => {
    const request = ++listRequestRef.current;
    const requestMode = q.trim() ? mode : "name";
    try {
      const r = await listFiles(p, q, requestMode);
      if (request !== listRequestRef.current) return false;
      setItems(r.items);
      setDisk(r.disk);
      setPath(r.path);
      pathRef.current = r.path;
      queryRef.current = q;
      searchModeRef.current = mode;
      setActiveQuery(q);
      setActiveSearchMode(requestMode);
      return true;
    } catch (e) {
      if (request === listRequestRef.current) {
        emitToast({ kind: "error", text: `文件列表加载失败：${String(e)}` });
      }
      return false;
    }
  }, []);

  const openFilePath = useCallback(async (filePath: string) => {
    const selectionRequest = invalidateSelectionWork();
    const separator = filePath.lastIndexOf("/");
    const parent = separator < 0 ? "" : filePath.slice(0, separator);
    const loaded = await load(parent, "");
    if (!loaded || selectionRequest !== selectionRequestRef.current) return;
    setSearchInput("");
    setSelected({ path: filePath, info: null, text: "" });
    setView("preview");
    setContentTruncated(false);
    try {
      const data = await getFileInfo(filePath);
      if (selectionRequest !== selectionRequestRef.current) return;
      setSelected({
        path: filePath,
        info: data,
        text: data.preview_kind === "text" ? (data.snippet || "") : "",
      });
    } catch (error) {
      if (selectionRequest === selectionRequestRef.current) {
        emitToast({ kind: "error", text: `文件详情加载失败：${String(error)}` });
      }
    }
  }, [invalidateSelectionWork, load]);

  const openFolderPath = useCallback(async (folderPath: string) => {
    const selectionRequest = invalidateSelectionWork();
    const loaded = await load(folderPath, "");
    if (!loaded || selectionRequest !== selectionRequestRef.current) return;
    setSearchInput("");
    setSelected(null);
  }, [invalidateSelectionWork, load]);

  useEffect(() => { load(""); }, [load]);
  useEffect(() => () => {
    listRequestRef.current += 1;
    selectionRequestRef.current += 1;
    contentRequestRef.current += 1;
    indexingRequestRef.current += 1;
  }, []);
  useEffect(() => {
    function onFilesChanged() { load(pathRef.current, queryRef.current); }
    window.addEventListener(EV.filesChanged, onFilesChanged);
    return () => window.removeEventListener(EV.filesChanged, onFilesChanged);
  }, [load]);

  useEffect(() => {
    const handleFileAction = async (action: PendingFrontendAction) => {
      const pathValue = action.arguments.path;
      const allowRoot = action.operation === "files.open_folder";
      if (!isSafeFrontendPath(pathValue, allowRoot)) {
        emitToast({ kind: "error", text: "前端动作路径不合法" });
        return;
      }
      if (action.operation === "files.open_folder") {
        await openFolderPath(pathValue);
      } else {
        await openFilePath(pathValue);
      }
    };
    const unregisterOpen = registerFrontendActionHandler("files.open", handleFileAction);
    const unregisterDetails = registerFrontendActionHandler("files.show_details", handleFileAction);
    const unregisterFolder = registerFrontendActionHandler("files.open_folder", handleFileAction);
    return () => {
      unregisterOpen();
      unregisterDetails();
      unregisterFolder();
    };
  }, [openFilePath, openFolderPath]);

  useEffect(() => {
    const action = frontendActions[0];
    if (!action || processingFrontendActionRef.current) return;
    processingFrontendActionRef.current = action.id;
    void dispatchFrontendAction(action)
      .catch((error) => emitToast({ kind: "error", text: `界面动作执行失败：${String(error)}` }))
      .finally(() => {
        consumeFrontendAction(action.id);
        processingFrontendActionRef.current = null;
      });
  }, [consumeFrontendAction, frontendActions]);

  async function doUpload(file: File) {
    setUploading(true);
    try {
      const result = await uploadFile(file, path);
      if (result.indexed?.task_id) emitTasksChanged();
      emitToast({ kind: "ok", text: `已上传 ${file.name}，内容将在后台处理` });
      void load(pathRef.current, queryRef.current, searchModeRef.current);
    } catch (e) {
      emitToast({ kind: "error", text: `上传失败: ${e}` });
    } finally {
      setUploading(false);
    }
  }

  async function openItem(it: FileItem) {
    const selectionRequest = invalidateSelectionWork();
    // 单击=选中（目录也可选中用于重命名/移动）；双击=进入目录
    setSelected({ path: it.path, info: it.is_dir ? null : null, text: "" });
    setView("preview");
    setContentTruncated(false);
    if (it.is_dir) return;
    try {
      const data = await getFileInfo(it.path);
      if (selectionRequest !== selectionRequestRef.current) return;
      setSelected((s) => s?.path === it.path
        ? { path: it.path, info: data, text: data.preview_kind === "text" ? (data.snippet || "") : "" }
        : s);
    } catch {
      // A later selection owns the preview, so an obsolete detail failure is silent.
    }
  }

  /**
   * 提交当前目录的递归文件搜索；空查询恢复普通目录列表。
   *
   * @param event 可选的表单提交事件。
   */
  function submitSearch(event?: React.FormEvent) {
    event?.preventDefault();
    const query = searchInput.trim();
    queryRef.current = query;
    void load(pathRef.current, query, searchMode);
  }

  /** 切换搜索模式；已有查询会立即用新模式重新执行。 */
  function changeSearchMode(mode: FileSearchMode) {
    setSearchMode(mode);
    searchModeRef.current = mode;
    if (queryRef.current.trim()) void load(pathRef.current, queryRef.current, mode);
  }

  /**
   * 读取文本文件的完整内容，并切换到查看内容模式。
   */
  async function viewContent() {
    if (!selected?.info || selected.info.preview_kind !== "text") return;
    const filePath = selected.path;
    const request = ++contentRequestRef.current;
    setView("content");
    setContentLoading(true);
    try {
      const result = await getFileContent(filePath);
      if (request !== contentRequestRef.current) return;
      setSelected((current) => current?.path === filePath
        ? { ...current, text: result.content }
        : current);
      setContentTruncated(result.truncated);
    } catch (error) {
      if (request === contentRequestRef.current) {
        emitToast({ kind: "error", text: `文件内容读取失败：${String(error)}` });
        setView("preview");
      }
    } finally {
      if (request === contentRequestRef.current) setContentLoading(false);
    }
  }

  /**
   * 为当前选中的文件创建后台索引任务，并在入队后刷新文件状态。
   *
   * @param kind 选择普通文本 embedding，或图片视觉描述索引。
   */
  async function enqueueSelectedIndex(kind: "embed" | "vision") {
    if (!selected?.info || indexing) return;
    if (kind === "vision" && selected.info.preview_kind !== "image") return;
    const filePath = selected.path;
    const request = ++indexingRequestRef.current;
    setIndexing(kind);
    try {
      const result = kind === "vision"
        ? await enqueueVisionIndex([filePath])
        : await enqueueEmbedIndex([filePath]);
      emitTasksChanged();
      emitFilesChanged();
      emitToast({
        kind: "ok",
        text: result.queued
          ? kind === "vision" ? "图片视觉索引已进入后台" : "文件向量化已进入后台"
          : "相同文件的索引任务已在处理中",
      });
      const refreshed = await getFileInfo(filePath);
      if (request !== indexingRequestRef.current) return;
      setSelected((current) => current?.path === filePath ? {
        ...current,
        info: refreshed,
        text: refreshed.preview_kind === "text" ? (refreshed.snippet || "") : current.text,
      } : current);
    } catch (error) {
      if (request === indexingRequestRef.current) {
        emitToast({ kind: "error", text: `索引任务创建失败：${String(error)}` });
      }
    } finally {
      if (request === indexingRequestRef.current) setIndexing(null);
    }
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
        invalidateSelectionWork();
        setSelected(null);
      }
      setAction(null);
      setActionValue("");
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
            <Button variant="outline" size="sm" className="min-w-0 whitespace-nowrap"
                    onClick={() => load(path ? crumbs.slice(0, -1).join("/") : "")} disabled={!path} title={path ? "返回上级" : "已在根目录"}><span aria-hidden="true">⬆</span> 上级</Button>
            <Button variant="default" size="sm" className="min-w-0 whitespace-nowrap"
                    onClick={() => fileRef.current?.click()} disabled={uploading}>
              {uploading ? <><span className="inline-block w-3 h-3 border-2 border-white/40 border-t-white rounded-full animate-spin-slow align-middle" /> 上传中</> : <><span aria-hidden="true">⬆</span> 上传</>}
            </Button>
            <Button variant="ghost" size="sm" className="min-w-0 whitespace-nowrap" onClick={() => load(path)} title="刷新" aria-label="刷新"><span aria-hidden="true">🔄</span><span className="sm:hidden">刷新</span></Button>
            <Button variant="default" size="sm" className="min-w-0 whitespace-nowrap sm:hidden" title="拍照上传" aria-label="拍照上传"
                    onClick={() => cameraRef.current?.click()}><span aria-hidden="true">📷</span><span>拍照</span></Button>
            <Button variant="default" size="sm" className="min-w-0 whitespace-nowrap" title="新建文件夹" aria-label="新建文件夹"
                    onClick={() => { setAction({ type: "mkdir", item: { name: "", path: "", is_dir: true } }); setActionValue(""); }}><span aria-hidden="true">📁+</span><span className="sm:hidden">新建</span></Button>
            <Button variant="default" size="sm" className="min-w-0 whitespace-nowrap" title="回收站" aria-label="回收站"
                    onClick={openTrash}><span aria-hidden="true">♻️</span><span className="sm:hidden">回收站</span></Button>
          </span>
          <input ref={fileRef} type="file" style={{ display: "none" }}
                 onChange={async (e) => { const f = e.target.files?.[0]; if (f) { await doUpload(f); e.target.value = ""; } }} />
          <input ref={cameraRef} type="file" accept="image/*" capture="environment" style={{ display: "none" }}
                 onChange={async (e) => { const f = e.target.files?.[0]; if (f) { await doUpload(f); e.target.value = ""; } }} />
        </div>

        <form className="flex flex-col gap-1.5 sm:flex-row sm:items-center" onSubmit={submitSearch} role="search">
          <div className="flex shrink-0 items-center gap-1" role="group" aria-label="搜索方式">
            <span className="text-xs text-muted mr-0.5">搜索方式</span>
            <Button type="button" variant={searchMode === "name" ? "default" : "outline"} size="sm"
                    onClick={() => changeSearchMode("name")}>名称</Button>
            <Button type="button" variant={searchMode === "semantic" ? "default" : "outline"} size="sm"
                    onClick={() => changeSearchMode("semantic")}>语义</Button>
          </div>
          <div className="relative flex-1 min-w-0">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden="true" />
            <Input
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              placeholder={searchMode === "semantic" ? "描述你要找的内容" : "搜索当前目录及子目录"}
              aria-label={searchMode === "semantic" ? "描述你要找的内容" : "搜索当前目录及子目录"}
              className="pl-8 pr-8"
            />
            {searchInput && (
              <button type="button" className="absolute right-2 top-1/2 -translate-y-1/2 text-muted hover:text-foreground" onClick={() => { setSearchInput(""); queryRef.current = ""; void load(path, "", searchMode); }} aria-label="清除搜索">
                <X className="size-4" />
              </button>
            )}
          </div>
          <Button type="submit" variant="outline" size="sm" aria-label="搜索" title="搜索">
            <Search className="size-4" />
          </Button>
        </form>
        {activeQuery && <div className="text-xs text-muted">
          {activeSearchMode === "semantic" ? "语义搜索" : "名称/路径搜索"}“{activeQuery}”，结果包含当前目录子树
        </div>}

        {selected && (
          <div className="flex items-center gap-1.5 text-xs flex-wrap">
            <span className="text-muted mr-1 truncate max-w-40" title={selected.path}>已选: {selected.path.split("/").pop()}</span>
            {selected.info && <Button variant="outline" size="sm" disabled={indexing !== null}
                    onClick={() => void enqueueSelectedIndex("embed")} title="为当前文件创建文本向量索引">
              <RefreshCw className={`size-3.5 ${indexing === "embed" ? "animate-spin" : ""}`} /> 向量化
            </Button>}
            {selected.info?.preview_kind === "image" && <Button variant="outline" size="sm" disabled={indexing !== null}
                    onClick={() => void enqueueSelectedIndex("vision")} title="为当前图片创建视觉索引">
              <Eye className={`size-3.5 ${indexing === "vision" ? "animate-pulse" : ""}`} /> 视觉索引
            </Button>}
            <Button variant="outline" size="sm"
                    onClick={() => { setAction({ type: "rename", item: selectedItem()! }); setActionValue(selectedItem()!.name); }}>✏️ 重命名</Button>
            <Button variant="outline" size="sm"
                    onClick={() => { setAction({ type: "move", item: selectedItem()! }); setActionValue(""); }}>🚚 移动</Button>
            <Button variant="outline" size="sm"
                    onClick={() => { setAction({ type: "copy", item: selectedItem()! }); setActionValue(""); }}>📄 复制到</Button>
            <Button variant="destructive" size="sm"
                    onClick={() => { setAction({ type: "delete", item: selectedItem()! }); setActionValue(""); }}>🗑️ 删除</Button>
          </div>
        )}
        <div className="flex items-center text-xs flex-wrap gap-0.5">
          <Button variant="ghost" size="sm" className={`px-1 ${!path ? "font-bold" : "text-accent"}`} onClick={() => load("")}>🏠 根目录</Button>
          {crumbs.map((c, i) => (
            <span key={i}>
              <span className="text-muted mx-0.5">/</span>
              <Button variant="ghost" size="sm" className={`px-1 ${i === crumbs.length - 1 ? "font-bold" : "text-accent"}`}
                      onClick={() => load(crumbs.slice(0, i + 1).join("/"))}>{c}</Button>
            </span>
          ))}
        </div>

        <div className="flex-1 overflow-auto border border-border rounded-lg bg-panel">
          <table className="w-full text-xs border-collapse">
            <thead>
              <tr><th className="text-left p-2 border-b border-border bg-card sticky top-0">名称</th><th className="text-left p-2 border-b border-border bg-card sticky top-0 w-20 sm:w-24">大小</th><th className="text-left p-2 border-b border-border bg-card sticky top-0 w-28">索引</th><th className="hidden sm:table-cell text-left p-2 border-b border-border bg-card sticky top-0 w-44">修改时间</th></tr>
            </thead>
            <tbody>
              {items.length === 0 && (
                <tr><td colSpan={4} className="p-8">
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
                    onDoubleClick={() => it.is_dir && openFolderPath(it.path)}>
                  <td className="p-1.5 px-2 border-b border-border/50">
                    <div><span className={it.is_dir ? "text-warn" : ""}>{it.is_dir ? "📂" : "📄"}</span> {it.name}</div>
                    {activeSearchMode === "semantic" && !it.is_dir && (it.search_snippet || searchScoreLabel(it.search_score)) && (
                      <div className="mt-1 flex items-start gap-1.5 pl-5 text-[11px]">
                        {searchScoreLabel(it.search_score) && <Badge variant="secondary" className="shrink-0">相关度 {searchScoreLabel(it.search_score)}</Badge>}
                        {it.search_snippet && <span className="line-clamp-2 text-muted">{it.search_snippet}</span>}
                      </div>
                    )}
                  </td>
                  <td className="p-1.5 px-2 border-b border-border/50">{it.is_dir ? "—" : fmtSize(it.size)}</td>
                  <td className="p-1.5 px-2 border-b border-border/50">{!it.is_dir && (
                    activeSearchMode === "semantic"
                      ? <Badge variant="default">已向量化</Badge>
                      : <Badge variant={it.index?.vectorized ? "default" : "outline"}>{indexStatusLabel(it.index)}</Badge>
                  )}</td>
                  <td className="hidden sm:table-cell p-1.5 px-2 border-b border-border/50 text-muted">{fmtTime(it.mtime)}</td>
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
                <Input autoFocus value={actionValue}
                       onChange={(e) => setActionValue(e.target.value)}
                       onKeyDown={(e) => { if (e.key === "Enter") execAction(); if (e.key === "Escape") { setAction(null); setActionValue(""); } }}
                       placeholder={action.type === "rename" ? "新名称" : "目标目录（如 资料/合同）"}
                       className="flex-1" />
                <Button variant="default" size="sm" onClick={execAction}>确定</Button>
                <Button variant="ghost" size="sm" onClick={() => { setAction(null); setActionValue(""); }}>取消</Button>
              </div>
            ) : (
              <div className="flex gap-2">
                <Button variant="destructive" size="sm" onClick={execAction}>确认删除</Button>
                <Button variant="ghost" size="sm" onClick={() => { setAction(null); setActionValue(""); }}>取消</Button>
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
              <Button variant="destructive" size="sm" onClick={doEmptyTrash}>清空</Button>
              <Button variant="ghost" size="sm" onClick={openTrash} aria-label="关闭回收站">✕</Button>
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
                <span className="text-muted text-xs">{fmtTime(t.deleted_at, { dateOnly: true })}</span>
                <Button variant="link" size="sm" onClick={() => doRestore(t)}>恢复</Button>
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
              <span className="flex items-center gap-1">
                {selected.info && <Button variant={view === "preview" ? "default" : "ghost"} size="sm" onClick={() => setView("preview")} aria-label="预览" title="预览"><Eye className="size-4" /></Button>}
                {selected.info?.preview_kind === "text" && <Button variant={view === "content" ? "default" : "ghost"} size="sm" onClick={viewContent} aria-label="查看内容" title="查看内容"><FileText className="size-4" /></Button>}
                {selected.info && <Button variant={view === "details" ? "default" : "ghost"} size="sm" onClick={() => setView("details")} aria-label="文件详情" title="文件详情"><Info className="size-4" /></Button>}
                <Button variant="default" size="sm" asChild aria-label="下载" title="下载"><a href={fileDownloadUrl(selected.path)} download><Download className="size-4" /></a></Button>
                 <Button variant="ghost" size="sm" className="lg:hidden" onClick={() => { invalidateSelectionWork(); setSelected(null); }} aria-label="关闭文件面板" title="关闭"><X className="size-4" /></Button>
              </span>
            </div>
            {selected.info && (
              <div className="px-3 py-1 text-muted text-xs">
                {fmtSize(selected.info.size)} · {fmtTime(selected.info.modified)}
                {selected.info.indexed && <Badge variant={selected.info.indexed.vectorized ? "default" : "outline"} className="ml-2">{indexStatusLabel(selected.info.indexed)}</Badge>}
              </div>
            )}
            <div className="flex-1 overflow-auto">
              {contentLoading && <div className="p-5 text-sm text-muted">正在读取文件内容…</div>}
              {!contentLoading && view === "details" && selected.info && <FileDetails info={selected.info} />}
              {!contentLoading && view !== "details" && selected.info && (
                <>
                  {view === "content" && contentTruncated && <div className="px-3 py-2 text-xs text-warn border-b border-border">文件较大，仅显示前 2 MiB。</div>}
                <FilePreview
                  info={selected.info}
                  path={selected.path}
                  text={selected.text}
                  isMarkdown={isMarkdown}
                  variant="page"
                />
                </>
              )}
            </div>
          </>
        )}
      </div>
    </section>
  );
}
