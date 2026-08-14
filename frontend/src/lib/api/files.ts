// 文件 API（补全：info/raw 走封装，组件不再绕层）
import { api, apiPath } from "./client";

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
  preview_kind: "text" | "image" | "pdf" | "binary";
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

export const getFileInfo = (path: string) =>
  api<FileInfo>(`/files/info?path=${encodeURIComponent(path)}`);

export const fileRawUrl = (path: string) => apiPath(`/files/raw?path=${encodeURIComponent(path)}`);

export const fileDownloadUrl = (path: string) => apiPath(`/files/download?path=${encodeURIComponent(path)}`);
