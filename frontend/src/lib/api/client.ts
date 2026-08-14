// API 基座：fetch 封装（唯一真相源，无死导出）
// web 生产(out 静态托管)同源 /api/v1；dev(next dev 3000)可配 NEXT_PUBLIC_API_BASE 直连后端
// 原生 App(Capacitor)：启动时经 ServerConfig 插件读取扫码配置的服务器地址
import { Capacitor } from "@capacitor/core";
import { ServerConfig } from "@/lib/native/server-config";

export let V1 = process.env.NEXT_PUBLIC_API_BASE || "/api/v1";
let baseReady = !Capacitor.isNativePlatform();

/** App 启动时调用一次（原生端从扫码配置解析服务器地址）。幂等。 */
export async function ensureBase(): Promise<void> {
  if (baseReady) return;
  try {
    const { server } = await ServerConfig.getServer();
    if (server) V1 = `${server.replace(/\/+$/, "")}/api/v1`;
  } catch {
    // 保持默认值
  }
  baseReady = true;
}

export function apiPath(path: string): string {
  return `${V1}${path}`;
}

export async function api<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  await ensureBase();
  const res = await fetch(`${V1}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { detail?: string }).detail || res.statusText);
  }
  return res.json() as Promise<T>;
}

export interface LLMConfigPayload {
  type: string;
  base_url: string;
  api_key: string;
  model: string;
  temperature?: number;
}

export interface StatusPayload {
  configured: boolean;
  provider_types?: Record<string, string>;
  current?: { type: string; base_url: string; model: string } | null;
  preferences?: Record<string, string>;
}
