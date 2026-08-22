// 文件 API（补全：info/raw 走封装，组件不再绕层）
import { api, apiPath, authenticatedFetch, ApiError, apiErrorMessage, ensureBase, getDeviceToken, invalidateApiCache, isCurrentRequest, requestBase } from "./client";
import { EV } from "@/lib/events";

export interface FileIndexStatus {
  text_indexed: boolean;
  /** 图片视觉描述是否已写入索引。 */
  vision_indexed?: boolean;
  /** 当前文件包含的向量文档类型；mixed 表示文本与视觉描述并存。 */
  vector_type?: "text" | "vision" | "mixed" | null;
  vectorized: boolean;
  vector_status: "not_indexed" | "indexed" | "pending" | "partial" | "vectorized" | "stale" | "not_configured";
  chunk_count: number;
  text_chunk_count?: number;
  vision_chunk_count?: number;
  vector_chunks: number;
  text_vector_chunks?: number;
  vision_vector_chunks?: number;
  stored_vector_chunks: number;
  text_stored_vector_chunks?: number;
  vision_stored_vector_chunks?: number;
  embedding_configured: boolean;
  /** 文件信息接口可选返回的索引详情，旧后端响应中不存在此字段。 */
  detail?: FileIndexDetail | null;
}

/**
 * GET /files/info 返回的文档索引详情。
 *
 * <p>该对象不包含 API key；embedding_fingerprint 只标识配置，不是凭据。</p>
 */
export interface FileIndexDetail {
  available: boolean;
  document_id: string | number | null;
  source_revision: number | string | null;
  extractor_version: string | null;
  vector_type?: "text" | "vision" | "mixed" | null;
  updated: string | number | null;
  embedding_provider: string | null;
  embedding_model: string | null;
  embedding_fingerprint: string | null;
  truncated: boolean;
  chunks: FileIndexChunk[];
}

/** 文档索引详情中的一个文本段及其向量版本状态。 */
export interface FileIndexChunk {
  id: string | number;
  vector_type?: "text" | "vision" | null;
  index: number;
  chunk_version: string | null;
  source_revision: number | string | null;
  content: string;
  content_length: number;
  stored_vector: boolean;
  current_vector: boolean;
  embedding_fingerprint: string | null;
}

export interface FileItem {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  mtime?: number;
  index?: FileIndexStatus;
  /** 语义搜索返回的最佳 chunk 相关度，范围通常为 0 到 1。 */
  search_score?: number | null;
  /** 语义搜索返回的最佳 chunk 文本片段。 */
  search_snippet?: string | null;
  /** 最佳匹配 chunk 在文档中的序号。 */
  search_chunk_index?: number | null;
  /** 语义命中的向量来源：普通文本或视觉描述。 */
  vector_type?: "text" | "vision" | null;
  /** owner-scoped 收藏标记。 */
  favorite?: boolean;
  /** 最近访问列表返回的 Unix 秒时间戳。 */
  last_accessed?: number | string | null;
  access_count?: number | null;
}

/** 文件列表支持的搜索模式。 */
export type FileSearchMode = "name" | "semantic";
export type FileTypeFilter = "all" | "file" | "folder" | "image" | "video" | "audio" | "pdf" | "text";

export interface FileListFilters {
  type?: FileTypeFilter;
  modifiedAfter?: number;
  modifiedBefore?: number;
  minScore?: number;
  limit?: number;
}

export interface FileInfo {
  path: string;
  name: string;
  size: number;
  modified: number;
  revision?: number;
  content_type?: string;
  preview_kind: "text" | "image" | "video" | "audio" | "pdf" | "binary";
  snippet: string | null;
  indexed: FileIndexStatus | null;
}

export interface FileVersion {
  version_id: string;
  source_revision: number | string;
  size: number;
  content_md5?: string | null;
  content_sha256?: string | null;
  created_at: number | string;
}

