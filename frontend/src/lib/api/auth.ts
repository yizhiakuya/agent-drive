// 认证 API：配对码（扫码即授权）等
import { api } from "./client";

/** 生成一次性配对码（5 分钟），二维码携带。需已登录（web 会话）。 */
export const getPairing = () => api<{ code: string; expires_in: number }>("/auth/pairing", { method: "POST" });
