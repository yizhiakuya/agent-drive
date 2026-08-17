"use client";
import { useCallback, useRef, useState } from "react";
import { chatStream } from "@/lib/api/chat";
import { emitFilesChanged } from "@/lib/events";
import { summarizeSession } from "@/lib/api/sessions";
import type { PlanStep } from "./PlanCard";

export type ThinkingLevel = "auto" | "low" | "medium" | "high";

export interface Message {
  type: "user" | "assistant" | "tool_step" | "system";
  content: string;
  reasoning?: string;
  tool?: string;
  arguments?: Record<string, unknown>;
  status?: "running" | "done" | "error";
  output?: string;
  parsed?: Record<string, unknown> | unknown[];
}

export interface PendingConfirmation {
  tool: string;
  arguments: Record<string, unknown>;
  nonce: string;
  ts: number;
  signature: string;
  message?: string;
}

const FILES_TOOLS = ["list_files", "search_files", "read_file", "write_file", "append_file",
  "copy_file", "create_folder", "rename_file", "move_file", "delete_file", "get_storage_info",
  "read_document", "search_content", "semantic_search", "index_stats"];

export function chatTextDelta(data: Record<string, unknown>): string {
  return typeof data.text === "string" ? data.text : "";
}

interface UseChatStreamOptions {
  /** 当前消息历史透视图（send 发起时采样，用于构造 history） */
  messages: Message[];
  /** 当前会话 id（读/写走 ref，保证异步流回调里拿到最新值） */
  sessionIdRef: React.MutableRefObject<string | null>;
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>;
  setBusy: React.Dispatch<React.SetStateAction<boolean>>;
  setPending: React.Dispatch<React.SetStateAction<PendingConfirmation | null>>;
  setPlan: React.Dispatch<React.SetStateAction<PlanStep[]>>;
  setContextUsage: React.Dispatch<React.SetStateAction<{ used: number; total: number; percent: number } | null>>;
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
 * 会话建立/自动总结/计划流、AbortController 生命周期与错误兜底。
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
  const streamTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

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
    const history = messages
      .filter((m) => m.type === "user" || m.type === "assistant")
      .map((m) => ({ role: m.type, content: m.content }))
      .slice(-80);
    setMessages((m) => [...m, { type: "user", content: msg }]);
    applyBusy(true);
    setPending(null);
    setPlan([]);
    setContextUsage(null);
    const controller = new AbortController();
    abortRef.current = controller;
    const sendSid = sessionIdRef.current;

    let replyRef = "";
    let reasoningRef = "";
    setMessages((m) => [...m, { type: "assistant", content: "" }]);
    // 流式节流：token 高频到达时每 80ms 批量刷一帧 UI（长回复不再逐 token 全量重渲染）
    // 工具步骤之后追加回复气泡时，先清掉发送时挂的空助手占位，避免残留空白气泡
    const applyReply = () => {
      setMessages((m) => {
        let copy = [...m];
        const assistant = {
          type: "assistant" as const,
          content: replyRef,
          ...(reasoningRef ? { reasoning: reasoningRef } : {}),
        };
        if (copy.length && copy[copy.length - 1].type === "tool_step") {
          copy = copy.filter((x) => !(x.type === "assistant" && !x.content));
          copy.push(assistant);
        } else {
          copy[copy.length - 1] = assistant;
        }
        return copy;
      });
    };
    const scheduleReply = () => {
      if (!streamTimerRef.current) {
        streamTimerRef.current = setTimeout(() => {
          streamTimerRef.current = null;
          applyReply();
        }, 80);
      }
    };

    try {
      const r = await chatStream(msg, history, sendSid, confirmations, (event, data) => {
        if (event === "text") {
          const delta = chatTextDelta(data);
          if (!delta) return;
          replyRef += delta;
          scheduleReply();
        } else if (event === "reasoning") {
          const delta = chatTextDelta(data);
          if (!delta) return;
          reasoningRef += delta;
          scheduleReply();
        } else if (event === "tool_start") {
          setMessages((m) => [...m, { type: "tool_step", status: "running", content: "", ...(data as object) } as Message]);
        } else if (event === "tool_trace") {
          const d = data as { tool: string; output: string; parsed?: Record<string, unknown> };
          if (FILES_TOOLS.includes(d.tool)) emitFilesChanged();
          setMessages((m) => {
            const copy = [...m];
            const failed = d.parsed && d.parsed.ok === false;
            for (let i = copy.length - 1; i >= 0; i--) {
              const node = copy[i];
              if (node.type === "tool_step" && node.tool === d.tool && node.status === "running") {
                copy[i] = { ...node, status: failed ? "error" : "done", output: d.output, parsed: d.parsed };
                break;
              }
            }
            return copy;
          });
          if ((d.tool === "set_plan" || d.tool === "update_plan") && d.parsed?.plan) {
            setPlan(d.parsed.plan as PlanStep[]);
          }
        }
      }, controller.signal, thinkingLevel);

      // 流结束：冲刷最后一帧（节流定时器里的内容立即落 UI）
      if (streamTimerRef.current) {
        clearTimeout(streamTimerRef.current);
        streamTimerRef.current = null;
        applyReply();
      } else {
        // 无文本事件（仅工具调用）：清掉空助手占位气泡，只留工具步骤
        setMessages((m) =>
          m.some((x) => x.type === "assistant" && !x.content && !x.reasoning)
            ? m.filter((x) => !(x.type === "assistant" && !x.content && !x.reasoning))
            : m
        );
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
      if (r?.needs_summary && r?.session_id) {
        summarizeSession(r.session_id as string).catch(() => {});
      }
      setTimeout(() => onFinish?.(), 50);
    } catch (e) {
      if ((e as Error).name === "AbortError") return;
      setMessages((m) => {
        const copy = [...m];
        copy[copy.length - 1] = { type: "assistant", content: `⚠️ 出错了：${(e as Error).message}` };
        return copy;
      });
    } finally {
      applyBusy(false);
    }
  }

  /** 静默中止：会话切换/卸载时用，不追加「已停止」提示。 */
  const abortStream = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    applyBusy(false);
  }, [applyBusy]);

  const stop = useCallback(() => {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    applyBusy(false);
    setMessages((m) => {
      // 清掉空助手占位气泡后追加停止提示（工具步骤保留）
      const copy = m.filter((x) => !(x.type === "assistant" && !x.content && !x.reasoning));
      copy.push({ type: "system", content: "⏹️ 已停止本次任务" });
      return copy;
    });
  }, [applyBusy, setMessages]);

  return { send, stop, abortStream, busy };
}
