import type { Dispatch, SetStateAction } from "react";
import type { Message } from "./chat-types";
import { replaceAssistantMessage } from "./chat-stream-state";

interface ChatStreamFrameOptions {
  isCurrent: () => boolean;
  setMessages: Dispatch<SetStateAction<Message[]>>;
  delayMs?: number;
}

export interface ChatStreamFrame {
  appendText: (delta: string) => void;
  appendReasoning: (delta: string) => void;
  beginToolStep: () => void;
  flush: () => boolean;
  cancel: () => void;
}

/** 管理一轮模型输出的 80ms UI 帧和工具轮次边界。 */
export function createChatStreamFrame({
  isCurrent,
  setMessages,
  delayMs = 80,
}: ChatStreamFrameOptions): ChatStreamFrame {
  // 流事件按帧批量提交，避免每个 token 都触发消息列表重渲染；flush 负责收尾时不丢最后一帧。
  let reply = "";
  let reasoning = "";
  let timer: ReturnType<typeof setTimeout> | null = null;

  const clearTimer = () => {
    if (!timer) return;
    clearTimeout(timer);
    timer = null;
  };

  const commit = () => {
    if (!isCurrent()) return;
    const content = reply;
    const reasoningSnapshot = reasoning;
    setMessages((messages) => replaceAssistantMessage(messages, content, reasoningSnapshot));
  };

  const schedule = () => {
    if (timer) return;
    timer = setTimeout(() => {
      timer = null;
      commit();
    }, delayMs);
  };

  return {
    appendText(delta) {
      reply += delta;
      schedule();
    },
    appendReasoning(delta) {
      reasoning += delta;
      schedule();
    },
    beginToolStep() {
      clearTimer();
      if (reply || reasoning) commit();
      reply = "";
      reasoning = "";
    },
    flush() {
      const hadTimer = timer !== null;
      if (hadTimer) {
        clearTimer();
        commit();
      }
      return hadTimer;
    },
    cancel() {
      clearTimer();
      reply = "";
      reasoning = "";
    },
  };
}
