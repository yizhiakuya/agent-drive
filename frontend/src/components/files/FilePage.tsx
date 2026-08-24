"use client";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { listFiles, listFavorites, listRecent, setFavorite, getFileInfo, getFileContent, renameFile, moveFile, copyFile, mkdir,
  deleteToTrash, listTrash, restoreFromTrash, emptyTrash, FileInfo, FileItem, FileSearchMode, FileTypeFilter, fileDownloadUrl } from "@/lib/api/files";
import FilePreview from "./FilePreview";
import FileDetails, { indexStatusLabel } from "./FileDetails";
import { fmtSize, fmtTime } from "@/lib/format";
import { EV, emitToast, emitFilesChanged } from "@/lib/events";
import {
  dispatchFrontendAction,
  isSafeFrontendPath,
  registerFrontendActionHandler,
} from "@/lib/frontend-actions";
import type { PendingFrontendAction } from "@/lib/frontend-actions";
import { useAppStore } from "@/lib/store";
import { indexFiles, indexVision, vectorize, type IndexResult } from "@/lib/api/index";
import {
  finishOperationActivity,
  startOperationActivity,
  updateOperationActivity,
} from "@/lib/operation-activity";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import {
  ArchiveRestore,
  ArrowLeft,
  Camera,
  ChevronRight,
  Copy,
  Download,
  Eye,
  File,
  FilePlus2,
  FileText,
  Folder,
  FolderOpen,
  FolderPlus,
  Home,
  Info,
  MoveRight,
  MoreHorizontal,
  Pencil,
  RefreshCw,
  Search,
  Star,
  Clock3,
  Trash2,
  Upload,
  X,
} from "lucide-react";
import UploadQueueBar from "./UploadQueueBar";
import { useUploadQueue } from "./useUploadQueue";

/** 把后端返回的 cosine 相似度转换成适合文件列表展示的百分比。 */
function searchScoreLabel(score: number | null | undefined) {
  if (typeof score !== "number" || !Number.isFinite(score)) return "";
  return `${Math.max(0, Math.min(100, score * 100)).toFixed(1)}%`;
}

type FileActionItem = { name: string; path: string; is_dir: boolean };
type FileSort = "name" | "mtime" | "size";

function isCompactViewport() {
  return typeof window !== "undefined"
    && typeof window.matchMedia === "function"
    && window.matchMedia("(max-width: 1023px)").matches;
}

function epochBoundary(value: string, endOfDay = false) {
  if (!value) return undefined;
  const parsed = new Date(`${value}T${endOfDay ? "23:59:59.999" : "00:00:00"}`);
  const seconds = parsed.getTime() / 1000;
  return Number.isFinite(seconds) ? seconds : undefined;
}

/** 以小并发执行批量 mutation，避免一次选择大量文件时压垮 API 和 storage lock。 */
async function settleWithConcurrency<T>(
  values: T[],
  operation: (value: T) => Promise<unknown>,
  concurrency = 4,
  onSettled?: (completed: number) => void,
): Promise<PromiseSettledResult<unknown>[]> {
  const results: PromiseSettledResult<unknown>[] = new Array(values.length);
  let completed = 0;
  let cursor = 0;
  const worker = async () => {
    while (true) {
      const index = cursor++;
      if (index >= values.length) return;
      try {
        results[index] = { status: "fulfilled", value: await operation(values[index]) };
      } catch (reason) {
        results[index] = { status: "rejected", reason };
      }
      completed += 1;
      onSettled?.(completed);
    }
  };
  await Promise.all(Array.from({ length: Math.min(Math.max(1, concurrency), values.length) }, worker));
  return results;
}

