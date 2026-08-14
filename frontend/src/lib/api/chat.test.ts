import { describe, it, expect, vi } from "vitest";
import { chatStream } from "./chat";

function sseResponse(chunks: string[]) {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c));
      controller.close();
    },
  });
  return new Response(stream, { status: 200 });
}

describe("chatStream SSE 解析", () => {
  it("解析 text 事件并流式回调", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: text\ndata: {"text":"你好"}\n\n',
      'event: text\ndata: {"text":"世界"}\n\n',
    ]));
    const events: [string, unknown][] = [];
    await chatStream("hi", [], null, [], (e, d) => events.push([e, d]), new AbortController().signal);
    expect(events).toEqual([
      ["text", { text: "你好" }],
      ["text", { text: "世界" }],
    ]);
  });

  it("跨 chunk 分割的 SSE 事件正确缓冲", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: tex', 't\ndata: {"text":"跨', '块"}\n\n',
    ]));
    const texts: string[] = [];
    await chatStream("hi", [], null, [], (e, d) => {
      if (e === "text") texts.push((d as { text: string }).text);
    }, new AbortController().signal);
    expect(texts).toEqual(["跨块"]);
  });

  it("done 事件作为返回值", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: done\ndata: {"session_id":"s1"}\n\n',
    ]));
    const r = await chatStream("hi", [], null, [], () => {}, new AbortController().signal);
    expect(r).toEqual({ session_id: "s1" });
  });

  it("HTTP 错误抛异常", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response("err", { status: 500 }));
    await expect(chatStream("hi", [], null, [], () => {}, new AbortController().signal))
      .rejects.toThrow("HTTP 500");
  });
});
