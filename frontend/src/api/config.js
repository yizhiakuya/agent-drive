// 配置 API（Onboarding）
import { api } from "./client.js";

export const getStatus = () => api("/status");
export const configureLLM = (cfg) => api("/config", { method: "POST", body: JSON.stringify(cfg) });
export const testLLM = (cfg) => api("/config/test", { method: "POST", body: JSON.stringify(cfg) });
