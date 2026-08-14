// API 基座：fetch 封装（唯一真相源，无死导出）
// web 生产(out 静态托管)同源 /api/v1（HttpOnly Cookie 鉴权）；dev(next dev 3000)可配 NEXT_PUBLIC_API_BASE 直连后端
// 原生 App(Capacitor)：启动时经 ServerConfig 插件读取扫码配置的服务器地址与设备令牌（Bearer 鉴权）
import { Capacitor } from "@capacitor/core";
import { ServerConfig } from "@/lib/native/server-config";

export let V1 = process.env.NEXT_PUBLIC_API_BASE || "/api/v1";
let baseReady = !Capacitor.isNativePlatform();
let deviceToken: string | null = null;

export function setDeviceToken(t: string | null) {
  deviceToken = t;
}
export function getDeviceToken() {
  return deviceToken;
}

/** App 启动时调用一次（原生端从扫码配置解析服务器地址与设备令牌）。幂等。 */
export async function ensureBase(): Promise<void> {
  if (baseReady) return;
  try {
    const { server } = await ServerConfig.getServer();
    if (server) V1 = `${server.replace(/\/+$/, "")}/api/v1`;
    if (Capacitor.isNativePlatform()) {
      const { token } = await ServerConfig.getDeviceToken();
      deviceToken = token || null;
    }
  } catch {
    // 保持默认值
  }
  baseReady = true;
}

/** 原生 App：所有 API 请求带设备令牌（无 Cookie 的跨域场景）。 */
export function authHeaders(): Record<string, string> {
  return deviceToken ? { Authorization: `Bearer ${deviceToken}` } : {};
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function apiPath(path: string): string {
  return `${V1}${path}`;
}

// GET 请求去重：同 key 并发只发一次（in-flight 合并），成功后短 TTL 缓存（15s）。
// 任何写操作（非 GET）都会清空缓存，避免改名/删除后拿到陈旧列表。
const GET_TTL_MS = 15_000;
const cache = new Map<string, { at: number; value: unknown }>();
const inflight = new Map<string, Promise<unknown>>();

async function doFetch<T = unknown>(path: string, options: RequestInit): Promise<T> {
  const res = await fetch(`${V1}${path}`, {
    headers: { "Content-Type": "application/json", ...authHeaders() },
    credentials: "include",
    ...options,
  });
  if (res.status === 401 && typeof window !== "undefined") {
    // 会话过期：通知应用层回登录页
    window.dispatchEvent(new CustomEvent("agent-drive:unauthorized"));
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, (body as { detail?: string }).detail || res.statusText);
  }
  return res.json() as Promise<T>;
}

export async function api<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  await ensureBase();
  const method = (options.method ?? "GET").toUpperCase();
  if (method !== "GET") {
    cache.clear(); // 写操作：清空 GET 缓存，保证下次读取是新鲜数据
    return doFetch<T>(path, options);
  }
  const hit = cache.get(path);
  if (hit && Date.now() - hit.at < GET_TTL_MS) {
    return hit.value as T;
  }
  const running = inflight.get(path);
  if (running) {
    return running as Promise<T>;
  }
  const p = doFetch<T>(path, options)
    .then((v) => {
      cache.set(path, { at: Date.now(), value: v });
      return v;
    })
    .finally(() => inflight.delete(path));
  inflight.set(path, p);
  return p;
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
