"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { cancelChatRun, chatReconnect, chatRunActive, chatStream } from "@/lib/api/chat";
import { getFrontendCapabilities, normalizeFrontendAction } from "@/lib/frontend-actions";
import { emitFilesChanged } from "@/lib/events";
import { useAppStore } from "@/lib/store";
import type { PlanStep } from "./PlanCard";
import { createChatStreamFrame, type ChatStreamFrame } from "./chat-stream-frame";
import { parseChatStreamEvent } from "./chat-stream-events";
import { activityIdForTool, dispatchChatStreamEvent, isFileMutationTrace } from "./chat-stream-dispatch";
import { buildChatHistory, removeEmptyAssistantMessages, settleRunningToolSteps } from "./chat-stream-state";
import type { ContextUsage, InlineImage, InlineImagePreview, Message, PendingConfirmation, PermissionMode, ThinkingLevel } from "./chat-types";
import { finishOperationActivity, startOperationActivity, updateOperationActivity } from "@/lib/operation-activity";

export type { ContextUsage, InlineImage, InlineImagePreview, Message, PendingConfirmation, PermissionMode, ThinkingLevel } from "./chat-types";
export { chatTextDelta } from "./chat-stream-events";

interface UseChatStreamOptions {
  /** 当前消息历史透视图（send 发起时采样，用于构造 history） */
  messages: Message[];
  /** 当前正在查看的会话 id。 */
  sessionId: string | null;
  /** 当前会话 id（读/写走 ref，保证异步流回调里拿到最新值） */
  sessionIdRef: React.MutableRefObject<string | null>;
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>;
  setPending: React.Dispatch<React.SetStateAction<PendingConfirmation | null>>;
  setPlan: React.Dispatch<React.SetStateAction<PlanStep[]>>;
  setContextUsage: React.Dispatch<React.SetStateAction<ContextUsage | null>>;
  setSessionId: (id: string | null) => void;
  bumpSessions: () => void;
  /** 后台流结束且当前已返回原会话时，重新读取持久化消息。 */
  onReconcile?: (sessionId: string) => void;
  onFinish?: () => void;
}

interface UseChatStreamReturn {
  /** 发送一条消息（可选自定义消息、确认信息和本轮模型）。发送前 UI 输入框清空由调用方负责。 */
  send: (message?: string, confirmations?: Record<string, unknown>[], thinkingLevel?: ThinkingLevel, model?: string, fileContext?: string[], permissionMode?: PermissionMode, inlineImages?: InlineImage[]) => Promise<void>;
  /** 中止当前进行中的流，并追加「已停止」提示。 */
  stop: () => void;
  /** 当前正在查看的会话是否有流式请求在进行中。 */
  busy: boolean;
  /** 当前/最近一轮 Agent 任务的总耗时；运行中每秒更新，结束时收敛到服务端 latency_ms。 */
  totalElapsedMs: number | null;
  /** 当前/最近一轮累计模型等待/思考耗时；不包含工具执行耗时。 */
  modelElapsedMs: number | null;
}

const NEW_SESSION_KEY = "__new_session__";

interface ActiveChatStream {
  key: string;
  controller: AbortController;
  frame: ChatStreamFrame;
  detached: boolean;
  startedAt: number;
  activityId: string;
  toolActivityIds: Set<string>;
  phase: "model" | "tool";
  phaseStartedAt: number;
  modelElapsedMs: number;
}

function streamKey(sessionId: string | null): string {
  return sessionId ?? NEW_SESSION_KEY;
}

function moveRunToSession(
  run: ActiveChatStream,
  sessionId: string,
  streams: Map<string, ActiveChatStream>,
) {
  const nextKey = streamKey(sessionId);
  if (run.key === nextKey) return;
  if (streams.get(run.key) !== run) return;
  const existing = streams.get(nextKey);
  if (existing && existing !== run) {
    existing.frame.cancel();
    existing.controller.abort();
    streams.delete(nextKey);
  }
  streams.delete(run.key);
  run.key = nextKey;
  streams.set(nextKey, run);
}

function currentErrorSessionId(error: unknown): string | null {
  if (typeof error !== "object" || error === null) return null;
  const sessionId = (error as { sessionId?: unknown }).sessionId;
  return typeof sessionId === "string" && sessionId.trim() ? sessionId : null;
}

