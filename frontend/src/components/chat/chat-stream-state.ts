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
  return [
    ...messages,
    { type: "tool_step", status: "running", content: "", ...data } as Message,
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
  const failed = isFailedResult(trace.parsed);
  for (let index = copy.length - 1; index >= 0; index -= 1) {
    const message = copy[index];
    if (message.type === "tool_step" && message.tool === trace.tool && message.status === "running") {
      copy[index] = {
        ...message,
        status: failed ? "error" : "done",
        output: trace.output,
        parsed: trace.parsed,
      };
      break;
    }
  }
  return copy;
}

export function planFromToolTrace(trace: ToolTrace): PlanStep[] | null {
  if (trace.tool !== "set_plan" && trace.tool !== "update_plan") return null;
  if (!isRecord(trace.parsed) || !Array.isArray(trace.parsed.plan)) return null;
  return trace.parsed.plan as PlanStep[];
}

function isEmptyAssistantMessage(message: Message | undefined): boolean {
  return message?.type === "assistant" && !message.content && !message.reasoning;
}

function isHistoryMessage(message: Message): message is Message & { type: "user" | "assistant" } {
  return message.type === "user" || message.type === "assistant";
}

function isFailedResult(value: ToolTrace["parsed"]): boolean {
  return isRecord(value) && value.ok === false;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
