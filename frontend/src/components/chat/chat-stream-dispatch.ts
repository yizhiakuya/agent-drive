import type { Dispatch, SetStateAction } from "react";
import { appendContextMessage, appendToolStep, completeToolStep, planFromToolTrace, updateToolProgress } from "./chat-stream-state";
import type { PlanStep } from "./PlanCard";
import type { Message } from "./chat-types";
import type { ContextUsage } from "./chat-types";
import type { ChatStreamEvent, ToolTrace } from "./chat-stream-events";
import type { ChatStreamFrame } from "./chat-stream-frame";
import {
  finishOperationActivity,
  startOperationActivity,
  updateOperationActivity,
} from "@/lib/operation-activity";

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
  setContextUsage: Dispatch<SetStateAction<ContextUsage | null>>;
  onFrontendAction: (data: Record<string, unknown>) => void;
}

function operationFromArguments(data: Record<string, unknown>) {
  const direct = data.operation;
  if (typeof direct === "string" && direct) return direct;
  const argumentsValue = data.arguments;
  if (!argumentsValue || typeof argumentsValue !== "object" || Array.isArray(argumentsValue)) return null;
  const operation = (argumentsValue as Record<string, unknown>).operation;
  return typeof operation === "string" && operation ? operation : null;
}

function activityForOperation(operation: string | null) {
  if (!operation) return null;
  if (!/^\s*(POST|PUT|PATCH|DELETE)\s+\/api\/v1\/(files|index)(?:\/|$)/i.test(operation)
      && !/^\s*INTERNAL\s+(?:write|append)_text\b/i.test(operation)) return null;
  const normalized = operation.toLowerCase();
  if (normalized.includes("/index/vision")) return { kind: "agent-index-vision", title: "Agent 图片视觉索引" };
  if (normalized.includes("/index/vectors")) return { kind: "agent-index-vector", title: "Agent 文件向量化" };
  if (normalized.includes("/index/")) return { kind: "agent-index", title: "Agent 文件索引" };
  if (normalized.includes("/files/")) return { kind: "agent-file", title: "Agent 文件操作" };
  return { kind: "agent-operation", title: "Agent 操作" };
}

function activityIdForTool(data: Record<string, unknown>) {
  const tool = typeof data.tool === "string" ? data.tool : "tool";
  const step = typeof data.step === "number" ? data.step : 0;
  return `agent-${tool}-${step}`;
}

function traceStatus(trace: ToolTrace): "succeeded" | "partial" | "failed" {
  if (!trace.parsed || typeof trace.parsed !== "object" || Array.isArray(trace.parsed)) return "succeeded";
  const parsed = trace.parsed as Record<string, unknown>;
  const nested = parsed.result && typeof parsed.result === "object" && !Array.isArray(parsed.result)
    ? parsed.result as Record<string, unknown>
    : null;
  const result = nested || parsed;
  if (result.status === "partial") return "partial";
  if (result.ok === false || result.status === "failed") return "failed";
  return "succeeded";
}

function traceCounts(trace: ToolTrace) {
  if (!trace.parsed || typeof trace.parsed !== "object" || Array.isArray(trace.parsed)) return {};
  const parsed = trace.parsed as Record<string, unknown>;
  const nested = parsed.result && typeof parsed.result === "object" && !Array.isArray(parsed.result)
    ? parsed.result as Record<string, unknown>
    : null;
  const result = nested || parsed;
  const items = Array.isArray(result.items) ? result.items : [];
  const failed = typeof result.failed === "number" ? result.failed : undefined;
  const completed = typeof result.embedded === "number" ? result.embedded : items.length || undefined;
  return {
    ...(completed === undefined ? {} : { completed }),
    ...(items.length > 0 ? { total: items.length } : {}),
    ...(failed === undefined ? {} : { failed, succeeded: Math.max(0, (items.length || completed || 0) - failed) }),
  };
}

/**
 * 把已校验的流事件映射为消息、计划和界面动作更新。
 * 文件工具完成后额外广播 files-changed，让文件栏刷新；这属于事件副作用，不能放回纯文本帧逻辑。
 */
export function dispatchChatStreamEvent(
  event: ChatStreamEvent,
  handlers: ChatStreamEventHandlers,
) {
  const { frame, setMessages, setPlan, setContextUsage, onFrontendAction } = handlers;
  switch (event.type) {
    case "text":
      frame.appendText(event.delta);
      break;
    case "reasoning":
      frame.appendReasoning(event.delta);
      break;
    case "context_usage":
      setContextUsage(event.usage);
      break;
    case "context":
      setMessages((messages) => appendContextMessage(messages, {
        type: "context",
        source: event.context.source,
        contextKind: event.context.kind,
        ...(event.context.trust ? { contextTrust: event.context.trust } : {}),
        content: event.context.content,
      }));
      break;
    case "frontend_action":
      onFrontendAction(event.data);
      break;
    case "tool_start":
      frame.beginToolStep();
      setMessages((messages) => appendToolStep(messages, event.data));
      if (event.data.tool === "backend_api") {
        const operation = operationFromArguments(event.data);
        const meta = activityForOperation(operation);
        if (meta) startOperationActivity({
          id: activityIdForTool(event.data),
          source: "agent",
          kind: meta.kind,
          title: meta.title,
          operation: operation || undefined,
          phase: typeof event.data.progress_message === "string" ? "running" : "running",
          message: typeof event.data.progress_message === "string" ? event.data.progress_message : "正在执行操作",
          startedAt: typeof event.data.started_at === "number" ? event.data.started_at : Date.now(),
        });
      }
      break;
    case "tool_progress":
      setMessages((messages) => updateToolProgress(messages, event.data));
      if (event.data.tool === "backend_api") {
        const id = activityIdForTool(event.data);
        updateOperationActivity(id, {
          phase: typeof event.data.phase === "string" ? event.data.phase : "running",
          message: typeof event.data.message === "string" ? event.data.message : "正在执行操作",
        });
      }
      break;
    case "tool_trace": {
      setMessages((messages) => completeToolStep(messages, event.trace));
      if (event.trace.tool === "backend_api") {
        const operation = typeof event.trace.parsed === "object" && event.trace.parsed !== null && !Array.isArray(event.trace.parsed)
          ? (() => {
            const parsed = event.trace.parsed as Record<string, unknown>;
            const nested = parsed.result && typeof parsed.result === "object" && !Array.isArray(parsed.result)
              ? parsed.result as Record<string, unknown>
              : null;
            return typeof (nested || parsed).operation === "string" ? String((nested || parsed).operation) : null;
          })()
          : null;
        if (activityForOperation(operation)) {
          const id = `agent-${event.trace.tool}-${event.trace.step ?? 0}`;
          finishOperationActivity(id, traceStatus(event.trace), {
            phase: "finished",
            message: traceStatus(event.trace) === "succeeded" ? "操作已完成" : traceStatus(event.trace) === "partial" ? "操作部分完成" : "操作失败",
            ...traceCounts(event.trace),
          });
        }
      }
      const nextPlan = planFromToolTrace(event.trace);
      if (nextPlan) setPlan(nextPlan);
      break;
    }
  }
}
