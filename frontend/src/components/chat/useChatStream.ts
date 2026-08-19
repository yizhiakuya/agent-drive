"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { chatStream } from "@/lib/api/chat";
import { emitFilesChanged } from "@/lib/events";
import { getFrontendCapabilities, normalizeFrontendAction } from "@/lib/frontend-actions";
import { useAppStore } from "@/lib/store";
import type { PlanStep } from "./PlanCard";
import { createChatStreamFrame, type ChatStreamFrame } from "./chat-stream-frame";
import { parseChatStreamEvent } from "./chat-stream-events";
import {
  appendToolStep,
  buildChatHistory,
  completeToolStep,
  planFromToolTrace,
  removeEmptyAssistantMessages,
} from "./chat-stream-state";
import type { ContextUsage, Message, PendingConfirmation, ThinkingLevel } from "./chat-types";

export type { ContextUsage, Message, PendingConfirmation, ThinkingLevel } from "./chat-types";
export { chatTextDelta } from "./chat-stream-events";

const FILES_TOOLS = ["list_files", "search_files", "read_file", "write_file", "append_file",
  "copy_file", "create_folder", "rename_file", "move_file", "delete_file", "get_storage_info",
  "read_document", "search_content", "semantic_search", "index_stats"];

interface UseChatStreamOptions {
  /** 当前消息历史透视图（send 发起时采样，用于构造 history） */
  messages: Message[];
  /** 当前会话 id（读/写走 ref，保证异步流回调里拿到最新值） */
  sessionIdRef: React.MutableRefObject<string | null>;
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>;
  setBusy: React.Dispatch<React.SetStateAction<boolean>>;
  setPending: React.Dispatch<React.SetStateAction<PendingConfirmation | null>>;
  setPlan: React.Dispatch<React.SetStateAction<PlanStep[]>>;
  setContextUsage: React.Dispatch<React.SetStateAction<ContextUsage | null>>;
  setSessionId: (id: string | null) => void;
  bumpSessions: () => void;
  onFinish?: () => void;
}

interface UseChatStreamReturn {
  /** 发送一条消息（可选自定义消息与确认信息）。发送前 UI 输入框清空由调用方负责。 */
  send: (message?: string, confirmations?: Record<string, unknown>[], thinkingLevel?: ThinkingLevel) => Promise<void>;
  /** 中止当前进行中的流，并追加「已停止」提示。 */
  stop: () => void;
  /** 静默中止（会话切换/卸载），不追加提示。 */
  abortStream: () => void;
  /** 当前是否有流式请求在进行中。 */
  busy: boolean;
}

/**
 * 流式对话发送 hook：封装 chatStream 调用、80ms 节流帧、事件→消息映射、
 * 会话建立/列表标题刷新/计划流、AbortController 生命周期与错误兜底。
 *
 * 所有 UI 状态（messages/input/plan/contextUsage/sessionId 等）仍由 ChatPanel 持有；
 * 本 hook 通过 options 传入的 setter 与 ref 读写这些状态，行为与原内联实现完全等价。
 */
