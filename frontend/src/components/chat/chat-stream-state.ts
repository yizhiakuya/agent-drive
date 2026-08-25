import type { PlanStep } from "./PlanCard";
import type { ChatHistoryMessage, Message } from "./chat-types";
import type { ToolTrace } from "./chat-stream-events";

export function buildChatHistory(messages: Message[]): ChatHistoryMessage[] {
  return messages
    .filter(isHistoryMessage)
    .map((message) => ({ role: message.type, content: message.content }))
    .slice(-80);
}

export function replaceAssistantMessage(messages: Message[], content: string, reasoning: string): Message[] {
  const assistant: Message = {
    type: "assistant",
    content,
    ...(reasoning ? { reasoning } : {}),
  };
  if (messages.at(-1)?.type === "tool_step") {
    return [...removeEmptyAssistantMessages(messages), assistant];
  }
  if (messages.at(-1)?.type === "assistant") {
    return [...messages.slice(0, -1), assistant];
  }
  return [...messages, assistant];
}

export function removeEmptyAssistantMessages(messages: Message[]): Message[] {
  // 工具调用可能先创建空 assistant 占位；工具步骤或停止提示已能表达过程时应移除它，避免空白气泡。
  return messages.filter((message) => !isEmptyAssistantMessage(message));
}

export function appendToolStep(messages: Message[], data: Record<string, unknown>): Message[] {
  const startedAt = typeof data.started_at === "number" ? data.started_at : undefined;
  return [
    ...messages,
    {
      type: "tool_step",
      status: "running",
      content: "",
      ...data,
      ...(startedAt === undefined ? {} : { startedAt }),
    } as Message,
  ];
}

export function appendContextMessage(messages: Message[], context: Message): Message[] {
  if (isEmptyAssistantMessage(messages.at(-1))) {
    return [...messages.slice(0, -1), context, messages.at(-1)!];
  }
  return [...messages, context];
}

export function completeToolStep(messages: Message[], trace: ToolTrace): Message[] {
  const copy = [...messages];
  const failed = isFailedToolResult(trace.parsed);
  for (let index = copy.length - 1; index >= 0; index -= 1) {
    const message = copy[index];
    if (message.type === "tool_step" && message.tool === trace.tool && message.status === "running") {
      copy[index] = {
        ...message,
        status: failed ? "error" : "done",
        ...(typeof trace.startedAt === "number" ? { startedAt: trace.startedAt } : {}),
        ...(typeof trace.elapsedMs === "number" ? { elapsedMs: trace.elapsedMs } : {}),
        output: trace.output,
        parsed: trace.parsed,
      };
      break;
    }
  }
  return copy;
}

/** 将长工具调用的阶段/耗时更新写入最近一个 running 工具步骤。 */
export function updateToolProgress(messages: Message[], data: Record<string, unknown>): Message[] {
  const tool = typeof data.tool === "string" ? data.tool : "";
  const step = typeof data.step === "number" ? data.step : null;
  const copy = [...messages];
  for (let index = copy.length - 1; index >= 0; index -= 1) {
    const message = copy[index];
    if (message.type !== "tool_step" || message.status !== "running" || message.tool !== tool) continue;
    if (step !== null && message.step !== undefined && message.step !== step) continue;
    copy[index] = {
      ...message,
      ...(typeof data.message === "string" ? { progressMessage: data.message } : {}),
      ...(typeof data.phase === "string" ? { progressPhase: data.phase } : {}),
      ...(typeof data.elapsed_ms === "number" ? { elapsedMs: data.elapsed_ms } : {}),
    };
    break;
  }
  return copy;
}

/** 识别当前及旧版 backend_api envelope 中的业务失败。 */
export function failedToolResult(value: unknown): Record<string, unknown> | null {
  if (!isRecord(value)) return null;
  if (value.ok === false) return value;
  const nested = value.result;
  return isRecord(nested) && nested.ok === false ? nested : null;
}

/** backend_api 旧版会把 dispatcher 失败嵌在 result 中，历史记录也必须显示失败。 */
export function isFailedToolResult(value: unknown): boolean {
  return failedToolResult(value) !== null;
}

/** 返回工具失败的可读详情，优先使用稳定错误说明而不是泛化错误码。 */
export function toolFailureDetail(value: unknown): string {
  const failure = failedToolResult(value);
  if (!failure) return "工具执行失败";
  for (const key of ["detail", "message", "error", "code"]) {
    if (typeof failure[key] === "string" && failure[key]) return failure[key] as string;
  }
  return "工具执行失败";
}

export function planFromToolTrace(trace: ToolTrace): PlanStep[] | null {
  if (trace.tool !== "plan" && trace.tool !== "set_plan" && trace.tool !== "update_plan") return null;
  if (!isRecord(trace.parsed) || !Array.isArray(trace.parsed.plan)) return null;
  return trace.parsed.plan as PlanStep[];
}

function isEmptyAssistantMessage(message: Message | undefined): boolean {
  return message?.type === "assistant" && !message.content && !message.reasoning;
}

function isHistoryMessage(message: Message): message is Message & { type: "user" | "assistant" } {
  return message.type === "user" || message.type === "assistant";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
