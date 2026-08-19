export type ThinkingLevel = "auto" | "low" | "medium" | "high";

export interface Message {
  type: "user" | "assistant" | "tool_step" | "system";
  content: string;
  reasoning?: string;
  tool?: string;
  arguments?: Record<string, unknown>;
  status?: "running" | "done" | "error";
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

export interface ChatHistoryMessage {
  role: "user" | "assistant";
  content: string;
}

export interface ContextUsage {
  used: number;
  total: number;
  percent: number;
}