export function useChatStream(options: UseChatStreamOptions): UseChatStreamReturn {
  const {
    messages,
    sessionIdRef,
    setMessages,
    setBusy,
    setPending,
    setPlan,
    setContextUsage,
    setSessionId,
    bumpSessions,
    onFinish,
  } = options;

  const [busy, _setBusyState] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const frameRef = useRef<ChatStreamFrame | null>(null);
  const streamGenerationRef = useRef(0);

  const applyBusy = useCallback((value: boolean) => {
    _setBusyState(value);
    setBusy(value);
  }, [setBusy]);

  async function send(
    message?: string,
    confirmations: Record<string, unknown>[] = [],
    thinkingLevel: ThinkingLevel = "auto",
  ) {
    const msg = message ?? "";
    if (!msg || busy) return;
    const history = buildChatHistory(messages);
    setMessages((m) => [...m, { type: "user", content: msg }]);
    applyBusy(true);
    setPending(null);
    setPlan([]);
    setContextUsage(null);
    const controller = new AbortController();
    abortRef.current = controller;
    const sendSid = sessionIdRef.current;
    const generation = ++streamGenerationRef.current;
    setMessages((m) => [...m, { type: "assistant", content: "" }]);
    const frame = createChatStreamFrame({
      isCurrent: () => generation === streamGenerationRef.current,
      setMessages,
    });
    frameRef.current = frame;

    try {
      const r = await chatStream(msg, history, sendSid, confirmations, (event, data) => {
        if (generation !== streamGenerationRef.current) return;
        const streamEvent = parseChatStreamEvent(event, data);
        if (!streamEvent) return;
        switch (streamEvent.type) {
          case "text":
            frame.appendText(streamEvent.delta);
            break;
          case "reasoning":
            frame.appendReasoning(streamEvent.delta);
            break;
          case "frontend_action": {
            const action = normalizeFrontendAction(streamEvent.data);
            if (action) useAppStore.getState().enqueueFrontendAction(action);
            break;
          }
          case "tool_start":
            frame.beginToolStep();
            setMessages((messages) => appendToolStep(messages, streamEvent.data));
            break;
          case "tool_trace": {
            const { trace } = streamEvent;
            if (FILES_TOOLS.includes(trace.tool)) emitFilesChanged();
            setMessages((messages) => completeToolStep(messages, trace));
            const nextPlan = planFromToolTrace(trace);
            if (nextPlan) setPlan(nextPlan);
            break;
          }
        }
      }, controller.signal, thinkingLevel, getFrontendCapabilities());

      if (generation !== streamGenerationRef.current) return;
      // 流结束：冲刷最后一帧（节流定时器里的内容立即落 UI）
      if (!frame.flush()) {
        // 无文本事件（仅工具调用）：清掉空助手占位气泡，只留工具步骤
        setMessages(removeEmptyAssistantMessages);
      }
      if (sendSid !== sessionIdRef.current) return;
      const rPlan = (r?.plan ?? []) as PlanStep[];
      if (rPlan.length) setPlan(rPlan);
      if (r?.context_usage) setContextUsage(r.context_usage as { used: number; total: number; percent: number });
      if (r?.session_id) {
        const sid = r.session_id as string;
        sessionIdRef.current = sid;
        setSessionId(sid);
        bumpSessions();
      }
      if (r?.truncated) {
        setMessages((m) => [...m, { type: "system", content: "⚠️ 任务达到最大步数，可能未完成，回复「继续」可接着做" }]);
      }
      if (r?.pending_confirmation) setPending(r.pending_confirmation as PendingConfirmation);
      setTimeout(() => {
        if (generation === streamGenerationRef.current) onFinish?.();
      }, 50);
    } catch (e) {
      if ((e as Error).name === "AbortError" || generation !== streamGenerationRef.current) return;
      setMessages((m) => {
        const copy = [...m];
        copy[copy.length - 1] = { type: "assistant", content: `⚠️ 出错了：${(e as Error).message}` };
        return copy;
      });
    } finally {
      if (frameRef.current === frame) frameRef.current = null;
      if (generation === streamGenerationRef.current) applyBusy(false);
    }
  }

  useEffect(() => () => {
    streamGenerationRef.current += 1;
    frameRef.current?.cancel();
    frameRef.current = null;
    abortRef.current?.abort();
    abortRef.current = null;
  }, []);

  const cancelActiveStream = useCallback(() => {
    streamGenerationRef.current += 1;
    frameRef.current?.cancel();
    frameRef.current = null;
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    applyBusy(false);
  }, [applyBusy]);

  /** 静默中止：会话切换/卸载时用，不追加「已停止」提示。 */
  const abortStream = useCallback(() => {
    cancelActiveStream();
  }, [cancelActiveStream]);

  const stop = useCallback(() => {
    cancelActiveStream();
    setMessages((m) => {
      // 清掉空助手占位气泡后追加停止提示（工具步骤保留）
      const copy = m.filter((x) => !(x.type === "assistant" && !x.content && !x.reasoning));
      copy.push({ type: "system", content: "⏹️ 已停止本次任务" });
      return copy;
    });
  }, [cancelActiveStream, setMessages]);

  return { send, stop, abortStream, busy };
}
