// 会话 API
import { api } from "./client.js";

export const listSessions = () => api("/sessions");
export const getSession = (sid) => api(`/sessions/${sid}`);
export const deleteSession = (sid) => api(`/sessions/${sid}`, { method: "DELETE" });
export const summarizeSession = (sid) => api(`/sessions/${sid}/summarize`, { method: "POST" });
