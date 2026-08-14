// API 基座：fetch 封装（唯一真相源，无死导出）
// 生产(out 静态托管)同源 /api/v1；dev(next dev 3000)可配 NEXT_PUBLIC_API_BASE 直连后端
export const V1 = process.env.NEXT_PUBLIC_API_BASE || "/api/v1";

export function apiPath(path: string): string {
  return `${V1}${path}`;
}

export async function api<T = unknown>(path: string, options: RequestInit = {}): Promise<T> {
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