export interface FileContent {
  path: string;
  name: string;
  content: string;
  encoding: string;
  size: number;
  truncated: boolean;
}

interface UploadResponse {
  uploaded: { path: string; size: number; deduped?: boolean };
  indexed: { task_id: string; status: string } | null;
}

/**
 * 列出目录内容或查询文件。
 * 语义模式只在有查询词时发送到后端；空查询继续走普通目录列表，避免无意义的 provider 请求。
 *
 * @param path 目录或搜索根目录。
 * @param query 名称/路径关键词或自然语言问题。
 * @param mode 名称/路径模式或语义模式。
 * @returns 文件条目、当前模式和磁盘用量。
 */
export const listFiles = (path = "", query = "", mode: FileSearchMode = "name", filters: FileListFilters = {}) => {
  const params = new URLSearchParams({ path });
  if (query) params.set("q", query);
  if (mode === "semantic" && query.trim()) params.set("mode", mode);
  if (filters.type && filters.type !== "all") params.set("type", filters.type);
  if (typeof filters.modifiedAfter === "number") params.set("modified_after", String(filters.modifiedAfter));
  if (typeof filters.modifiedBefore === "number") params.set("modified_before", String(filters.modifiedBefore));
  if (typeof filters.minScore === "number") params.set("min_score", String(filters.minScore));
  if (typeof filters.limit === "number") params.set("limit", String(filters.limit));
  return api<{
    path: string;
    query?: string;
    mode?: FileSearchMode;
    /** 新版后端在结果截断时返回；旧后端缺失时由列表层按上限兼容推断。 */
    has_more?: boolean;
    items: FileItem[];
    disk: { used: number; total: number; free: number } | null;
  }>(`/files?${params.toString()}`);
};

export const listFavorites = (limit = 100) =>
  api<{ path: string; mode?: "favorites"; items: FileItem[]; has_more?: boolean; disk: { used: number; total: number; free: number } | null }>(`/files/favorites?limit=${limit}`);

export const listRecent = (limit = 100) =>
  api<{ path: string; mode?: "recent"; items: FileItem[]; has_more?: boolean; disk: { used: number; total: number; free: number } | null }>(`/files/recent?limit=${limit}`);

export const listVersions = (path: string, limit = 20) =>
  api<{ path: string; current_revision?: number | string; items: FileVersion[]; has_more?: boolean }>(
    `/files/versions?path=${encodeURIComponent(path)}&limit=${limit}`,
  );

export const restoreVersion = (path: string, versionId: string) =>
  api<{ restored: { path: string; size: number; deduped?: boolean }; version_id: string }>(
    `/files/versions/restore?path=${encodeURIComponent(path)}&version_id=${encodeURIComponent(versionId)}`,
    { method: "POST" },
  );

export const setFavorite = (path: string, favorite: boolean) =>
  api<{ path: string; favorite: boolean }>(`/files/favorites?path=${encodeURIComponent(path)}`, { method: favorite ? "POST" : "DELETE" });

