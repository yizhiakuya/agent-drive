import type { Dispatch, SetStateAction } from "react";
import { appendContextMessage, appendToolStep, completeToolStep, planFromToolTrace } from "./chat-stream-state";
import type { PlanStep } from "./PlanCard";
import type { Message } from "./chat-types";
import type { ChatStreamEvent, ToolTrace } from "./chat-stream-events";
import type { ChatStreamFrame } from "./chat-stream-frame";

const LEGACY_FILES_TOOLS = new Set([
  "list_files", "search_files", "read_file", "write_file", "append_file",
  "copy_file", "create_folder", "rename_file", "move_file", "delete_file", "get_storage_info",
  "read_document", "search_content", "semantic_search", "index_stats",
]);

/** 判断新版 backend_api trace 是否会改变文件或索引状态。 */
export function isFileMutationTrace(trace: ToolTrace): boolean {
  if (LEGACY_FILES_TOOLS.has(trace.tool)) return true;
  if (trace.tool !== "backend_api" || !trace.parsed || typeof trace.parsed !== "object"
      || Array.isArray(trace.parsed)) return false;
  const parsed = trace.parsed as Record<string, unknown>;
  const nested = parsed.result && typeof parsed.result === "object" && !Array.isArray(parsed.result)
    ? parsed.result as Record<string, unknown>
    : null;
  const operations = [parsed.operation, nested?.operation].filter((value): value is string => typeof value === "string");
  return operations.some((operation) => (
    /^\s*(POST|PUT|PATCH|DELETE)\s+\/api\/v1\/(files|index)(?:\/|$)/i.test(operation)
      || /^\s*INTERNAL\s+(?:write|append)_text\b/i.test(operation)
  ));
}

export interface ChatStreamEventHandlers {
  frame: ChatStreamFrame;
  setMessages: Dispatch<SetStateAction<Message[]>>;
  setPlan: Dispatch<SetStateAction<PlanStep[]>>;
  onFrontendAction: (data: Record<string, unknown>) => void;
}

/**
 * 把已校验的流事件映射为消息、计划和界面动作更新。
 * 文件工具完成后额外广播 files-changed，让文件栏刷新；这属于事件副作用，不能放回纯文本帧逻辑。
 */
export function dispatchChatStreamEvent(
  event: ChatStreamEvent,
  handlers: ChatStreamEventHandlers,
) {
  const { frame, setMessages, setPlan, onFrontendAction } = handlers;
  switch (event.type) {
    case "text":
      frame.appendText(event.delta);
      break;
    case "reasoning":
      frame.appendReasoning(event.delta);
      break;
    case "context":
      setMessages((messages) => appendContextMessage(messages, {
        type: "context",
        source: event.context.source,
        contextKind: event.context.kind,
        content: event.context.content,
      }));
      break;
    case "frontend_action":
      onFrontendAction(event.data);
      break;
    case "tool_start":
      frame.beginToolStep();
      setMessages((messages) => appendToolStep(messages, event.data));
      break;
    case "tool_trace": {
      setMessages((messages) => completeToolStep(messages, event.trace));
      const nextPlan = planFromToolTrace(event.trace);
      if (nextPlan) setPlan(nextPlan);
      break;
    }
  }
}
