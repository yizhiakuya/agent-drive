import type { Dispatch, SetStateAction } from "react";
import { emitFilesChanged } from "@/lib/events";
import { appendToolStep, completeToolStep, planFromToolTrace } from "./chat-stream-state";
import type { PlanStep } from "./PlanCard";
import type { Message } from "./chat-types";
import type { ChatStreamEvent } from "./chat-stream-events";
import type { ChatStreamFrame } from "./chat-stream-frame";

const FILES_TOOLS = new Set([
  "list_files", "search_files", "read_file", "write_file", "append_file",
  "copy_file", "create_folder", "rename_file", "move_file", "delete_file", "get_storage_info",
  "read_document", "search_content", "semantic_search", "index_stats",
]);

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
    case "frontend_action":
      onFrontendAction(event.data);
      break;
    case "tool_start":
      frame.beginToolStep();
      setMessages((messages) => appendToolStep(messages, event.data));
      break;
    case "tool_trace": {
      if (FILES_TOOLS.has(event.trace.tool)) emitFilesChanged();
      setMessages((messages) => completeToolStep(messages, event.trace));
      const nextPlan = planFromToolTrace(event.trace);
      if (nextPlan) setPlan(nextPlan);
      break;
    }
  }
}