export const uploadFile = async (
  file: File,
  path = "",
  onProgress?: (progress: number) => void,
  signal?: AbortSignal,
): Promise<UploadResponse> => {
  const form = new FormData();
  form.append("file", file);
  if (onProgress && typeof XMLHttpRequest !== "undefined") {
    await ensureBase();
    const base = requestBase();
    const token = getDeviceToken();
    invalidateApiCache();
    onProgress(0);
    try {
      return await new Promise<UploadResponse>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        let settled = false;
        const finish = (callback: () => void) => {
          if (settled) return;
          settled = true;
          callback();
        };
        xhr.open("POST", `${base}/files/upload?path=${encodeURIComponent(path)}`, true);
        xhr.withCredentials = true;
        if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
        xhr.upload.onprogress = (event) => {
          if (event.lengthComputable) onProgress(Math.max(0, Math.min(99, Math.round((event.loaded / event.total) * 100))));
        };
        xhr.onload = () => {
          let body: unknown = {};
          try { body = xhr.responseText ? JSON.parse(xhr.responseText) : {}; } catch { /* handled as API error */ }
          if (xhr.status === 401 && isCurrentRequest(base, token) && typeof window !== "undefined") {
            window.dispatchEvent(new CustomEvent(EV.unauthorized));
          }
          if (xhr.status < 200 || xhr.status >= 300) {
            finish(() => reject(new ApiError(xhr.status, apiErrorMessage(body, xhr.statusText || `HTTP ${xhr.status}`))));
            return;
          }
          onProgress(100);
          finish(() => resolve(body as UploadResponse));
        };
        xhr.onerror = () => finish(() => reject(new Error("上传网络失败")));
        xhr.onabort = () => finish(() => reject(new DOMException("上传已取消", "AbortError")));
        if (signal) {
          if (signal.aborted) { xhr.abort(); return; }
          signal.addEventListener("abort", () => xhr.abort(), { once: true });
        }
        xhr.send(form);
      });
    } finally {
      if (isCurrentRequest(base, token)) invalidateApiCache();
    }
  }
  const res = await authenticatedFetch(`/files/upload?path=${encodeURIComponent(path)}`, {
    method: "POST",
    body: form,
    signal,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, apiErrorMessage(body, res.statusText || `HTTP ${res.status}`));
  }
  return res.json() as Promise<UploadResponse>;
};

export const mkdir = (path: string) =>
  api(`/files/mkdir?path=${encodeURIComponent(path)}`, { method: "POST" });

export const renameFile = (src: string, dst: string) =>
  api(`/files/rename?src=${encodeURIComponent(src)}&dst=${encodeURIComponent(dst)}`, { method: "POST" });
export const moveFile = (src: string, dstDir: string, overwrite = false) =>
  api(`/files/move?src=${encodeURIComponent(src)}&dst_dir=${encodeURIComponent(dstDir)}&overwrite=${overwrite}`, { method: "POST" });
export const copyFile = (src: string, dst: string, overwrite = false) =>
  api(`/files/copy?src=${encodeURIComponent(src)}&dst=${encodeURIComponent(dst)}&overwrite=${overwrite}`, { method: "POST" });
export const deleteToTrash = (path: string) =>
  api(`/files/delete?path=${encodeURIComponent(path)}`, { method: "POST" });
export const listTrash = () => api<{ items: { path: string; trash_id: string; deleted_at: number; size: number; is_dir: boolean }[] }>("/files/trash");
export const restoreFromTrash = (trashId: string) =>
  api(`/files/trash/restore?trash_id=${encodeURIComponent(trashId)}`, { method: "POST" });
export const emptyTrash = () => api("/files/trash/empty", { method: "POST" });

export const getFileInfo = (path: string) =>
  api<FileInfo>(`/files/info?path=${encodeURIComponent(path)}`);

/**
 * 读取文本文件的完整内容，服务端会应用最大字节限制并返回截断标志。
 *
 * @param path 用户相对文件路径。
 * @param maxBytes 前端请求的最大 UTF-8 字节数。
 * @returns 文本内容和读取元数据。
 */
export const getFileContent = (path: string, maxBytes = 2 * 1024 * 1024) =>
  api<FileContent>(`/files/content?path=${encodeURIComponent(path)}&max_bytes=${maxBytes}`);

// 媒体元素（img/video/audio）无法携带 Cookie/Header：App 端经查询参数附设备令牌
const mediaQuery = (path: string) => {
  const t = getDeviceToken();
  return `path=${encodeURIComponent(path)}${t ? `&token=${encodeURIComponent(t)}` : ""}`;
};
export const fileRawUrl = (path: string) => apiPath(`/files/raw?${mediaQuery(path)}`);

export const fileDownloadUrl = (path: string) => apiPath(`/files/download?${mediaQuery(path)}`);
