import { describe, expect, it, vi } from "vitest";
import { dispatchChatStreamEvent, isFileMutationTrace } from "./chat-stream-dispatch";

function handlers() {
  return {
    frame: {
      appendText: vi.fn(),
      appendReasoning: vi.fn(),
      beginToolStep: vi.fn(),
      flush: vi.fn(() => false),
      cancel: vi.fn(),
    },
    setMessages: vi.fn(),
    setPlan: vi.fn(),
    setContextUsage: vi.fn(),
    onFrontendAction: vi.fn(),
  };
}

describe("dispatchChatStreamEvent", () => {
  it("routes text, reasoning and frontend actions to their handlers", () => {
    const target = handlers();
    dispatchChatStreamEvent({ type: "text", delta: "你好" }, target);
    dispatchChatStreamEvent({ type: "reasoning", delta: "先判断" }, target);
    dispatchChatStreamEvent({ type: "frontend_action", data: { operation: "files.open" } }, target);

    expect(target.frame.appendText).toHaveBeenCalledWith("你好");
    expect(target.frame.appendReasoning).toHaveBeenCalledWith("先判断");
    expect(target.onFrontendAction).toHaveBeenCalledWith({ operation: "files.open" });
  });

  it("appends context injections as sourced messages", () => {
    const target = handlers();
    dispatchChatStreamEvent({
      type: "context",
      context: { source: "AGENT.md", kind: "agent-instructions", content: "rules" },
    }, target);

    expect(target.setMessages).toHaveBeenCalledTimes(1);
    const update = target.setMessages.mock.calls[0][0] as (messages: unknown[]) => unknown[];
    expect(update([{ type: "assistant", content: "" }])).toEqual([{
      type: "context",
      source: "AGENT.md",
      contextKind: "agent-instructions",
      content: "rules",
    }, { type: "assistant", content: "" }]);
  });

  it("updates live context usage and compression state", () => {
    const target = handlers();
    dispatchChatStreamEvent({
      type: "context_usage",
      usage: { used: 12000, total: 262144, percent: 4.6, estimated: true, compacted: true },
    }, target);
    expect(target.setContextUsage).toHaveBeenCalledWith({
      used: 12000, total: 262144, percent: 4.6, estimated: true, compacted: true,
    });
  });

  it("updates tool steps and plans without changing stream parsing", () => {
    const target = handlers();
    dispatchChatStreamEvent({ type: "tool_start", data: { tool: "list_files", arguments: {} } }, target);
    dispatchChatStreamEvent({ type: "tool_progress", data: {
      step: 1, tool: "list_files", phase: "running", message: "正在列出文件", elapsed_ms: 1200,
    } }, target);
    dispatchChatStreamEvent({
      type: "tool_trace",
      trace: { tool: "set_plan", output: "{}", parsed: { plan: [{ step: "读取文件", status: "in_progress" }] } },
    }, target);

    expect(target.frame.beginToolStep).toHaveBeenCalledTimes(1);
    expect(target.setMessages).toHaveBeenCalledTimes(3);
    expect(target.setPlan).toHaveBeenCalledWith([{ step: "读取文件", status: "in_progress" }]);
  });

  it("recognizes current backend_api mutation operations for file refresh", () => {
    expect(isFileMutationTrace({
      tool: "backend_api",
      output: "{}",
      parsed: { operation: "POST /api/v1/files/mkdir", ok: true },
    })).toBe(true);
    expect(isFileMutationTrace({
      tool: "backend_api",
      output: "{}",
      parsed: { operation: "GET /api/v1/files", ok: true },
    })).toBe(false);
  });

  it("keeps partial index activity distinct from failed activity", () => {
    const target = handlers();
    dispatchChatStreamEvent({
      type: "tool_start",
      data: { step: 1, tool: "backend_api", operation: "PUT /api/v1/index/vision", started_at: Date.now() },
    }, target);
    dispatchChatStreamEvent({
      type: "tool_trace",
      trace: { step: 1, tool: "backend_api", output: "{}", parsed: {
        ok: false, status: "partial", operation: "PUT /api/v1/index/vision", items: [{ indexed: true }], skipped: 1,
      } },
    }, target);
    // The assertion is intentionally event-level; activity persistence is external state.
    expect(target.setMessages).toHaveBeenCalledTimes(2);
  });
});
