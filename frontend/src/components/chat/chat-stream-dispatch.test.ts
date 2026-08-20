import { describe, expect, it, vi } from "vitest";
import { dispatchChatStreamEvent } from "./chat-stream-dispatch";

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

  it("updates tool steps and plans without changing stream parsing", () => {
    const target = handlers();
    dispatchChatStreamEvent({ type: "tool_start", data: { tool: "list_files", arguments: {} } }, target);
    dispatchChatStreamEvent({
      type: "tool_trace",
      trace: { tool: "set_plan", output: "{}", parsed: { plan: [{ step: "读取文件", status: "in_progress" }] } },
    }, target);

    expect(target.frame.beginToolStep).toHaveBeenCalledTimes(1);
    expect(target.setMessages).toHaveBeenCalledTimes(2);
    expect(target.setPlan).toHaveBeenCalledWith([{ step: "读取文件", status: "in_progress" }]);
  });
});
