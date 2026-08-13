import { describe, it, expect, vi } from "vitest";
import { chatStream } from "./chat.js";

function mockFetch(chunks) {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c));
      controller.close();
    },
  });
  return vi.fn().mockResolvedValue({
    ok: true,
    body: stream,
  });
}

describe("chatStream SSE 解析", () => {
  it("解析完整事件流: tool_start → tool_trace → text → done", async () => {
    const sse = [
      `event: tool_start\ndata: {"step":1,"tool":"list_files","arguments":{"path":""}}\n\n`,
      `event: tool_trace\ndata: {"step":1,"tool":"list_files","arguments":{"path":""},"output":"[]"}\n\n`,
      `event: text\ndata: "网"\n\n`,
      `event: text\ndata: "盘"\n\n`,
      `event: done\ndata: {"session_id":"abc","needs_summary":false}\n\n`,
    ];
    vi.stubGlobal("fetch", mockFetch(sse));
    const events = [];
    const done = await chatStream("看看网盘", [], null, [], (ev, data) => events.push([ev, data]));
    expect(events.map((e) => e[0])).toEqual(["tool_start", "tool_trace", "text", "text", "done"]);
    expect(done.session_id).toBe("abc");
    expect(events[1][1].tool).toBe("list_files");
  });

  it("处理跨 chunk 分割的 SSE 事件（缓冲拼接）", async () => {
    // 一个事件被拆成多个 chunk 到达
    const sse = [
      `event: tool_start\ndata: {"step":1,"tool":"set`,
      `_plan","arguments":{"steps":["a","b"]}}\n\nevent: done\ndata: {"s`,
      `ession_id":"x"}\n\n`,
    ];
    vi.stubGlobal("fetch", mockFetch(sse));
    const events = [];
    const done = await chatStream("计划任务", [], null, [], (ev, data) => events.push([ev, data]));
    expect(events.map((e) => e[0])).toEqual(["tool_start", "done"]);
    expect(events[0][1].arguments.steps).toEqual(["a", "b"]);
    expect(done.session_id).toBe("x");
  });

  it("HTTP 错误时抛异常", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 500, body: null }));
    await expect(chatStream("hi", [], null, [])).rejects.toThrow("HTTP 500");
  });

  it("AbortError 正常传播（调用方静默处理）", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(Object.assign(new Error("aborted"), { name: "AbortError" })));
    await expect(chatStream("hi", [], null, [], undefined, undefined)).rejects.toMatchObject({ name: "AbortError" });
  });
});
