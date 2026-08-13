// 对话 API
import { api } from "./client.js";

export const chat = (message, history, sessionId, confirmations = []) => api("/chat", {
  method: "POST",
  body: JSON.stringify({ message, history, session_id: sessionId, confirmations }),
});
