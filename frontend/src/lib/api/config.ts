// 配置 API（状态/LLM/embeddings——设置页与 Onboarding 共用）
import { api, LLMConfigPayload } from "./client";

// Boot status must bypass the short GET cache so a migrated server config is visible immediately.
export const getStatus = () => api("/config/status", { cache: "no-store" });
export const getConfig = () => api<{
  configured: boolean;
  llm?: { type: string; base_url: string; model: string; api_key_masked: string };
  embeddings?: { provider: string; base_url: string; model: string; api_key_masked: string } | null;
  preferences?: Record<string, string>;
}>("/config");
export const configureLLM = (cfg: LLMConfigPayload) => api("/config", { method: "POST", body: JSON.stringify(cfg) });

async function revealApiKey(path: string): Promise<string> {
  const response = await api<{ api_key: unknown }>(path, { method: "POST", cache: "no-store" });
  if (typeof response.api_key !== "string" || response.api_key.length === 0) {
    throw new Error("服务端未返回已保存的 API Key");
  }
  return response.api_key;
}

/** 由 Web 会话按需读取已保存的 LLM API key；响应不会进入 GET 缓存。 */
export const revealLlmApiKey = () => revealApiKey("/config/api-key/reveal");

/** 由 Web 会话按需读取已保存的 embedding API key；响应不会进入 GET 缓存。 */
export const revealEmbeddingApiKey = () => revealApiKey("/config/embeddings/api-key/reveal");
export const listModels = (body: { type: string; base_url: string; api_key: string }) =>
  api<{ ok: boolean; models?: string[]; error?: string }>("/config/models", {
    method: "POST",
    body: JSON.stringify(body),
  });
export const saveEmbeddings = (body: Record<string, string>) =>
  api("/config/embeddings", { method: "PUT", body: JSON.stringify(body) });

export interface VisionConfigView {
  configured: boolean;
  provider: string;
  base_url: string;
  model: string;
  api_key_masked: string;
}

/**
 * 读取当前 owner 的视觉模型配置状态。
 * @returns provider、地址、模型和脱敏 key 前缀。
 */
export const getVisionConfig = () => api<VisionConfigView>("/config/vision");

/** 由 Web 会话按需读取已保存的视觉模型 API key；响应不会进入 GET 缓存。 */
export const revealVisionApiKey = () => revealApiKey("/config/vision/api-key/reveal");

/**
 * 保存并测试 OpenAI 兼容视觉模型配置。
 * @param body provider、地址、模型和可选 API key。
 * @returns 保存结果与连接测试诊断。
 */
export const saveVision = (body: Record<string, string>) =>
  api("/config/vision", { method: "PUT", body: JSON.stringify(body) });

/**
 * 查询视觉 provider 的模型目录，供图片识别模型选择器使用。
 * @param body provider、地址和可选 API key。
 * @returns 视觉模型 ID 列表或安全错误信息。
 */
export const listVisionModels = (body: { provider: string; base_url: string; api_key: string }) =>
  api<{ ok: boolean; models?: string[]; error?: string }>("/config/vision/models", {
    method: "POST",
    body: JSON.stringify(body),
  });
