// 文件 API（补全：info/raw 走封装，组件不再绕层）
import { api, apiPath, authenticatedFetch, ApiError, apiErrorMessage, getDeviceToken } from "./client";

export interface FileIndexStatus {
  text_indexed: boolean;
  vectorized: boolean;
  vector_status: "not_indexed" | "indexed" | "pending" | "partial" | "vectorized" | "stale" | "not_configured";
  chunk_count: number;
  vector_chunks: number;
  stored_vector_chunks: number;
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
}

/** 文件列表支持的搜索模式。 */
export type FileSearchMode = "name" | "semantic";

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
export const listFiles = (path = "", query = "", mode: FileSearchMode = "name") => {
  const params = new URLSearchParams({ path });
  if (query) params.set("q", query);
  if (mode === "semantic" && query.trim()) params.set("mode", mode);
  return api<{
    path: string;
    query?: string;
    mode?: FileSearchMode;
    items: FileItem[];
    disk: { used: number; total: number; free: number } | null;
  }>(`/files?${params.toString()}`);
};

export const uploadFile = async (file: File, path = ""): Promise<UploadResponse> => {
  const form = new FormData();
  form.append("file", file);
  const res = await authenticatedFetch(`/files/upload?path=${encodeURIComponent(path)}`, {
    method: "POST",
    body: form,
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
