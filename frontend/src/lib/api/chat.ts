// 对话 API：聚合 + SSE 流式（含跨 chunk 缓冲）
import { api, apiPath, authHeaders } from "./client";

export interface ChatEvent {
  event: "text" | "tool_start" | "tool_trace" | "done" | "error";
  data: Record<string, unknown>;
}

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
) {
  const res = await fetch(apiPath("/chat/stream"), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    credentials: "include",
    body: JSON.stringify({ message, history, session_id: sessionId, confirmations }),
    signal,
  });
  if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let donePayload: Record<string, unknown> | null = null;

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split("\n\n");
    buffer = parts.pop() ?? "";
    for (const part of parts) {
      const lines = part.split("\n");
      const evLine = lines.find((l) => l.startsWith("event:"));
      const dataLine = lines.find((l) => l.startsWith("data:"));
      if (!evLine || !dataLine) continue;
      const event = evLine.slice(6).trim();
      const data = JSON.parse(dataLine.slice(5).trim());
      if (event === "done") donePayload = data;
      onEvent(event, data);
    }
  }
  return donePayload;
}
