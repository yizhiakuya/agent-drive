"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { chatStream } from "@/lib/api/chat";
import { getFrontendCapabilities, normalizeFrontendAction } from "@/lib/frontend-actions";
import { useAppStore } from "@/lib/store";
import type { PlanStep } from "./PlanCard";
import { createChatStreamFrame, type ChatStreamFrame } from "./chat-stream-frame";
import { parseChatStreamEvent } from "./chat-stream-events";
import { dispatchChatStreamEvent, type ChatStreamEventHandlers } from "./chat-stream-dispatch";
import { buildChatHistory, removeEmptyAssistantMessages } from "./chat-stream-state";
import type { ContextUsage, Message, PendingConfirmation, ThinkingLevel } from "./chat-types";

export type { ContextUsage, Message, PendingConfirmation, ThinkingLevel } from "./chat-types";
export { chatTextDelta } from "./chat-stream-events";

interface UseChatStreamOptions {
  /** 当前消息历史透视图（send 发起时采样，用于构造 history） */
  messages: Message[];
  /** 当前会话 id（读/写走 ref，保证异步流回调里拿到最新值） */
  sessionIdRef: React.MutableRefObject<string | null>;
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>;
  setPending: React.Dispatch<React.SetStateAction<PendingConfirmation | null>>;
  setPlan: React.Dispatch<React.SetStateAction<PlanStep[]>>;
  setContextUsage: React.Dispatch<React.SetStateAction<ContextUsage | null>>;
  setSessionId: (id: string | null) => void;
  bumpSessions: () => void;
  onFinish?: () => void;
}

interface UseChatStreamReturn {
  /** 发送一条消息（可选自定义消息、确认信息和本轮模型）。发送前 UI 输入框清空由调用方负责。 */
  send: (message?: string, confirmations?: Record<string, unknown>[], thinkingLevel?: ThinkingLevel, model?: string) => Promise<void>;
  /** 中止当前进行中的流，并追加「已停止」提示。 */
  stop: () => void;
  /** 静默中止（会话切换/卸载），不追加提示。 */
  abortStream: () => void;
  /** 当前是否有流式请求在进行中。 */
  busy: boolean;
}

interface StreamLifecycle extends Pick<UseChatStreamOptions, "setMessages" | "setPending" | "setPlan" | "setContextUsage" | "setSessionId" | "bumpSessions" | "onFinish"> {
  frame: ChatStreamFrame;
  generation: number;
  sendSid: string | null;
  sessionIdRef: React.MutableRefObject<string | null>;
  streamGenerationRef: React.MutableRefObject<number>;
}

function isAbortError(error: unknown): boolean {
  return typeof error === "object" && error !== null
    && (error as { name?: unknown }).name === "AbortError";
}

function currentErrorSessionId(error: unknown): string | null {
  if (typeof error !== "object" || error === null) return null;
  const sessionId = (error as { sessionId?: unknown }).sessionId;
  return typeof sessionId === "string" && sessionId.trim() ? sessionId : null;
}

function adoptStreamSession(sessionId: unknown, lifecycle: StreamLifecycle): void {
  if (typeof sessionId !== "string" || !sessionId || lifecycle.sendSid !== lifecycle.sessionIdRef.current) return;
  lifecycle.sessionIdRef.current = sessionId;
  lifecycle.setSessionId(sessionId);
  lifecycle.bumpSessions();
}

function appendStreamError(
  error: unknown,
  lifecycle: StreamLifecycle,
): void {
  lifecycle.frame.flush();
  lifecycle.frame.cancel();
  const message = error instanceof Error ? error.message : String(error);
  lifecycle.setMessages((messages) => [
    ...removeEmptyAssistantMessages(messages),
    { type: "assistant" as const, content: `出错了：${message}` },
  ]);
}

