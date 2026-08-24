export type ThinkingLevel = "auto" | "low" | "medium" | "high";
import type { PermissionMode } from "@/lib/permission";
export type { PermissionMode };

/** 当前浏览器消息气泡可展示的图片预览；previewUrl 只用于内存中的页面渲染。 */
export interface InlineImagePreview {
  id: string;
  name: string;
  mediaType: string;
  previewUrl: string;
  size: number;
}

export interface Message {
  type: "user" | "assistant" | "tool_step" | "system" | "context";
  content: string;
  images?: InlineImagePreview[];
  source?: string;
  contextKind?: string;
  contextTrust?: "system" | "instruction" | "user_data" | "untrusted_data";
  reasoning?: string;
  tool?: string;
  step?: number;
  arguments?: Record<string, unknown>;
  status?: "running" | "done" | "error";
  startedAt?: number;
  progressMessage?: string;
  progressPhase?: string;
  elapsedMs?: number;
  output?: string;
  parsed?: Record<string, unknown> | unknown[];
}

export interface PendingConfirmation {
  tool: string;
  arguments: Record<string, unknown>;
  nonce: string;
  ts: number;
  signature: string;
  message?: string;
}

/** 剪贴板图片的本轮内联载荷；data 只在当前请求中发送，不写入文件或会话。 */
export interface InlineImage extends InlineImagePreview {
  mediaType: string;
  data: string;
}

export interface ChatHistoryMessage {
  role: "user" | "assistant";
  content: string;
}

export interface ContextUsage {
  used: number;
  total: number;
  percent: number;
  input?: number;
  output?: number;
  estimated?: boolean;
}
