import { describe, expect, it } from "vitest";
import type { Message } from "./chat-types";
import { parseChatStreamEvent } from "./chat-stream-events";
import {
  appendToolStep,
  buildChatHistory,
  completeToolStep,
  planFromToolTrace,
  removeEmptyAssistantMessages,
  replaceAssistantMessage,
} from "./chat-stream-state";

describe("chat stream state helpers", () => {
  it("只把用户和助手消息转换为最近 80 条 history", () => {
    const messages: Message[] = Array.from({ length: 85 }, (_, index) => ({
      type: index % 2 === 0 ? "user" : "tool_step",
      content: String(index),
    }));
    expect(buildChatHistory(messages)).toHaveLength(43);
    expect(buildChatHistory(messages)[0]).toEqual({ role: "user", content: "0" });
  });

  it("工具步骤后追加助手回复并清理空占位", () => {
    const messages: Message[] = [
      { type: "user", content: "列文件" },
      { type: "assistant", content: "" },
      { type: "tool_step", tool: "list_files", status: "done", content: "" },
    ];
    expect(replaceAssistantMessage(messages, "完成", "思考")).toEqual([
      { type: "user", content: "列文件" },
      { type: "tool_step", tool: "list_files", status: "done", content: "" },
      { type: "assistant", content: "完成", reasoning: "思考" },
    ]);
    expect(removeEmptyAssistantMessages(messages)).toHaveLength(2);
  });

  it("只保留最后一个同名 running tool step 并标记失败状态", () => {
    const started = appendToolStep([], { tool: "list_files", arguments: {} });
    const messages = appendToolStep(started, { tool: "list_files", arguments: {} });
    const completed = completeToolStep(messages, {
      tool: "list_files",
      output: "failed",
      parsed: { ok: false, error: "denied" },
    });
    expect(completed[0].status).toBe("running");
    expect(completed[1]).toMatchObject({ status: "error", output: "failed" });
  });

  it("只从计划工具 trace 提取合法计划", () => {
    const event = parseChatStreamEvent("tool_trace", {
      tool: "set_plan",
      output: "{}",
      parsed: { plan: [{ step: "检查", status: "completed" }] },
    });
    expect(event?.type).toBe("tool_trace");
    if (event?.type === "tool_trace") {
      expect(planFromToolTrace(event.trace)).toEqual([{ step: "检查", status: "completed" }]);
    }
    expect(parseChatStreamEvent("tool_trace", { tool: "list_files" })).toBeNull();
  });
});
