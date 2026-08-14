// 文件 API（补全：info/raw 走封装，组件不再绕层）
import { api, apiPath, getDeviceToken } from "./client";

export interface FileItem {
  name: string;
  path: string;
  is_dir: boolean;
  size: number;
  mtime?: number;
}

export interface FileInfo {
  path: string;
  name: string;
  size: number;
  modified: number;
  preview_kind: "text" | "image" | "video" | "audio" | "pdf" | "binary";
  snippet: string | null;
  indexed: { method: string; chars: number } | null;
}

export const listFiles = (path = "") =>
  api<{ path: string; items: FileItem[]; disk: { used: number; total: number; free: number } | null }>(
    `/files?path=${encodeURIComponent(path)}`,
  );

export const uploadFile = async (file: File, path = "") => {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(apiPath(`/files/upload?path=${encodeURIComponent(path)}`), {
    method: "POST",
    body: form,
  });
  if (!res.ok) throw new Error("上传失败");
  return res.json();
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
export const listTrash = () => api<{ items: { path: string; deleted_at: number; size: number; is_dir: boolean }[] }>("/files/trash");
export const restoreFromTrash = (path: string) =>
  api(`/files/trash/restore?path=${encodeURIComponent(path)}`, { method: "POST" });
export const emptyTrash = () => api("/files/trash/empty", { method: "POST" });

export const getFileInfo = (path: string) =>
  api<FileInfo>(`/files/info?path=${encodeURIComponent(path)}`);

// 媒体元素（img/video/audio）无法携带 Cookie/Header：App 端经查询参数附设备令牌
const mediaQuery = (path: string) => {
  const t = getDeviceToken();
  return `path=${encodeURIComponent(path)}${t ? `&token=${encodeURIComponent(t)}` : ""}`;
};
export const fileRawUrl = (path: string) => apiPath(`/files/raw?${mediaQuery(path)}`);

export const fileDownloadUrl = (path: string) => apiPath(`/files/download?${mediaQuery(path)}`);
