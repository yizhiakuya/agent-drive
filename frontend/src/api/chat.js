// 对话 API（含流式 SSE）
import { api } from "./client.js";

export const chat = (message, history, sessionId, confirmations = []) => api("/chat", {
  method: "POST",
  body: JSON.stringify({ message, history, session_id: sessionId, confirmations }),
});

/**
 * 流式对话：SSE 解析。
 * onEvent: (event, payload) => void
 *   event = "text" | "tool_trace" | "done" | "error"
 * 返回 Promise<donePayload>
 */
export async function chatStream(message, history, sessionId, confirmations = [], onEvent) {
  const res = await fetch("/api/v1/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message, history, session_id: sessionId, confirmations }),
  });
  if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let donePayload = null;

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    // 按空行分隔 SSE 事件
    const parts = buffer.split("\n\n");
    buffer = parts.pop(); // 保留未完成部分
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
