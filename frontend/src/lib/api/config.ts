// 配置 API（状态/LLM/embeddings——设置页与 Onboarding 共用）
import { api, LLMConfigPayload } from "./client";

export const getStatus = () => api("/status");
export const getConfig = () => api<{
  configured: boolean;
  llm?: { type: string; base_url: string; model: string; api_key_masked: string };
  embeddings?: { provider: string; base_url: string; model: string; api_key_masked: string } | null;
  preferences?: Record<string, string>;
}>("/config");
export const configureLLM = (cfg: LLMConfigPayload) => api("/config", { method: "POST", body: JSON.stringify(cfg) });
export const listModels = (body: { type: string; base_url: string; api_key: string }) =>
  api<{ ok: boolean; models?: string[]; error?: string }>("/config/models", {
    method: "POST",
    body: JSON.stringify(body),
  });
export const saveEmbeddings = (body: Record<string, string>) =>
  api("/config/embeddings", { method: "PUT", body: JSON.stringify(body) });