function agentActivityPhase(event: string, data: Record<string, unknown>) {
  if (event === "tool_start" || event === "tool_progress") {
    return {
      phase: "tool",
      message: typeof data.progress_message === "string" ? data.progress_message
        : typeof data.message === "string" ? data.message : "正在执行工具",
    };
  }
  return { phase: "model", message: "正在等待模型响应" };
}

function settleRunToolActivities(
  run: ActiveChatStream,
  status: "cancelled" | "failed",
  message: string,
  error?: string,
) {
  for (const id of run.toolActivityIds) {
    finishOperationActivity(id, status, {
      phase: "finished",
      message,
      ...(error ? { error } : {}),
    });
  }
}

function trackToolActivity(run: ActiveChatStream, event: string, data: Record<string, unknown>) {
  if ((event === "tool_start" || event === "tool_progress") && data.tool === "backend_api") {
    run.toolActivityIds.add(activityIdForTool(data));
  }
}

function updateRunPhase(run: ActiveChatStream, event: string) {
  const nextPhase = event === "tool_start" || event === "tool_progress"
    ? "tool"
    : event === "tool_trace" ? "model" : run.phase;
  if (nextPhase === run.phase) return;
  const now = Date.now();
  if (run.phase === "model") run.modelElapsedMs += Math.max(0, now - run.phaseStartedAt);
  run.phase = nextPhase;
  run.phaseStartedAt = now;
}

function currentModelElapsedMs(run: ActiveChatStream) {
  return Math.max(0, run.modelElapsedMs + (run.phase === "model"
    ? Date.now() - run.phaseStartedAt
    : 0));
}

function settlePlanSteps(steps: PlanStep[], status: "cancelled" | "failed") {
  return steps.map((step) => step.status === "in_progress"
    ? { ...step, status }
    : step);
}

/**
 * 流式对话发送 hook：按 session 隔离 chatStream、80ms 节流帧、事件→消息映射、
 * 会话建立/列表刷新/计划流、AbortController 生命周期与错误兜底。
 *
 * 消息和当前视图状态仍由 ChatPanel 持有；活动连接保存在 session-keyed Map，
 * 因此切换只隔离视图写入，显式 stop 或组件卸载才中止网络请求。
 */