function finishStream(result: Record<string, unknown> | null, lifecycle: StreamLifecycle): void {
  const { generation, streamGenerationRef, frame, setMessages, sendSid, sessionIdRef } = lifecycle;
  if (generation !== streamGenerationRef.current) return;
  if (!frame.flush()) setMessages(removeEmptyAssistantMessages);
  if (sendSid !== sessionIdRef.current) return;
  const plan = (result?.plan ?? []) as PlanStep[];
  if (plan.length) lifecycle.setPlan(plan);
  if (result?.context_usage) lifecycle.setContextUsage(result.context_usage as ContextUsage);
  adoptStreamSession(result?.session_id, lifecycle);
  if (result?.truncated) {
    setMessages((messages) => [...messages, { type: "system", content: "任务达到最大步数，可能未完成，回复「继续」可接着做。" }]);
  }
  if (result?.pending_confirmation) lifecycle.setPending(result.pending_confirmation as PendingConfirmation);
  setTimeout(() => {
    if (generation === streamGenerationRef.current) lifecycle.onFinish?.();
  }, 50);
}

function createStreamEventHandlers(
  frame: ChatStreamFrame,
  setMessages: StreamLifecycle["setMessages"],
  setPlan: StreamLifecycle["setPlan"],
): ChatStreamEventHandlers {
  return {
    frame,
    setMessages,
    setPlan,
    onFrontendAction: (data) => {
      const action = normalizeFrontendAction(data);
      if (action) useAppStore.getState().enqueueFrontendAction(action);
    },
  };
}

function dispatchCurrentStreamEvent(
  event: string,
  data: Record<string, unknown>,
  generation: number,
  streamGenerationRef: React.MutableRefObject<number>,
  eventHandlers: ChatStreamEventHandlers,
): void {
  if (generation !== streamGenerationRef.current) return;
  const streamEvent = parseChatStreamEvent(event, data);
  if (streamEvent) dispatchChatStreamEvent(streamEvent, eventHandlers);
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
    setPending,
    setPlan,
    setContextUsage,
    setSessionId,
    bumpSessions,
    onFinish,
  } = options;

  const [busy, _setBusyState] = useState(false);
  const busyRef = useRef(false);
  const abortRef = useRef<AbortController | null>(null);
  const frameRef = useRef<ChatStreamFrame | null>(null);
  const streamGenerationRef = useRef(0);

  const applyBusy = useCallback((value: boolean) => {
    busyRef.current = value;
    _setBusyState(value);
  }, []);

  async function send(
    message?: string,
    confirmations: Record<string, unknown>[] = [],
    thinkingLevel: ThinkingLevel = "auto",
    model = "",
  ) {
    const msg = message ?? "";
    if (!msg || busyRef.current) return;
    const history = buildChatHistory(messages);
    setMessages((current) => [...current, { type: "user", content: msg }]);
    applyBusy(true);
    setPending(null);
    setPlan([]);
    setContextUsage(null);

    const controller = new AbortController();
    abortRef.current = controller;
    const sendSid = sessionIdRef.current;
    const generation = ++streamGenerationRef.current;
    setMessages((current) => [...current, { type: "assistant", content: "" }]);
    const frame = createChatStreamFrame({
      isCurrent: () => generation === streamGenerationRef.current,
      setMessages,
    });
    frameRef.current = frame;
    const eventHandlers = createStreamEventHandlers(frame, setMessages, setPlan);
    const lifecycle: StreamLifecycle = {
      frame,
      generation,
      sendSid,
      sessionIdRef,
      streamGenerationRef,
      setMessages,
      setPending,
      setPlan,
      setContextUsage,
      setSessionId,
      bumpSessions,
      onFinish,
    };

    try {
      const result = await chatStream(
        msg,
        history,
        sendSid,
        confirmations,
        (event, data) => dispatchCurrentStreamEvent(event, data, generation, streamGenerationRef, eventHandlers),
        controller.signal,
        thinkingLevel,
        getFrontendCapabilities(),
        model,
      );
      finishStream(result, lifecycle);
    } catch (error) {
      if (isAbortError(error) || generation !== streamGenerationRef.current) return;
      adoptStreamSession(currentErrorSessionId(error), lifecycle);
      appendStreamError(error, lifecycle);
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
      copy.push({ type: "system", content: "已停止本次任务。" });
      return copy;
    });
  }, [cancelActiveStream, setMessages]);

  return { send, stop, abortStream, busy };
}