export default function FilePage() {
  const [path, setPath] = useState("");
  const [collection, setCollection] = useState<"browse" | "favorites" | "recent">("browse");
  const [items, setItems] = useState<FileItem[]>([]);
  const [disk, setDisk] = useState<{ used: number; total: number; free: number } | null>(null);
  const [selected, setSelected] = useState<{ path: string; info: FileInfo | null; text: string } | null>(null);
  // 目录点击直接进入；目录仍可通过行尾操作按钮进入批量操作态，不打开预览覆盖层。
  const [actionSelection, setActionSelection] = useState<FileActionItem | null>(null);
  const [view, setView] = useState<"preview" | "content" | "details">("preview");
  const [contentLoading, setContentLoading] = useState(false);
  const [contentTruncated, setContentTruncated] = useState(false);
  const [indexing, setIndexing] = useState<"embed" | "vision" | null>(null);
  const [searchInput, setSearchInput] = useState("");
  const [searchMode, setSearchMode] = useState<FileSearchMode>("name");
  const [activeSearchMode, setActiveSearchMode] = useState<FileSearchMode>("name");
  const [activeQuery, setActiveQuery] = useState("");
  const [sortBy, setSortBy] = useState<FileSort>("name");
  const [minScore, setMinScore] = useState("");
  const [fileType, setFileType] = useState<FileTypeFilter>("all");
  const [modifiedAfterInput, setModifiedAfterInput] = useState("");
  const [modifiedBeforeInput, setModifiedBeforeInput] = useState("");
  const [searchHasMore, setSearchHasMore] = useState(false);
  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(() => new Set());
  const [dragOver, setDragOver] = useState(false);
  const [showTrash, setShowTrash] = useState(false);
  const [trashItems, setTrashItems] = useState<{ path: string; trash_id: string; deleted_at: number; size: number; is_dir: boolean }[]>([]);
  const [action, setAction] = useState<{ type: string; item: { name: string; path: string; is_dir: boolean } } | null>(null);
  const [actionValue, setActionValue] = useState("");
  const [listError, setListError] = useState("");
  const [listLoading, setListLoading] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const pathRef = useRef("");
  const queryRef = useRef("");
  const searchModeRef = useRef<FileSearchMode>("name");
  const filtersRef = useRef<{ type: FileTypeFilter; modifiedAfter: string; modifiedBefore: string; minScore: string }>({ type: "all", modifiedAfter: "", modifiedBefore: "", minScore: "" });
  const listRequestRef = useRef(0);
  const selectionRequestRef = useRef(0);
  const contentRequestRef = useRef(0);
  const indexingRequestRef = useRef(0);
  const trashRequestRef = useRef(0);
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
    setListLoading(true);
    setListError("");
    try {
      const filters = filtersRef.current;
      const listFilters = {
        type: filters.type,
        modifiedAfter: epochBoundary(filters.modifiedAfter),
        modifiedBefore: epochBoundary(filters.modifiedBefore, true),
        minScore: requestMode === "semantic" && filters.minScore.trim() ? Number(filters.minScore) / 100 : undefined,
      };
      const hasFilters = listFilters.type !== "all"
        || listFilters.modifiedAfter !== undefined
        || listFilters.modifiedBefore !== undefined
        || listFilters.minScore !== undefined;
      const r = hasFilters
        ? await listFiles(p, q, requestMode, listFilters)
        : await listFiles(p, q, requestMode);
      if (request !== listRequestRef.current) return false;
      setCollection("browse");
      setItems(r.items);
      setListError("");
      setDisk(r.disk);
      setPath(r.path);
      pathRef.current = r.path;
      queryRef.current = q;
      searchModeRef.current = mode;
      setActiveQuery(q);
      setActiveSearchMode(requestMode);
      setSearchHasMore(Boolean(r.has_more ?? (q.trim() && r.items.length >= 1000)));
      return true;
    } catch (e) {
      if (request === listRequestRef.current) {
        setListError(e instanceof Error ? e.message : String(e));
        emitToast({ kind: "error", text: `文件列表加载失败：${String(e)}` });
      }
      return false;
    } finally {
      if (request === listRequestRef.current) setListLoading(false);
    }
  }, []);

  const refreshAfterUpload = useCallback(() => {
    void load(pathRef.current, queryRef.current, searchModeRef.current);
  }, [load]);
  const { uploading, uploadQueue, uploadFiles, cancelUpload, retryUpload } = useUploadQueue(
    pathRef,
    refreshAfterUpload,
  );

  const loadCollection = useCallback(async (kind: "favorites" | "recent") => {
    const request = ++listRequestRef.current;
    setListLoading(true);
    setListError("");
    try {
      const r = kind === "favorites" ? await listFavorites() : await listRecent();
      if (request !== listRequestRef.current) return false;
      setCollection(kind);
      setItems(r.items);
      setDisk(r.disk);
      setPath("");
      pathRef.current = "";
      queryRef.current = "";
      setActiveQuery("");
      setActiveSearchMode("name");
      setSearchInput("");
      setSearchHasMore(Boolean(r.has_more));
      return true;
    } catch (error) {
      if (request === listRequestRef.current) {
        setListError(error instanceof Error ? error.message : String(error));
        emitToast({ kind: "error", text: `文件集合加载失败：${String(error)}` });
      }
      return false;
    } finally {
      if (request === listRequestRef.current) setListLoading(false);
    }
  }, []);

  const openFilePath = useCallback(async (filePath: string) => {
    const selectionRequest = invalidateSelectionWork();
    const separator = filePath.lastIndexOf("/");
    const parent = separator < 0 ? "" : filePath.slice(0, separator);
    const loaded = await load(parent, "");
    if (!loaded || selectionRequest !== selectionRequestRef.current) return;
    setSearchInput("");
    setActionSelection(null);
    setSelectedPaths(new Set());
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
    setActionSelection(null);
    setSelectedPaths(new Set());
  }, [invalidateSelectionWork, load]);

  useEffect(() => { load(""); }, [load]);
  useEffect(() => () => {
    listRequestRef.current += 1;
    selectionRequestRef.current += 1;
    contentRequestRef.current += 1;
    indexingRequestRef.current += 1;
    trashRequestRef.current += 1;
  }, []);
  useEffect(() => {
    function onFilesChanged() {
      if (collection === "browse") void load(pathRef.current, queryRef.current);
      else void loadCollection(collection);
    }
    window.addEventListener(EV.filesChanged, onFilesChanged);
    return () => window.removeEventListener(EV.filesChanged, onFilesChanged);
  }, [collection, load, loadCollection]);

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

  async function openItem(it: FileItem) {
    // 目录点击直接进入，避免移动端先打开预览覆盖层后无法再次触达列表。
    if (it.is_dir) {
      await openFolderPath(it.path);
      return;
    }
    const selectionRequest = invalidateSelectionWork();
    setActionSelection(null);
    setSelected({ path: it.path, info: null, text: "" });
    setView("preview");
    setContentTruncated(false);
    if (it.is_dir) return;
    try {
      const data = await getFileInfo(it.path);
      if (selectionRequest !== selectionRequestRef.current) return;
      setSelected((s) => s?.path === it.path
        ? { path: it.path, info: data, text: data.preview_kind === "text" ? (data.snippet || "") : "" }
        : s);
    } catch (error) {
      if (selectionRequest === selectionRequestRef.current) {
        emitToast({ kind: "error", text: `文件详情加载失败：${String(error)}` });
      }
    }
  }

  async function toggleFavorite(item: FileItem) {
    const nextFavorite = !item.favorite;
    try {
      await setFavorite(item.path, nextFavorite);
      setItems((current) => current
        .map((entry) => entry.path === item.path ? { ...entry, favorite: nextFavorite } : entry)
        .filter((entry) => collection !== "favorites" || entry.favorite));
      emitToast({ kind: "ok", text: nextFavorite ? `已收藏 ${item.name}` : `已取消收藏 ${item.name}` });
    } catch (error) {
      emitToast({ kind: "error", text: `收藏操作失败：${String(error)}` });
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
    filtersRef.current = {
      type: fileType,
      modifiedAfter: modifiedAfterInput,
      modifiedBefore: modifiedBeforeInput,
      minScore,
    };
    setCollection("browse");
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
   * 为当前选中的文件直接执行索引业务，并在完成后刷新文件状态。
   *
   * @param kind 选择普通文本 embedding，或图片视觉描述索引。
   */
  async function enqueueSelectedIndex(kind: "embed" | "vision") {
    if (!selected?.info || indexing) return;
    if (kind === "vision" && selected.info.preview_kind !== "image") return;
    const filePath = selected.path;
    const request = ++indexingRequestRef.current;
    const activityId = startOperationActivity({
      source: "ui",
      kind: kind === "vision" ? "index-vision" : "index-vector",
      title: kind === "vision" ? "图片视觉索引" : "文件向量化",
      operation: kind === "vision" ? "PUT /api/v1/index/vision" : "PUT /api/v1/index/vectors",
      target: filePath,
      phase: kind === "vision" ? "vision" : "preparing",
      message: kind === "vision" ? "正在生成图片内容描述" : "正在准备文本索引",
    });
    setIndexing(kind);
    let activitySettled = false;
    try {
      if (kind === "vision") updateOperationActivity(activityId, { phase: "vision", message: "正在生成图片内容描述" });
      const result = kind === "vision"
        ? await indexVision([filePath])
        : await indexTextAndVectors(filePath, activityId);
      const failure = indexFailure(result);
      const counts = indexActivityCounts(result);
      if (failure) {
        finishOperationActivity(activityId, result.status === "partial" ? "partial" : "failed", {
          phase: "finished",
          message: result.status === "partial" ? "部分索引完成" : "索引失败",
        ...counts,
          error: failure,
        });
        activitySettled = true;
        throw new Error(failure);
      }
      finishOperationActivity(activityId, "succeeded", {
        phase: "finished",
        message: kind === "vision" ? "图片视觉索引已完成" : "文件文本与向量索引已完成",
        ...counts,
      });
      activitySettled = true;
      emitFilesChanged();
      emitToast({
        kind: "ok",
        text: kind === "vision" ? "图片视觉索引已完成" : "文件文本与向量索引已完成",
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
          const current = error instanceof Error ? error.message : String(error);
          if (!activitySettled) finishOperationActivity(activityId, "failed", {
              phase: "finished",
              message: "索引执行失败",
              error: current,
            });
          emitToast({ kind: "error", text: `索引执行失败：${String(error)}` });
      }
    } finally {
      if (request === indexingRequestRef.current) setIndexing(null);
    }
  }

  /** 文本文件需要先抽取正文，再按当前 embedding 配置写入向量。 */
  async function indexTextAndVectors(filePath: string, activityId?: string): Promise<IndexResult> {
    if (activityId) updateOperationActivity(activityId, { phase: "extracting", message: "正在抽取文件正文" });
    const extracted = await indexFiles([filePath]);
    const extractionFailure = indexFailure(extracted);
    if (extractionFailure) throw new Error(extractionFailure);
    if (activityId) updateOperationActivity(activityId, { phase: "embedding", message: "正在生成文本向量" });
    return vectorize([filePath]);
  }

  function indexActivityCounts(result: IndexResult): Pick<import("@/lib/operation-activity").OperationActivity, "completed" | "total" | "succeeded" | "failed"> {
    const items = Array.isArray(result.items) ? result.items : [];
    const failed = typeof result.failed === "number"
      ? result.failed
      : items.filter((item) => item.indexed === false || item.status === "error").length;
    const embedded = typeof result.embedded === "number" ? result.embedded : undefined;
    const total = items.length > 0 ? items.length : embedded;
    const completed = embedded ?? (items.length > 0 ? items.length : undefined);
    return {
      completed,
      total,
      succeeded: total === undefined ? undefined : Math.max(0, total - failed),
      failed,
    };
  }

  /** 从统一索引 envelope 提取逐项错误，兼容旧响应中的 reason/error 字段。 */
  function indexFailure(result: IndexResult): string | null {
    if (result.vectorized === false) {
      return String(result.reason || result.error || "向量化未完成");
    }
    const failedItem = result.items?.find((item) => item.indexed === false
      || (item.embedding && typeof item.embedding === "object"
        && (item.embedding as { vectorized?: unknown }).vectorized === false)
      || item.status === "error");
    if (failedItem) return String(failedItem.error || failedItem.status || "索引项失败");
    if (result.ok === false || result.status === "failed" || result.status === "partial") {
      return String(result.error || result.reason || "索引未完整完成");
    }
    return null;
  }

  const crumbs = path ? path.split("/").filter(Boolean) : [];
  const isMarkdown = selected?.info?.path?.toLowerCase().endsWith(".md");

  async function refreshSelectedFile() {
    if (!selected) return;
    const filePath = selected.path;
    const refreshed = await getFileInfo(filePath);
    setSelected((current) => current?.path === filePath
      ? { ...current, info: refreshed, text: refreshed.preview_kind === "text" ? (refreshed.snippet || "") : current.text }
      : current);
    emitFilesChanged();
    emitToast({ kind: "ok", text: "已恢复文件版本" });
  }

  async function openTrash() {
    const opening = !showTrash;
    const request = ++trashRequestRef.current;
    setShowTrash(opening);
    if (!opening) return;
    try {
      const r = await listTrash();
      if (request !== trashRequestRef.current) return;
      setTrashItems(r.items);
    } catch (error) {
      if (request === trashRequestRef.current) {
        emitToast({ kind: "error", text: `回收站加载失败：${String(error)}` });
      }
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
      if (type === "batch-move" || type === "batch-copy" || type === "batch-delete") {
        const paths = Array.from(selectedPaths);
        if (paths.length === 0) return;
        if (type === "batch-delete") {
          if (!window.confirm(`删除选中的 ${paths.length} 项？（移入回收站，30 天内可恢复）`)) return;
        } else if (!actionValue.trim()) {
          emitToast({ kind: "error", text: "请填写目标目录" });
          return;
        }
        const activityId = startOperationActivity({
          source: "ui",
          kind: type,
          title: type === "batch-move" ? "批量移动文件" : type === "batch-copy" ? "批量复制文件" : "批量删除文件",
          target: `${paths.length} 项`,
          phase: "running",
          message: `正在处理 0/${paths.length} 项`,
          completed: 0,
          total: paths.length,
        });
        const results = await settleWithConcurrency(paths, (source) => {
          if (type === "batch-delete") return deleteToTrash(source);
          if (type === "batch-move") return moveFile(source, actionValue.trim());
          const name = source.split("/").pop() || source;
          return copyFile(source, `${actionValue.trim().replace(/\/$/, "")}/${name}`);
        }, 4, (completed) => updateOperationActivity(activityId, {
          completed,
          message: `正在处理 ${completed}/${paths.length} 项`,
        }));
        const failed = results.filter((result) => result.status === "rejected").length;
        finishOperationActivity(activityId, failed === 0 ? "succeeded" : failed === paths.length ? "failed" : "partial", {
          phase: "finished",
          message: failed === 0 ? `已处理 ${paths.length} 项` : `已处理 ${paths.length - failed}/${paths.length} 项`,
          completed: paths.length,
          total: paths.length,
          succeeded: paths.length - failed,
          failed,
          ...(failed > 0 ? { error: `${failed} 项操作失败` } : {}),
        });
        setSelectedPaths(new Set());
        emitToast({ kind: failed ? "error" : "ok", text: failed ? `${paths.length - failed} 项完成，${failed} 项失败` : `已处理 ${paths.length} 项` });
      } else if (type === "rename") {
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
        setActionSelection(null);
      }
      setAction(null);
      setActionValue("");
      setActionSelection(null);
      emitFilesChanged();
    } catch (e) {
      emitToast({ kind: "error", text: `操作失败: ${e}` });
    }
  }

  function selectedItem() {
    if (actionSelection) return actionSelection;
    if (!selected) return null;
    const name = selected.path.split("/").pop() || "";
    const isDir = items.find((it) => it.path === selected.path)?.is_dir ?? false;
    return { name, path: selected.path, is_dir: isDir };
  }

  function beginAction(type: string, candidate = selectedItem()) {
    if (!candidate) return;
    setAction({ type, item: candidate });
    setActionValue(type === "rename" ? candidate.name : "");
    // The mobile preview is a full-screen layer. Close it before showing the
    // existing operation form so the form remains visible and keyboard reachable.
    if (selected && isCompactViewport()) {
      invalidateSelectionWork();
      setSelected(null);
    }
  }

  function beginBatchAction(type: "batch-move" | "batch-copy" | "batch-delete") {
    const count = selectedPaths.size;
    if (count === 0) return;
    setAction({ type, item: { name: `${count} 项`, path: "", is_dir: false } });
    setActionValue("");
  }

  const displayItems = useMemo(() => {
    const threshold = activeSearchMode === "semantic" && minScore.trim()
      ? Math.max(0, Number(minScore) || 0) / 100
      : 0;
    const filtered = items.filter((item) => item.is_dir || activeSearchMode !== "semantic"
      || typeof item.search_score !== "number" || item.search_score >= threshold);
    return [...filtered].sort((left, right) => {
      if (left.is_dir !== right.is_dir) return left.is_dir ? -1 : 1;
      if (sortBy === "size") return (left.size || 0) - (right.size || 0);
      if (sortBy === "mtime") return (left.mtime || 0) - (right.mtime || 0);
      return left.name.localeCompare(right.name, undefined, { numeric: true, sensitivity: "base" });
    });
  }, [activeSearchMode, items, minScore, sortBy]);

  function togglePath(pathValue: string, checked: boolean) {
    setSelectedPaths((current) => {
      const next = new Set(current);
      if (checked) next.add(pathValue); else next.delete(pathValue);
      return next;
    });
    if (selected) {
      invalidateSelectionWork();
      setSelected(null);
    }
  }

  function toggleAllVisible(checked: boolean) {
    setSelectedPaths((current) => {
      const next = new Set(current);
      displayItems.forEach((item) => checked ? next.add(item.path) : next.delete(item.path));
      return next;
    });
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
        if (file) void uploadFiles([file]);
      }}
    >
      <div className="flex-1 flex flex-col min-w-0 gap-3 bg-bg p-3 sm:p-4">
        <div className="flex flex-col gap-3 border-b border-border pb-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-2 text-sm font-semibold">
            <Folder className="size-4 text-muted" aria-hidden="true" />
            <span>文件</span>
            {path && <span className="text-xs font-normal text-muted">/ {path}</span>}
          </div>
          <span className="grid w-full grid-cols-3 gap-1.5 sm:flex sm:w-auto sm:items-center">
            <Button variant="outline" size="sm" className="min-w-0 whitespace-nowrap"
                    onClick={() => load(path ? crumbs.slice(0, -1).join("/") : "")} disabled={!path} title={path ? "返回上级" : "已在根目录"}><ArrowLeft className="size-3.5" /> <span>上级</span></Button>
            <Button variant="default" size="sm" className="min-w-0 whitespace-nowrap"
                    onClick={() => fileRef.current?.click()} disabled={uploading}>
              {uploading ? <><span className="inline-block size-3 border-2 border-white/40 border-t-white rounded-full animate-spin-slow" /> 上传中</> : <><Upload className="size-3.5" /> 上传</>}
            </Button>
            <Button variant="ghost" size="sm" className="min-w-0 whitespace-nowrap" onClick={() => load(path)} title="刷新" aria-label="刷新"><RefreshCw className="size-3.5" /><span className="sm:hidden">刷新</span></Button>
            <Button variant="outline" size="sm" className="min-w-0 whitespace-nowrap sm:hidden" title="拍照上传" aria-label="拍照上传"
                    onClick={() => cameraRef.current?.click()}><Camera className="size-3.5" /><span>拍照</span></Button>
            <Button variant="outline" size="sm" className="min-w-0 whitespace-nowrap" title="新建文件夹" aria-label="新建文件夹"
                    onClick={() => { setAction({ type: "mkdir", item: { name: "", path: "", is_dir: true } }); setActionValue(""); }}><FolderPlus className="size-3.5" /><span className="sm:hidden">新建</span></Button>
            <Button variant="outline" size="sm" className="min-w-0 whitespace-nowrap" title="回收站" aria-label="回收站"
                    onClick={openTrash}><ArchiveRestore className="size-3.5" /><span className="sm:hidden">回收站</span></Button>
          </span>
          <input ref={fileRef} type="file" multiple style={{ display: "none" }}
                 onChange={async (e) => { const files = Array.from(e.target.files || []); if (files.length) { await uploadFiles(files); e.target.value = ""; } }} />
          <input ref={cameraRef} type="file" accept="image/*" capture="environment" style={{ display: "none" }}
                 onChange={async (e) => { const f = e.target.files?.[0]; if (f) { await uploadFiles([f]); e.target.value = ""; } }} />
        </div>

        <div className="flex flex-wrap items-center gap-1 border-b border-border pb-2" role="tablist" aria-label="文件集合">
          <Button type="button" size="sm" variant={collection === "browse" ? "default" : "ghost"}
                  onClick={() => { setCollection("browse"); void load(pathRef.current, queryRef.current, searchModeRef.current); }}>
            <FolderOpen className="size-3.5" /> 全部文件
          </Button>
          <Button type="button" size="sm" variant={collection === "favorites" ? "default" : "ghost"}
                  onClick={() => void loadCollection("favorites")}>
            <Star className="size-3.5" /> 收藏
          </Button>
          <Button type="button" size="sm" variant={collection === "recent" ? "default" : "ghost"}
                  onClick={() => void loadCollection("recent")}>
            <Clock3 className="size-3.5" /> 最近访问
          </Button>
        </div>

        <form className="flex flex-col gap-2 sm:flex-row sm:items-center" onSubmit={submitSearch} role="search">
          <div className="inline-flex shrink-0 items-center self-start rounded-md border border-border bg-card p-0.5" role="group" aria-label="搜索方式">
            <span className="px-2 text-[10px] font-semibold uppercase tracking-[0.1em] text-muted">搜索</span>
            <Button type="button" variant={searchMode === "name" ? "default" : "ghost"} size="xs"
                    className="h-6" onClick={() => changeSearchMode("name")}>名称</Button>
            <Button type="button" variant={searchMode === "semantic" ? "default" : "ghost"} size="xs"
                    className="h-6" onClick={() => changeSearchMode("semantic")}>语义</Button>
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
         <div className="flex flex-wrap items-center gap-2">
           <label className="flex items-center gap-1.5 text-xs text-muted">
             <span>排序</span>
             <Select value={sortBy} onValueChange={(value) => { if (value === "name" || value === "mtime" || value === "size") setSortBy(value); }}>
               <SelectTrigger size="sm" aria-label="文件排序" className="h-7 w-28 text-xs"><SelectValue /></SelectTrigger>
               <SelectContent>
                 <SelectItem value="name">名称</SelectItem>
                 <SelectItem value="mtime">修改时间</SelectItem>
                 <SelectItem value="size">大小</SelectItem>
               </SelectContent>
             </Select>
           </label>
           {activeSearchMode === "semantic" && (
             <label className="flex items-center gap-1.5 text-xs text-muted">
               <span>最低相关度</span>
               <Input aria-label="最低相关度" inputMode="numeric" value={minScore} onChange={(event) => setMinScore(event.target.value.replace(/[^0-9]/g, ""))} className="h-7 w-16 px-2 text-xs" placeholder="0" />
               <span>%</span>
             </label>
           )}
           <label className="flex items-center gap-1.5 text-xs text-muted">
             <span>类型</span>
             <Select value={fileType} onValueChange={(value) => {
               if (["all", "file", "folder", "image", "video", "audio", "pdf", "text"].includes(value)) {
                 setFileType(value as FileTypeFilter);
               }
             }}>
               <SelectTrigger size="sm" aria-label="文件类型筛选" className="h-7 w-24 text-xs"><SelectValue /></SelectTrigger>
               <SelectContent>
                 <SelectItem value="all">全部类型</SelectItem>
                 <SelectItem value="file">普通文件</SelectItem>
                 <SelectItem value="folder">文件夹</SelectItem>
                 <SelectItem value="image">图片</SelectItem>
                 <SelectItem value="video">视频</SelectItem>
                 <SelectItem value="audio">音频</SelectItem>
                 <SelectItem value="pdf">PDF</SelectItem>
                 <SelectItem value="text">文本</SelectItem>
               </SelectContent>
             </Select>
           </label>
           <label className="flex items-center gap-1.5 text-xs text-muted">
             <span>修改自</span>
             <Input type="date" aria-label="修改时间起点" value={modifiedAfterInput} onChange={(event) => setModifiedAfterInput(event.target.value)} className="h-7 w-32 px-2 text-xs" />
           </label>
           <label className="flex items-center gap-1.5 text-xs text-muted">
             <span>至</span>
             <Input type="date" aria-label="修改时间终点" value={modifiedBeforeInput} onChange={(event) => setModifiedBeforeInput(event.target.value)} className="h-7 w-32 px-2 text-xs" />
           </label>
         </div>
         {activeQuery && <div className="text-xs text-muted">
           {activeSearchMode === "semantic" ? "语义搜索" : "名称/路径搜索"}“{activeQuery}”，结果包含当前目录子树
           {searchHasMore && <span className="ml-2 text-warn">结果已截断，缩小范围以查看更准确匹配</span>}
         </div>}

        <div
          data-testid="file-selection-toolbar"
          className={`h-12 shrink-0 overflow-hidden border-b text-xs sm:h-9 ${selected || actionSelection ? "border-border" : "border-transparent"}`}
        >
          {(selected || actionSelection) && (
            <div className="flex h-full items-center gap-1.5 overflow-x-auto whitespace-nowrap">
              <span className="mr-1 max-w-40 truncate font-medium text-text" title={selectedItem()!.path}>已选: {selectedItem()!.path.split("/").pop()}</span>
              {selected?.info && selected.info.preview_kind !== "image" && <Button variant="outline" size="sm" disabled={indexing !== null}
                      onClick={() => void enqueueSelectedIndex("embed")} title="为当前文件创建文本向量索引">
                <RefreshCw className={`size-3.5 ${indexing === "embed" ? "animate-spin" : ""}`} /> 向量化
              </Button>}
              {selected?.info?.preview_kind === "image" && <Button variant="outline" size="sm" disabled={indexing !== null}
                      onClick={() => void enqueueSelectedIndex("vision")} title="为当前图片创建视觉索引">
                <Eye className={`size-3.5 ${indexing === "vision" ? "animate-pulse" : ""}`} /> 视觉索引
              </Button>}
              <Button variant="outline" size="sm" onClick={() => beginAction("rename")}><Pencil className="size-3.5" /> 重命名</Button>
              <Button variant="outline" size="sm" onClick={() => beginAction("move")}><MoveRight className="size-3.5" /> 移动</Button>
              <Button variant="outline" size="sm" onClick={() => beginAction("copy")}><Copy className="size-3.5" /> 复制到</Button>
              <Button variant="destructive" size="sm"
                      onClick={() => beginAction("delete")}><Trash2 className="size-3.5" /> 删除</Button>
            </div>
          )}
        </div>
        {selectedPaths.size > 0 && (
          <div data-testid="file-batch-toolbar" className="flex h-9 shrink-0 items-center gap-1.5 overflow-x-auto whitespace-nowrap border-b border-border text-xs">
            <span className="mr-1 font-medium text-text">已选 {selectedPaths.size} 项</span>
            <Button type="button" variant="outline" size="sm" onClick={() => beginBatchAction("batch-move")}><MoveRight className="size-3.5" /> 移动</Button>
            <Button type="button" variant="outline" size="sm" onClick={() => beginBatchAction("batch-copy")}><Copy className="size-3.5" /> 复制</Button>
            <Button type="button" variant="destructive" size="sm" onClick={() => beginBatchAction("batch-delete")}><Trash2 className="size-3.5" /> 删除</Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setSelectedPaths(new Set())}>清除选择</Button>
          </div>
        )}
        <UploadQueueBar
          entries={uploadQueue}
          uploading={uploading}
          onCancel={cancelUpload}
          onRetry={(id) => { void retryUpload(id); }}
        />
        <div className="flex items-center gap-0.5 text-xs flex-wrap" aria-label="当前位置">
          <Button variant="ghost" size="sm" className={`px-1.5 ${!path ? "font-semibold text-text" : "text-muted"}`} onClick={() => load("")}><Home className="size-3.5" /> 根目录</Button>
          {crumbs.map((c, i) => (
            <span key={i}>
              <ChevronRight className="mx-0.5 inline size-3 text-muted" aria-hidden="true" />
              <Button variant="ghost" size="sm" className={`px-1.5 ${i === crumbs.length - 1 ? "font-semibold text-text" : "text-muted"}`}
                      onClick={() => load(crumbs.slice(0, i + 1).join("/"))}>{c}</Button>
            </span>
          ))}
        </div>

        <div className="flex-1 overflow-auto border-y border-border bg-panel">
          <table className="w-full text-xs border-collapse">
            <thead>
              <tr><th className="sticky top-0 w-10 border-b border-border bg-card/80 p-3 text-left font-semibold"><input type="checkbox" aria-label="全选当前文件" checked={displayItems.length > 0 && displayItems.every((item) => selectedPaths.has(item.path))} onChange={(event) => toggleAllVisible(event.target.checked)} /></th><th className="sticky top-0 border-b border-border bg-card/80 p-3 text-left font-semibold">名称</th><th className="sticky top-0 w-20 border-b border-border bg-card/80 p-3 text-left font-semibold sm:w-24">大小</th><th className="sticky top-0 w-28 border-b border-border bg-card/80 p-3 text-left font-semibold">索引</th><th className="hidden sticky top-0 w-44 border-b border-border bg-card/80 p-3 text-left font-semibold sm:table-cell">修改时间</th><th className="sticky top-0 w-10 border-b border-border bg-card/80 p-3 text-right font-semibold" aria-label="操作" /></tr>
            </thead>
            <tbody>
              {listLoading && items.length === 0 && (
                <tr><td colSpan={6} className="p-8"><div className="text-center text-sm text-muted" role="status">正在读取文件列表…</div></td></tr>
              )}
              {!listLoading && displayItems.length === 0 && (
                <tr><td colSpan={6} className="p-8">
                  <div className="text-center text-muted text-sm">
                    {listError ? (
                      <>
                        <p className="text-danger">文件列表加载失败：{listError}</p>
                        <Button type="button" variant="outline" size="sm" className="mt-3" onClick={() => void load(pathRef.current, queryRef.current, searchModeRef.current)}>重试</Button>
                      </>
                    ) : <>
                      {dragOver ? <Upload className="mx-auto mb-2 size-7" /> : <FolderOpen className="mx-auto mb-2 size-7" />}
                      {dragOver ? "松开鼠标上传文件" : "目录为空 — 拖文件到这里，或点「上传」"}
                    </>}
                  </div>
                </td></tr>
              )}
              {displayItems.map((it) => (
                <tr key={it.path}
                    className={`cursor-pointer border-b border-border/60 transition-colors hover:bg-card/60 ${selected?.path === it.path || actionSelection?.path === it.path ? "bg-accent-soft" : ""}`}
                    onClick={() => openItem(it)}
                    onDoubleClick={() => it.is_dir && void openFolderPath(it.path)}>
                  <td className="w-10 px-3 py-2.5">
                    <input type="checkbox" aria-label={`选择 ${it.name}`} checked={selectedPaths.has(it.path)} onClick={(event) => event.stopPropagation()} onChange={(event) => togglePath(it.path, event.target.checked)} />
                  </td>
                  <td className="px-3 py-2.5">
                    <div className="flex items-center gap-2"><span className={it.is_dir ? "text-text" : "text-muted"}>{it.is_dir ? <FolderOpen className="size-4" /> : <File className="size-4" />}</span> <span className="min-w-0 break-words">{it.name}</span></div>
                    {activeSearchMode === "semantic" && !it.is_dir && (it.search_snippet || searchScoreLabel(it.search_score)) && (
                      <div className="mt-1 flex items-start gap-1.5 pl-5 text-[11px]">
                        {searchScoreLabel(it.search_score) && <Badge variant="secondary" className="shrink-0">相关度 {searchScoreLabel(it.search_score)}</Badge>}
                        {it.search_snippet && <span className="line-clamp-2 text-muted">{it.search_snippet}</span>}
                      </div>
                    )}
                  </td>
                  <td className="px-3 py-2.5 text-muted">{it.is_dir ? "—" : fmtSize(it.size)}</td>
                  <td className="px-3 py-2.5">{!it.is_dir && (
                    activeSearchMode === "semantic"
                      ? <Badge variant="default">已向量化</Badge>
                      : <Badge variant={it.index?.vectorized ? "default" : "outline"}>{indexStatusLabel(it.index)}</Badge>
                  )}</td>
                  <td className="hidden px-3 py-2.5 text-muted sm:table-cell">{fmtTime(it.mtime)}</td>
                  <td className="w-20 px-1.5 py-2.5 text-right">
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      className={it.favorite ? "text-warn" : "text-muted hover:text-warn"}
                      aria-label={it.favorite ? `取消收藏 ${it.name}` : `收藏 ${it.name}`}
                      title={it.favorite ? "取消收藏" : "收藏"}
                      onClick={(event) => { event.stopPropagation(); void toggleFavorite(it); }}
                    >
                      <Star className={`size-4 ${it.favorite ? "fill-current" : ""}`} aria-hidden="true" />
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      className="text-muted hover:text-text"
                      aria-label={`选择 ${it.name} 的操作`}
                      title="文件操作"
                      onClick={(event) => {
                        event.stopPropagation();
                        invalidateSelectionWork();
                        setSelected(null);
                        setActionSelection({ name: it.name, path: it.path, is_dir: it.is_dir });
                      }}
                    >
                      <MoreHorizontal className="size-4" aria-hidden="true" />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {disk && (
          <div className="text-muted text-xs">已用 {fmtSize(disk.used)} / {fmtSize(disk.total)} · 剩余 {fmtSize(disk.free)}</div>
        )}

        {action && (
          <div className="animate-slide-in rounded-md border border-text/25 bg-card/50 p-3 text-sm">
            <div className="font-semibold mb-2">
              {action.type === "rename" && `重命名: ${action.item.name}`}
              {action.type === "move" && `移动: ${action.item.name} → 目标目录`}
              {action.type === "copy" && `复制: ${action.item.name} → 目标目录`}
              {action.type === "delete" && `确认删除: ${action.item.name}（移入回收站）`}
              {action.type === "batch-move" && `移动选中的 ${selectedPaths.size} 项 → 目标目录`}
              {action.type === "batch-copy" && `复制选中的 ${selectedPaths.size} 项 → 目标目录`}
              {action.type === "batch-delete" && `确认删除选中的 ${selectedPaths.size} 项（移入回收站）`}
            </div>
            {action.type !== "delete" && action.type !== "batch-delete" ? (
              <div className="flex gap-2">
                <Input autoFocus value={actionValue}
                       onChange={(e) => setActionValue(e.target.value)}
                       onKeyDown={(e) => { if (e.key === "Enter") execAction(); if (e.key === "Escape") { setAction(null); setActionValue(""); } }}
                       placeholder={action.type === "rename" ? "新名称" : "目标目录（如 资料/合同）"}
                       className="flex-1" />
                <Button variant="default" size="sm" onClick={execAction}><FilePlus2 className="size-3.5" /> 确定</Button>
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
          <div className="flex items-center justify-between px-3 py-2.5 border-b border-border">
            <b className="flex items-center gap-2 text-sm"><ArchiveRestore className="size-4 text-muted" /> 回收站</b>
            <span className="flex gap-1.5">
              <Button variant="destructive" size="sm" onClick={doEmptyTrash}>清空</Button>
             <Button variant="ghost" size="sm" onClick={openTrash} aria-label="关闭回收站" title="关闭"><X className="size-4" /></Button>
            </span>
          </div>
          <div className="flex-1 overflow-auto p-2">
            {trashItems.length === 0 && (
              <div className="py-8 text-center text-muted text-xs">
                <ArchiveRestore className="mx-auto mb-2 size-7" />回收站为空
              </div>
            )}
            {trashItems.map((t) => (
              <div key={t.trash_id} className="flex items-center gap-2 border-b border-border/60 px-2.5 py-2.5 text-sm transition-colors hover:bg-card/60">
                <span className="text-muted">{t.is_dir ? <FolderOpen className="size-4" /> : <File className="size-4" />}</span>
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
            <Eye className="size-7 text-muted" />
            <div className="text-muted">点击左侧文件进行预览</div>
            <div className="text-muted text-xs">支持：文本 · Markdown · 图片 · PDF</div>
          </div>
        )}
        {selected && (
          <>
            <div className="flex justify-between items-center gap-2 px-3 py-2.5 border-b border-border">
              <b className="text-sm truncate flex-1" title={selected.path}>{selected.path.split("/").pop()}</b>
              <span className="flex min-w-0 items-center gap-1 overflow-x-auto">
                {selected.info && <Button variant={view === "preview" ? "default" : "ghost"} size="sm" onClick={() => setView("preview")} aria-label="预览" title="预览"><Eye className="size-4" /></Button>}
                {selected.info?.preview_kind === "text" && <Button variant={view === "content" ? "default" : "ghost"} size="sm" onClick={viewContent} aria-label="查看内容" title="查看内容"><FileText className="size-4" /></Button>}
                {selected.info && <Button variant={view === "details" ? "default" : "ghost"} size="sm" onClick={() => setView("details")} aria-label="文件详情" title="文件详情"><Info className="size-4" /></Button>}
                {selected.info && selected.info.preview_kind !== "image" && <Button variant="ghost" size="sm" className="lg:hidden" disabled={indexing !== null} onClick={() => void enqueueSelectedIndex("embed")} aria-label="预览文件向量化" title="向量化"><RefreshCw className={`size-4 ${indexing === "embed" ? "animate-spin" : ""}`} /></Button>}
                {selected.info?.preview_kind === "image" && <Button variant="ghost" size="sm" className="lg:hidden" disabled={indexing !== null} onClick={() => void enqueueSelectedIndex("vision")} aria-label="预览文件图片索引" title="视觉索引"><Eye className={`size-4 ${indexing === "vision" ? "animate-pulse" : ""}`} /></Button>}
                <Button variant="ghost" size="sm" className="lg:hidden" onClick={() => beginAction("rename")} aria-label="预览文件重命名" title="重命名"><Pencil className="size-4" /></Button>
                <Button variant="ghost" size="sm" className="lg:hidden" onClick={() => beginAction("move")} aria-label="预览文件移动" title="移动"><MoveRight className="size-4" /></Button>
                <Button variant="ghost" size="sm" className="lg:hidden" onClick={() => beginAction("copy")} aria-label="预览文件复制" title="复制到"><Copy className="size-4" /></Button>
                <Button variant="ghost" size="sm" className="lg:hidden text-danger" onClick={() => beginAction("delete")} aria-label="预览文件删除" title="删除"><Trash2 className="size-4" /></Button>
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
              {!contentLoading && view === "details" && selected.info && <FileDetails info={selected.info} onRestored={refreshSelectedFile} />}
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
