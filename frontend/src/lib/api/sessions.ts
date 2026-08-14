// 会话 API
import { api } from "./client";

export const listSessions = () => api<{ sessions: { id: string; title: string; summary?: string }[] }>("/sessions");
export const getSession = (sid: string) => api(`/sessions/${sid}`);
export const deleteSession = (sid: string) => api(`/sessions/${sid}`, { method: "DELETE" });
export const summarizeSession = (sid: string) => api(`/sessions/${sid}/summarize`, { method: "POST" });
