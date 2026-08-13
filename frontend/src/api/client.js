// API 封装
const BASE = "";  // vite 代理 /api → :8000

const V1 = "/api/v1";

export async function api(path, options = {}) {
  const res = await fetch(`${BASE}${V1}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.detail || res.statusText);
  }
  return res.json();
}

export const getStatus = () => api("/api/status");
export const configureLLM = (cfg) => api("/api/config", {
  method: "POST",
  body: JSON.stringify(cfg),
});
export const testLLM = (cfg) => api("/api/config/test", {
  method: "POST",
  body: JSON.stringify(cfg),
});
export const chat = (message, history, sessionId, confirmations = []) => api("/api/chat", {
  method: "POST",
  body: JSON.stringify({ message, history, session_id: sessionId, confirmations }),
});
export const listFiles = (path = "") => api(`/api/files?path=${encodeURIComponent(path)}`);
export const listSessions = () => api("/api/sessions");
export const getSession = (sid) => api(`/api/sessions/${sid}`);
export const deleteSession = (sid) => api(`/api/sessions/${sid}`, { method: "DELETE" });
export const summarizeSession = (sid) => api(`/api/sessions/${sid}/summarize`, { method: "POST" });
export const uploadFile = async (file, path = "") => {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`${BASE}/api/files/upload?path=${encodeURIComponent(path)}`, {
    method: "POST",
    body: form,
  });
  if (!res.ok) throw new Error("上传失败");
  return res.json();
};
