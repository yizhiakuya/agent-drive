// API 基座：fetch 封装（唯一真相源）
// web 生产(out 静态托管)同源 /api/v1（HttpOnly Cookie 鉴权）；dev(next dev 3000)可配 NEXT_PUBLIC_API_BASE 直连后端
// 原生 App(Capacitor)：启动时经 ServerConfig 插件读取扫码配置的服务器地址与设备令牌（Bearer 鉴权）
import { Capacitor } from "@capacitor/core";
import { ServerConfig } from "@/lib/native/server-config";
import { EV } from "@/lib/events";

let V1 = process.env.NEXT_PUBLIC_API_BASE || "/api/v1";
let baseReady = !Capacitor.isNativePlatform();
let basePromise: Promise<void> | null = null;
let deviceToken: string | null = null;
let credentialGeneration = 0;
let cacheGeneration = 0;

const GET_TTL_MS = 15_000;
const cache = new Map<string, { at: number; value: unknown }>();
const inflight = new Map<string, Promise<unknown>>();

type RequestContext = {
  base: string;
  token: string | null;
  generation: number;
};

function snapshotContext(): RequestContext {
  return { base: V1, token: deviceToken, generation: credentialGeneration };
}

function isCurrentContext(context: RequestContext): boolean {
  return (
    context.generation === credentialGeneration
    && context.base === V1
    && context.token === deviceToken
  );
}

function cacheKey(path: string, context: RequestContext, getGeneration: number): string {
  return `${context.base}\n${context.generation}\n${getGeneration}\n${path}`;
}

function clearGetState() {
  cache.clear();
  // 让失效前启动的 GET 不能复用/回填当前缓存；身份代次单独管理。
  cacheGeneration += 1;
  // 释放旧 key 的 inflight 索引：旧 Promise 由调用方持有并自行 settle，
  // generation 已保证新调用不可能命中这些 key（不取消网络，只释放 Map 项）。
  inflight.clear();
}

function markCredentialChanged() {
  credentialGeneration += 1;
  clearGetState();
}

export function setDeviceToken(t: string | null) {
  if (deviceToken !== t) {
    deviceToken = t;
    markCredentialChanged();
  }
}

export function getDeviceToken() {
  return deviceToken;
}

/** App 启动时调用一次（原生端从扫码配置解析服务器地址与设备令牌）。幂等。 */
export async function ensureBase(): Promise<void> {
  if (baseReady) return;
  if (basePromise) return basePromise;
  basePromise = (async () => {
    const oldBase = V1;
    const oldToken = deviceToken;
    try {
      const { server } = await ServerConfig.getServer();
      if (server) V1 = `${server.replace(/\/+$/, "")}/api/v1`;
      if (Capacitor.isNativePlatform()) {
        const { token } = await ServerConfig.getDeviceToken();
        deviceToken = token || null;
      }
    } catch (error) {
      if (Capacitor.isNativePlatform()) {
        // 原生安全存储失败必须显式暴露；绝不能假装使用默认地址继续运行。
        V1 = oldBase;
        deviceToken = oldToken;
        throw error;
      }
      // web 环境保持默认同源地址。
    }
    if (V1 !== oldBase || deviceToken !== oldToken) markCredentialChanged();
    baseReady = true;
  })().finally(() => {
    basePromise = null;
  });
  return basePromise;
}

function headersFor(token: string | null): Record<string, string> {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function apiErrorMessage(body: unknown, fallback: string): string {
  if (!body || typeof body !== "object" || !("detail" in body)) return fallback;
  const detail = (body as { detail?: unknown }).detail;
  if (typeof detail === "string") return detail;
  if (detail == null) return fallback;
  try {
    return JSON.stringify(detail) ?? fallback;
  } catch {
    return fallback;
  }
}

export function apiPath(path: string): string {
  return `${V1}${path}`;
}

/** Internal upload adapters need the current API origin without exposing cache state. */
export function requestBase(): string {
  return V1;
}

/** Invalidate GET dedupe/cache around streamed multipart writes. */
export function invalidateApiCache(): void {
  clearGetState();
}

/** Prevent a late upload response from being treated as the current identity. */
export function isCurrentRequest(base: string, token: string | null): boolean {
  return base === V1 && token === deviceToken;
}

// GET 请求去重：同 base/凭据代次/缓存代次下并发只发一次，成功后短 TTL 缓存（15s）。
// 任何写操作（非 GET）都会清空缓存，避免改名/删除后拿到陈旧列表。
async function doFetch<T = unknown>(
  path: string,
  options: RequestInit,
  context: RequestContext = snapshotContext(),
): Promise<T> {
  const { headers: optionHeaders, ...rest } = options;
  const mergedHeaders = new Headers({
    "Content-Type": "application/json",
    ...headersFor(context.token),
  });
  new Headers(optionHeaders).forEach((value, key) => mergedHeaders.set(key, value));
  const res = await fetch(`${context.base}${path}`, {
    ...rest,
    headers: mergedHeaders,
    credentials: "include",
  });
  if (res.status === 401 && isCurrentContext(context) && typeof window !== "undefined") {
    // 只让当前身份的 401 触发退出；旧 token 的迟到响应不能踢掉新身份。
    window.dispatchEvent(new CustomEvent(EV.unauthorized));
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, apiErrorMessage(body, res.statusText));
  }
  return res.json() as Promise<T>;
}

export async function api<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
  await ensureBase();
  const method = (options.method ?? "GET").toUpperCase();
  if (method !== "GET") {
    clearGetState(); // 写开始前清旧缓存。
    const context = snapshotContext();
    try {
      return await doFetch<T>(path, options, context);
    } finally {
      // 写完成后再清一次，避免写进行中 GET 到旧状态并缓存。
      if (isCurrentContext(context)) clearGetState();
    }
  }
  const context = snapshotContext();
  const getGeneration = cacheGeneration;
  const key = cacheKey(path, context, getGeneration);
  if (options.cache === "no-store") {
    return doFetch<T>(path, options, context);
  }
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < GET_TTL_MS) {
    return hit.value as T;
  }
  const running = inflight.get(key);
  if (running) {
    return running as Promise<T>;
  }
  const p = doFetch<T>(path, options, context)
    .then((v) => {
      // An identity/base switch may have happened while the old request was in flight.
      if (isCurrentContext(context) && getGeneration === cacheGeneration) {
        cache.set(key, { at: Date.now(), value: v });
      }
      return v;
    })
    .finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

/** Raw authenticated fetch for streaming and multipart endpoints. */
export async function authenticatedFetch(path: string, options: RequestInit = {}): Promise<Response> {
  await ensureBase();
  const method = (options.method ?? "GET").toUpperCase();
  if (method !== "GET") clearGetState();
  const context = snapshotContext();
  const { headers: optionHeaders, ...rest } = options;
  const mergedHeaders = new Headers(headersFor(context.token));
  new Headers(optionHeaders).forEach((value, key) => mergedHeaders.set(key, value));
  try {
    const res = await fetch(`${context.base}${path}`, {
      ...rest,
      headers: mergedHeaders,
      credentials: "include",
    });
    if (res.status === 401 && isCurrentContext(context) && typeof window !== "undefined") {
      window.dispatchEvent(new CustomEvent(EV.unauthorized));
    }
    return res;
  } finally {
    if (method !== "GET" && isCurrentContext(context)) {
      // 成功、HTTP 错误或网络异常都结束本次写窗口；再次失效并发 GET 旧快照。
      clearGetState();
    }
  }
}

export interface LLMConfigPayload {
  type: string;
  base_url: string;
  api_key: string;
  model: string;
}
