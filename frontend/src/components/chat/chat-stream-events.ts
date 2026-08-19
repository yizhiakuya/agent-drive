export interface ToolTrace {
  tool: string;
  output: string;
  parsed?: Record<string, unknown> | unknown[];
}

export type ChatStreamEvent =
  | { type: "text"; delta: string }
  | { type: "reasoning"; delta: string }
  | { type: "frontend_action"; data: Record<string, unknown> }
  | { type: "tool_start"; data: Record<string, unknown> }
  | { type: "tool_trace"; trace: ToolTrace };

/** 从后端事件对象读取文本增量；非文本 payload 按空增量处理。 */
export function chatTextDelta(data: Record<string, unknown>): string {
  return typeof data.text === "string" ? data.text : "";
}

/** 将 SSE 事件转换为有限的内部事件集合，忽略不符合契约的 payload。 */
export function parseChatStreamEvent(event: string, data: Record<string, unknown>): ChatStreamEvent | null {
  switch (event) {
    case "text": {
      const delta = chatTextDelta(data);
      return delta ? { type: "text", delta } : null;
    }
    case "reasoning": {
      const delta = chatTextDelta(data);
      return delta ? { type: "reasoning", delta } : null;
    }
    case "frontend_action":
      return { type: "frontend_action", data };
    case "tool_start":
      return { type: "tool_start", data };
    case "tool_trace":
      return parseToolTrace(data);
    default:
      return null;
  }
}

function parseToolTrace(data: Record<string, unknown>): ChatStreamEvent | null {
  if (typeof data.tool !== "string" || typeof data.output !== "string") return null;
  const parsed = data.parsed;
  const trace: ToolTrace = {
    tool: data.tool,
    output: data.output,
    ...(isStructuredResult(parsed) ? { parsed } : {}),
  };
  return { type: "tool_trace", trace };
}

function isStructuredResult(value: unknown): value is Record<string, unknown> | unknown[] {
  return Array.isArray(value) || (typeof value === "object" && value !== null);
}
