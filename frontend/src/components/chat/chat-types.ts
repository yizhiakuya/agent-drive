export type ThinkingLevel = "auto" | "low" | "medium" | "high";
import type { PermissionMode } from "@/lib/permission";
export type { PermissionMode };

export interface Message {
  type: "user" | "assistant" | "tool_step" | "system" | "context";
  content: string;
  source?: string;
  contextKind?: string;
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
export interface InlineImage {
  id: string;
  name: string;
  mediaType: string;
  data: string;
  previewUrl: string;
  size: number;
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
