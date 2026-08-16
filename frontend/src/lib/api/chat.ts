// 对话 API：聚合 + SSE 流式（含跨 chunk 缓冲）
import { api, authenticatedFetch, ApiError, apiErrorMessage } from "./client";

export const chat = (
  message: string,
  history: { role: string; content: string }[],
  sessionId: string | null,
  confirmations: Record<string, unknown>[] = [],
) => api("/chat", {
  method: "POST",
  body: JSON.stringify({ message, history, session_id: sessionId, confirmations }),
});

export async function chatStream(
  message: string,
  history: { role: string; content: string }[],
  sessionId: string | null,
  confirmations: Record<string, unknown>[],
  onEvent: (event: string, data: Record<string, unknown>) => void,
  signal: AbortSignal,
): Promise<Record<string, unknown> | null> {
  const res = await authenticatedFetch("/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message, history, session_id: sessionId, confirmations }),
    signal,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, apiErrorMessage(body, res.statusText || `HTTP ${res.status}`));
  }
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
