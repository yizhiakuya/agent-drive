import { describe, expect, it } from "vitest";
import type { Message } from "./chat-types";
import { parseChatStreamEvent } from "./chat-stream-events";
import {
  appendToolStep,
  appendContextMessage,
  buildChatHistory,
  completeToolStep,
  planFromToolTrace,
  removeEmptyAssistantMessages,
  replaceAssistantMessage,
  isFailedToolResult,
  updateToolProgress,
  settleRunningToolSteps,
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

  it("把工具进度更新写入对应的 running 步骤", () => {
    const messages = appendToolStep([], { step: 2, tool: "backend_api", arguments: {}, started_at: 10 });
    expect(updateToolProgress(messages, {
      step: 2,
      tool: "backend_api",
      phase: "running",
      message: "正在生成文件向量",
      elapsed_ms: 2300,
    })).toEqual([expect.objectContaining({
      progressMessage: "正在生成文件向量",
      progressPhase: "running",
      elapsedMs: 2300,
    })]);
  });

  it("停止时把仍在运行的工具步骤收敛为已停止", () => {
    const messages = appendToolStep([], { step: 2, tool: "backend_api", arguments: {}, started_at: 10 });
    expect(settleRunningToolSteps(messages, "cancelled", "工具执行已停止", 2300)[0])
      .toMatchObject({ status: "cancelled", progressPhase: "cancelled", progressMessage: "工具执行已停止", elapsedMs: 2300 });
  });

  it("把上下文注入插入空助手占位之前", () => {
    const messages: Message[] = [
      { type: "user", content: "hello" },
      { type: "assistant", content: "" },
    ];
    expect(appendContextMessage(messages, {
      type: "context", source: "skill-catalog", content: "catalog",
    })).toEqual([
      { type: "user", content: "hello" },
      { type: "context", source: "skill-catalog", content: "catalog" },
      { type: "assistant", content: "" },
    ]);
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

  it("兼容旧版 backend_api 嵌套失败结果", () => {
    expect(isFailedToolResult({ ok: true, result: { ok: false, error: "provider_failed" } })).toBe(true);
    const completed = completeToolStep(
      appendToolStep([], { tool: "backend_api", arguments: {} }),
      { tool: "backend_api", output: "legacy", parsed: { ok: true, result: { ok: false } } },
    );
    expect(completed[0]).toMatchObject({ status: "error", output: "legacy" });
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
    const productionPlan = parseChatStreamEvent("tool_trace", {
      tool: "plan",
      output: "{}",
      parsed: { plan: [{ text: "读取", status: "in_progress" }] },
    });
    expect(productionPlan?.type).toBe("tool_trace");
    if (productionPlan?.type === "tool_trace") {
      expect(planFromToolTrace(productionPlan.trace)).toEqual([{ text: "读取", status: "in_progress" }]);
    }
    expect(parseChatStreamEvent("tool_trace", { tool: "list_files" })).toBeNull();
  });

  it("只接受字段完整的上下文注入事件", () => {
    expect(parseChatStreamEvent("context", {
      source: "skill-catalog",
      kind: "skill-catalog",
      content: "available skills",
      trust: "instruction",
    })).toEqual({
      type: "context",
      context: { source: "skill-catalog", kind: "skill-catalog", content: "available skills", trust: "instruction" },
    });
    expect(parseChatStreamEvent("context", { source: "skill-catalog" })).toBeNull();
  });

  it("解析工具运行进度事件", () => {
    expect(parseChatStreamEvent("tool_progress", {
      step: 2,
      tool: "backend_api",
      phase: "running",
      message: "正在生成文件向量",
      elapsed_ms: 2300,
    })).toEqual({
      type: "tool_progress",
      data: {
        step: 2,
        tool: "backend_api",
        phase: "running",
        message: "正在生成文件向量",
        elapsed_ms: 2300,
      },
    });
  });
});
