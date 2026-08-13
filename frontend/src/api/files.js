// 文件 API
import { api } from "./client.js";

export const listFiles = (path = "") => api(`/files?path=${encodeURIComponent(path)}`);
export const uploadFile = async (file, path = "") => {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`/api/v1/files/upload?path=${encodeURIComponent(path)}`, { method: "POST", body: form });
  if (!res.ok) throw new Error("上传失败");
  return res.json();
};