export function useChatStream(options: UseChatStreamOptions): UseChatStreamReturn {
  const {
    messages,
    sessionId,
    sessionIdRef,
    setMessages,
    setPending,
    setPlan,
    setContextUsage,
    setSessionId,
    bumpSessions,
    onReconcile,
    onFinish,
  } = options;

  const streamsRef = useRef(new Map<string, ActiveChatStream>());
  const onReconcileRef = useRef(onReconcile);
  const [runningKeys, setRunningKeys] = useState<ReadonlySet<string>>(() => new Set());
  const [totalElapsedMs, setTotalElapsedMs] = useState<number | null>(null);
  const [modelElapsedMs, setModelElapsedMs] = useState<number | null>(null);
  const busy = runningKeys.has(streamKey(sessionId));

  useEffect(() => {
    onReconcileRef.current = onReconcile;
  }, [onReconcile]);

  const refreshRunningKeys = useCallback(() => {
    setRunningKeys(new Set(streamsRef.current.keys()));
  }, []);

  // 活动会话只在当前进程内计时；完成后保留最后一次耗时，便于用户判断慢点发生在哪一轮。
  useEffect(() => {
    const tick = () => {
      const run = streamsRef.current.get(streamKey(sessionIdRef.current));
      if (run) {
        setTotalElapsedMs(Math.max(0, Date.now() - run.startedAt));
        setModelElapsedMs(currentModelElapsedMs(run));
      }
    };
    tick();
    if (!busy) return;
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [busy, sessionIdRef]);

  const isVisible = useCallback((run: ActiveChatStream) => (
    streamsRef.current.get(run.key) === run
    && streamKey(sessionIdRef.current) === run.key
  ), [sessionIdRef]);

  async function send(
    message?: string,
    confirmations: Record<string, unknown>[] = [],
    thinkingLevel: ThinkingLevel = "auto",
    model = "",
    fileContext: string[] = [],
    permissionMode: PermissionMode = "auto",
    inlineImages: InlineImage[] = [],
  ) {
    const msg = message ?? "";
    const sendSid = sessionIdRef.current;
    const key = streamKey(sendSid);
    if (!msg || streamsRef.current.has(key)) return;
    const history = buildChatHistory(messages);
    const messageImages: InlineImagePreview[] = inlineImages.map(({ id, name, mediaType, previewUrl, size }) => ({
      id,
      name,
      mediaType,
      previewUrl,
      size,
    }));
    setMessages((m) => [...m, {
      type: "user",
      content: msg,
      ...(messageImages.length > 0 ? { images: messageImages } : {}),
    }]);
    setPending(null);
    setPlan([]);
    setContextUsage(null);

    const controller = new AbortController();
    setMessages((m) => [...m, { type: "assistant", content: "" }]);
    const startedAt = Date.now();
    const activityId = `agent-run-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    startOperationActivity({
      id: activityId,
      source: "agent",
      kind: "agent-run",
      title: "Agent 运行",
      target: "当前会话",
      operation: "POST /api/v1/chat/stream",
      phase: "model",
      message: "正在等待模型响应",
      startedAt,
    });
    const run = {
      key, controller, frame: null as unknown as ChatStreamFrame, detached: false, startedAt, activityId,
      toolActivityIds: new Set<string>(),
      phase: "model" as const, phaseStartedAt: startedAt, modelElapsedMs: 0,
    };
    const frame = createChatStreamFrame({
      isCurrent: () => streamsRef.current.get(run.key)?.controller === controller
        && streamKey(sessionIdRef.current) === run.key,
      setMessages,
    });
    run.frame = frame;
    streamsRef.current.set(key, run);
    setTotalElapsedMs(0);
    refreshRunningKeys();
    // 将协议事件集中交给 dispatcher，保证文本/思考/工具步骤共享同一个帧和消息状态机。
    const eventHandlers = {
      frame,
      setMessages,
      setPlan,
      setContextUsage,
      onFrontendAction: (data: Record<string, unknown>) => {
        const action = normalizeFrontendAction(data);
        if (action) useAppStore.getState().enqueueFrontendAction(action);
      },
    };

    try {
      const r = await chatStream(msg, history, sendSid, confirmations, (event, data) => {
        if (streamsRef.current.get(run.key) !== run) return;
        const streamEvent = parseChatStreamEvent(event, data);
        if (!streamEvent) return;
        trackToolActivity(run, event, data);
        updateRunPhase(run, event);
        updateOperationActivity(run.activityId, agentActivityPhase(event, data));
        const visible = isVisible(run);
        if (!visible) run.detached = true;
        // 文件变更通知与当前会话视图解耦；切换会话时后台运行也必须刷新文件栏。
        if (streamEvent.type === "tool_trace" && isFileMutationTrace(streamEvent.trace)) {
          emitFilesChanged();
        }
        if (streamEvent.type === "text" || streamEvent.type === "reasoning") {
          dispatchChatStreamEvent(streamEvent, eventHandlers);
        } else if (streamEvent.type === "frontend_action") {
          eventHandlers.onFrontendAction(streamEvent.data);
        } else if (visible) {
          dispatchChatStreamEvent(streamEvent, eventHandlers);
        } else if (streamEvent.type === "tool_start") {
          frame.beginToolStep();
        }
      }, controller.signal, thinkingLevel, getFrontendCapabilities(), model, fileContext, permissionMode, inlineImages,
      (resolvedSid) => {
        moveRunToSession(run, resolvedSid, streamsRef.current);
        sessionIdRef.current = resolvedSid;
        setSessionId(resolvedSid);
        refreshRunningKeys();
      });

      if (streamsRef.current.get(run.key) !== run) return;
      const visible = isVisible(run);
      const serverElapsed = typeof r?.total_elapsed_ms === "number"
        ? r.total_elapsed_ms
        : typeof r?.latency_ms === "number" ? r.latency_ms : null;
      if (visible) setTotalElapsedMs(serverElapsed === null
        ? Math.max(0, Date.now() - run.startedAt)
        : Math.max(0, serverElapsed));
      setModelElapsedMs(currentModelElapsedMs(run));
      // 流结束：冲刷最后一帧（节流定时器里的内容立即落 UI）
      if (!frame.flush() && visible) {
        // 无文本事件（仅工具调用）：清掉空助手占位气泡，只留工具步骤
        setMessages(removeEmptyAssistantMessages);
      }
      const resolvedSid = typeof r?.session_id === "string" ? r.session_id : null;
      if (resolvedSid) {
        if (sendSid === null && visible) {
          sessionIdRef.current = resolvedSid;
          setSessionId(resolvedSid);
        }
        bumpSessions();
      }
      if (visible) {
        const rPlan = (r?.plan ?? []) as PlanStep[];
        if (rPlan.length) setPlan(rPlan);
        if (r?.context_usage) setContextUsage(r.context_usage as ContextUsage);
        if (r?.truncated) {
          setMessages((m) => [...m, { type: "system", content: "任务达到最大步数，可能未完成，回复「继续」可接着做。" }]);
        }
        if (r?.pending_confirmation) setPending(r.pending_confirmation as PendingConfirmation);
        if (run.detached && resolvedSid) onReconcile?.(resolvedSid);
        if (onFinish) setTimeout(onFinish, 50);
      }
      finishOperationActivity(run.activityId, r?.pending_confirmation || r?.truncated ? "partial" : "succeeded", {
        phase: "finished",
        message: r?.pending_confirmation ? "等待用户确认" : r?.truncated ? "运行达到边界" : "Agent 运行完成",
      });
    } catch (error) {
      if ((error as Error).name === "AbortError" || streamsRef.current.get(run.key) !== run) return;
      const visible = isVisible(run);
      const resolvedSid = currentErrorSessionId(error);
      if (resolvedSid) {
        if (sendSid === null && visible) {
          sessionIdRef.current = resolvedSid;
          setSessionId(resolvedSid);
        }
        bumpSessions();
      }
      // 先把待提交的正文/reasoning 同步落地并取消定时帧，再追加错误；否则迟到的
      // 80ms commit 会覆盖错误，直接替换末项也会吞掉已经完成的工具步骤。
      frame.flush();
      frame.cancel();
      settleRunToolActivities(run, "failed", "工具执行异常中断", error instanceof Error ? error.message : String(error));
      if (visible) {
        setTotalElapsedMs(Math.max(0, Date.now() - run.startedAt));
        setPlan((steps) => settlePlanSteps(steps, "failed"));
        const message = error instanceof Error ? error.message : String(error);
        setMessages((m) => [
          ...removeEmptyAssistantMessages(settleRunningToolSteps(m, "error", "工具执行异常中断", Date.now() - run.startedAt)),
          { type: "assistant", content: `出错了：${message}` },
        ]);
        if (run.detached && resolvedSid) onReconcile?.(resolvedSid);
      }
      finishOperationActivity(run.activityId, "failed", {
        phase: "finished",
        message: "Agent 运行失败",
        error: error instanceof Error ? error.message : String(error),
      });
    } finally {
      if (streamsRef.current.get(run.key) === run) {
        streamsRef.current.delete(run.key);
        refreshRunningKeys();
      }
    }
  }

  useEffect(() => {
    if (!sessionId || streamsRef.current.has(sessionId)) return;
    const streams = streamsRef.current;
    let disposed = false;
    let run: ActiveChatStream | null = null;
    const controller = new AbortController();
    const key = sessionId;

    void chatRunActive(key)
      .then(({ active, status }) => {
        if (status === "interrupted" && !disposed && sessionIdRef.current === key) {
          setMessages((current) => [...current, {
            type: "system",
            content: "上次运行在服务重启时中断，当前没有自动重放未确认操作；如需继续，请回复“继续刚才的任务”。",
          }]);
        }
        if (!active || disposed || streams.has(key)) return;
        const frame = createChatStreamFrame({
          isCurrent: () => streamsRef.current.get(key)?.controller === controller
            && streamKey(sessionIdRef.current) === key,
          setMessages,
        });
        const activityId = `agent-run-reconnect-${key}`;
        startOperationActivity({
          id: activityId,
          source: "agent",
          kind: "agent-run",
          title: "Agent 运行",
          target: "当前会话",
          operation: "POST /api/v1/chat/stream",
          phase: "reconnect",
          message: "正在恢复 Agent 运行",
        });
        run = {
          key, controller, frame, detached: false, startedAt: Date.now(), activityId,
          toolActivityIds: new Set<string>(),
          phase: "model", phaseStartedAt: Date.now(), modelElapsedMs: 0,
        };
        streams.set(key, run);
        refreshRunningKeys();
        const eventHandlers = {
          frame,
          setMessages,
          setPlan,
          setContextUsage,
          onFrontendAction: (data: Record<string, unknown>) => {
            const action = normalizeFrontendAction(data);
            if (action) useAppStore.getState().enqueueFrontendAction(action);
          },
        };
        return chatReconnect(key, (event, data) => {
          if (!run || streamsRef.current.get(key) !== run) return;
          const streamEvent = parseChatStreamEvent(event, data);
          if (!streamEvent) return;
          trackToolActivity(run, event, data);
          updateRunPhase(run, event);
          updateOperationActivity(run.activityId, agentActivityPhase(event, data));
           if (streamEvent.type === "tool_trace" && isFileMutationTrace(streamEvent.trace)) {
             emitFilesChanged();
           }
           if (streamEvent.type === "frontend_action") {
            eventHandlers.onFrontendAction(streamEvent.data);
          } else {
            dispatchChatStreamEvent(streamEvent, eventHandlers);
          }
        }, controller.signal);
      })
      .catch(() => {})
      .finally(() => {
        if (!run || streams.get(key) !== run) return;
        streams.delete(key);
        run.frame.flush();
        run.frame.cancel();
        finishOperationActivity(run.activityId, "succeeded", { phase: "finished", message: "Agent 运行完成" });
        refreshRunningKeys();
        onReconcileRef.current?.(key);
        bumpSessions();
      });

    return () => {
      disposed = true;
      controller.abort();
      if (run && streams.get(key) === run) {
        streams.delete(key);
        run.frame.cancel();
        settleRunToolActivities(run, "cancelled", "工具执行已停止");
        finishOperationActivity(run.activityId, "cancelled", { phase: "finished", message: "Agent 运行已取消" });
        refreshRunningKeys();
      }
    };
  }, [sessionId, sessionIdRef, setMessages, setPlan, setContextUsage, refreshRunningKeys, bumpSessions]);

  useEffect(() => {
    const activeKey = streamKey(sessionId);
    for (const run of streamsRef.current.values()) {
      if (run.key !== activeKey) run.detached = true;
    }
  }, [sessionId]);

  useEffect(() => {
    const streams = streamsRef.current;
    return () => {
      const active = Array.from(streams.values());
      streams.clear();
      for (const run of active) {
        run.frame.cancel();
        run.controller.abort();
        settleRunToolActivities(run, "cancelled", "工具执行已停止");
        finishOperationActivity(run.activityId, "cancelled", { phase: "finished", message: "Agent 运行已取消" });
      }
    };
  }, []);

  const stop = useCallback(() => {
    const key = streamKey(sessionIdRef.current);
    const run = streamsRef.current.get(key);
    if (!run) return;
    streamsRef.current.delete(key);
    run.frame.cancel();
    run.controller.abort();
    settleRunToolActivities(run, "cancelled", "工具执行已停止");
    finishOperationActivity(run.activityId, "cancelled", { phase: "finished", message: "Agent 运行已取消" });
    setTotalElapsedMs(Math.max(0, Date.now() - run.startedAt));
    setModelElapsedMs(currentModelElapsedMs(run));
    setPlan((steps) => settlePlanSteps(steps, "cancelled"));
    if (sessionIdRef.current) void cancelChatRun(sessionIdRef.current).catch(() => {});
    refreshRunningKeys();
    setMessages((m) => {
      // 清掉空助手占位气泡后追加停止提示（工具步骤保留）
      const copy = settleRunningToolSteps(m, "cancelled", "工具执行已停止", Date.now() - run.startedAt)
        .filter((x) => !(x.type === "assistant" && !x.content && !x.reasoning));
      copy.push({ type: "system", content: "已停止本次任务。" });
      return copy;
    });
  }, [refreshRunningKeys, sessionIdRef, setMessages, setPlan]);

  return { send, stop, busy, totalElapsedMs, modelElapsedMs };
}
