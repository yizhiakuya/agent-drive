// 对话 API：聚合 + SSE 流式（含跨 chunk 缓冲）
import { api, authenticatedFetch, ApiError, apiErrorMessage } from "./client";
import type { FrontendCapability } from "../frontend-actions";
import type { PermissionMode } from "@/lib/permission";
import type { InlineImage } from "@/components/chat/chat-types";

export class ChatStreamError extends Error {
  readonly sessionId: string | null;

  constructor(message: string, sessionId: string | null = null) {
    super(message);
    this.name = "ChatStreamError";
    this.sessionId = sessionId;
  }
}

/** Match the backend ChatRequest JSON contract without leaking the UI camelCase shape. */
function serializeInlineImages(inlineImages: Pick<InlineImage, "name" | "mediaType" | "data">[]) {
  return inlineImages.map(({ name, mediaType, data }) => ({
    name,
    media_type: mediaType,
    data,
  }));
}

export const chat = (
  message: string,
  history: { role: string; content: string }[],
  sessionId: string | null,
  confirmations: Record<string, unknown>[] = [],
  thinkingLevel = "auto",
  frontendCapabilities: FrontendCapability[] = [],
  model = "",
  fileContext: string[] = [],
  permissionMode: PermissionMode = "auto",
  inlineImages: Pick<InlineImage, "name" | "mediaType" | "data">[] = [],
) => api("/chat", {
  method: "POST",
  body: JSON.stringify({
    message,
    history,
    session_id: sessionId,
    confirmations,
    thinking_level: thinkingLevel,
    frontend_capabilities: frontendCapabilities,
    model: model || undefined,
    file_context: fileContext,
    permission_mode: permissionMode,
    inline_images: serializeInlineImages(inlineImages),
  }),
});

export const cancelChatRun = (sessionId: string) =>
  api<{ cancelled: boolean }>(`/chat/${encodeURIComponent(sessionId)}/cancel`, { method: "POST" });

export const chatRunActive = (sessionId: string) =>
  api<{ active: boolean; status?: string; phase?: string; resumable?: boolean }>(`/chat/${encodeURIComponent(sessionId)}/active`, { cache: "no-store" });

/** 订阅服务端保留的当前会话 relay；没有活跃运行时会自然结束。 */
export async function chatReconnect(
  sessionId: string,
  onEvent: (event: string, data: Record<string, unknown>) => void,
  signal: AbortSignal,
): Promise<Record<string, unknown> | null> {
  const res = await authenticatedFetch(`/chat/${encodeURIComponent(sessionId)}/stream`, { signal });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, apiErrorMessage(body, res.statusText || `HTTP ${res.status}`));
  }
  return readSseResponse(res, onEvent);
}

export async function chatStream(
  message: string,
  history: { role: string; content: string }[],
  sessionId: string | null,
  confirmations: Record<string, unknown>[],
  onEvent: (event: string, data: Record<string, unknown>) => void,
  signal: AbortSignal,
  thinkingLevel = "auto",
  frontendCapabilities: FrontendCapability[] = [],
  model = "",
  fileContext: string[] = [],
  permissionMode: PermissionMode = "auto",
  inlineImages: Pick<InlineImage, "name" | "mediaType" | "data">[] = [],
  onSessionId?: (sessionId: string) => void,
): Promise<Record<string, unknown> | null> {
  const res = await authenticatedFetch("/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      message,
      history,
      session_id: sessionId,
      confirmations,
      thinking_level: thinkingLevel,
      frontend_capabilities: frontendCapabilities,
      model: model || undefined,
      file_context: fileContext,
      permission_mode: permissionMode,
      inline_images: serializeInlineImages(inlineImages),
    }),
    signal,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, apiErrorMessage(body, res.statusText || `HTTP ${res.status}`));
  }
  const headerSessionId = res.headers.get("X-Session-ID");
  if (headerSessionId?.trim()) onSessionId?.(headerSessionId.trim());
  return readSseResponse(res, onEvent);
}

async function readSseResponse(
  res: Response,
  onEvent: (event: string, data: Record<string, unknown>) => void,
): Promise<Record<string, unknown> | null> {
  if (!res.body) throw new Error(`HTTP ${res.status}: empty response body`);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let pendingCR = false;
  let donePayload: Record<string, unknown> | null = null;

  const dispatchBlock = (block: string) => {
    let event = "message";
    const dataLines: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith(":")) continue; // SSE comment/heartbeat
      if (line === "event" || line.startsWith("event:")) {
        const value = line === "event" ? "" : line.slice(6);
        event = value.trim();
      } else if (line === "data" || line.startsWith("data:")) {
        const value = line === "data" ? "" : line.slice(5);
        dataLines.push(value.startsWith(" ") ? value.slice(1) : value);
      }
    }
    if (dataLines.length === 0) return;
    const rawData = dataLines.join("\n");
    let data: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(rawData);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("SSE data must be a JSON object");
      }
      data = parsed as Record<string, unknown>;
    } catch (error) {
      const detail = rawData.length > 200 ? `${rawData.slice(0, 200)}…` : rawData;
      throw new Error(`SSE ${event || "message"} 数据格式错误: ${detail}`, { cause: error });
    }
    if (event === "error") {
      const message = typeof data.error === "string" && data.error.trim()
        ? data.error
        : "chat stream failed";
      const sessionId = typeof data.session_id === "string" && data.session_id.trim()
        ? data.session_id
        : null;
      throw new ChatStreamError(message, sessionId);
    }
    if (event === "done") donePayload = data;
    onEvent(event, data);
  };

  const consumeCompleteBlocks = () => {
    while (true) {
      const index = buffer.indexOf("\n\n");
      if (index < 0) return;
      const block = buffer.slice(0, index);
      buffer = buffer.slice(index + 2);
      if (block) dispatchBlock(block);
    }
  };

  const appendDecoded = (decoded: string, final = false) => {
    let text = pendingCR ? "\r" + decoded : decoded;
    pendingCR = false;
    // chunk 尾部 CR 可能是下一个 chunk 中 CRLF 的前半段，延迟一个 chunk 再归一化。
    if (!final && text.endsWith("\r")) {
      pendingCR = true;
      text = text.slice(0, -1);
    }
    buffer += text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
    consumeCompleteBlocks();
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    appendDecoded(decoder.decode(value, { stream: true }));
  }
  appendDecoded(decoder.decode(), true);
  if (buffer) dispatchBlock(buffer);
  return donePayload;
}
