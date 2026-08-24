import { afterEach, describe, it, expect, vi } from "vitest";
import { EV } from "@/lib/events";
import { ApiError } from "./client";
import { chatReconnect, chatStream } from "./chat";

function sseResponse(chunks: string[], init?: ResponseInit) {
  const encoder = new TextEncoder();
  return byteResponse(chunks.map((c) => encoder.encode(c)), init);
}

function byteResponse(chunks: Uint8Array[], init?: ResponseInit) {
  const stream = new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(c);
      controller.close();
    },
  });
  return new Response(stream, { status: 200, ...init });
}

afterEach(() => vi.restoreAllMocks());

describe("chatStream SSE 解析", () => {
  it("从响应头回传服务端新建的会话 ID", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: done\ndata: {"session_id":"header-session"}\n\n',
    ], { headers: { "X-Session-ID": "header-session" } }));
    const onSessionId = vi.fn();

    await chatStream("hi", [], null, [], () => {}, new AbortController().signal,
      "auto", [], "", [], "auto", [], onSessionId);

    expect(onSessionId).toHaveBeenCalledWith("header-session");
  });

  it("重连接口复用同一套 SSE 解析器", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: text\ndata: {"text":"继续输出"}\n\n',
      'event: done\ndata: {"session_id":"reconnected"}\n\n',
    ]));
    const events: string[] = [];

    const result = await chatReconnect("reconnected", (event) => events.push(event), new AbortController().signal);

    expect(events).toEqual(["text", "done"]);
    expect(result).toEqual({ session_id: "reconnected" });
    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/chat/reconnected/stream"),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
  });

  it("把选定模型放入聊天请求体", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: done\ndata: {"session_id":"model-session"}\n\n',
    ]));

    await chatStream("hi", [], null, [], () => {}, new AbortController().signal,
      "auto", [], "fast-model");

    const init = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(init.body))).toMatchObject({ model: "fast-model" });
  });

  it("把权限模式放入聊天请求体", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: done\ndata: {"session_id":"permission-session"}\n\n',
    ]));

    await chatStream("执行", [], null, [], () => {}, new AbortController().signal,
      "auto", [], "", [], "ask");

    const init = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(init.body))).toMatchObject({ permission_mode: "ask" });
  });

  it("把内联图片 MIME 字段转换为后端的 snake_case 契约", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: done\ndata: {"session_id":"image-session"}\n\n',
    ]));

    await chatStream("描述图片", [], null, [], () => {}, new AbortController().signal,
      "auto", [], "", [], "auto", [{ name: "screen.png", mediaType: "image/png", data: "AQI=" }]);

    const init = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    const body = JSON.parse(String(init.body));
    expect(body).toMatchObject({
      inline_images: [{ name: "screen.png", media_type: "image/png", data: "AQI=" }],
    });
    expect(body.inline_images[0]).not.toHaveProperty("mediaType");
  });

  it("把 owner 文件上下文路径放入聊天请求体", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: done\ndata: {"session_id":"file-session"}\n\n',
    ]));

    await chatStream("总结附件", [], null, [], () => {}, new AbortController().signal,
      "auto", [], "", ["notes/today.md", "photos"]);

    const init = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit;
    expect(JSON.parse(String(init.body))).toMatchObject({
      file_context: ["notes/today.md", "photos"],
    });
  });

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

  it("reasoning 事件按 {text} 契约独立回调（不混入正文）", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: reasoning\ndata: {"text":"先"}\n\n',
      'event: reasoning\ndata: {"text":"判断范围"}\n\n',
      'event: text\ndata: {"text":"结果如下"}\n\n',
    ]));
    const events: [string, unknown][] = [];
    const reasoningChunks: string[] = [];
    await chatStream("hi", [], null, [], (e, d) => {
      events.push([e, d]);
      if (e === "reasoning") reasoningChunks.push((d as { text: string }).text);
    }, new AbortController().signal);
    expect(events).toEqual([
      ["reasoning", { text: "先" }],
      ["reasoning", { text: "判断范围" }],
      ["text", { text: "结果如下" }],
    ]);
    expect(reasoningChunks.join("")).toBe("先判断范围");
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

  it("支持 CRLF、多行 data 与流末尾无空行", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: text\r\ndata: {"text":\r\ndata: "多行"}\r\n\r\n',
      'event: done\ndata: {"session_id":"tail"}',
    ]));
    const events: [string, unknown][] = [];
    const result = await chatStream("hi", [], null, [], (e, d) => events.push([e, d]), new AbortController().signal);
    expect(events).toEqual([
      ["text", { text: "多行" }],
      ["done", { session_id: "tail" }],
    ]);
    expect(result).toEqual({ session_id: "tail" });
  });

  it("CRLF 分隔符跨 chunk 且 event 有空白时仍正确解析", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      "event: done \r", "\ndata: {\"session_id\":\"split-crlf\"}\r", "\n\r", "\n",
    ]));
    const events: [string, unknown][] = [];
    const result = await chatStream("hi", [], null, [], (e, d) => events.push([e, d]), new AbortController().signal);
    expect(events).toEqual([["done", { session_id: "split-crlf" }]]);
    expect(result).toEqual({ session_id: "split-crlf" });
  });

  it("支持纯 CR、注释、未知字段和跨 chunk 的尾 CR", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      ': heartbeat\runknown: ignored\revent: text\rdata: {"text":"纯CR"}\r',
      '\revent: done\rdata: {"session_id":"cr"}\r',
    ]));
    const events: [string, unknown][] = [];
    const result = await chatStream("hi", [], null, [], (e, d) => events.push([e, d]), new AbortController().signal);
    expect(events).toEqual([
      ["text", { text: "纯CR" }],
      ["done", { session_id: "cr" }],
    ]);
    expect(result).toEqual({ session_id: "cr" });
  });

  it("格式错误的 JSON 带事件上下文抛出", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      "event: text\ndata: {bad}\n\n",
    ]));
    await expect(chatStream("hi", [], null, [], () => {}, new AbortController().signal))
      .rejects.toThrow("SSE text 数据格式错误: {bad}");
  });

  it("SSE error 事件进入异常路径而不是被当作正常结束", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: error\ndata: {"error":"Provider 连接超时"}\n\n',
    ]));
    await expect(chatStream("hi", [], null, [], () => {}, new AbortController().signal))
      .rejects.toThrow("Provider 连接超时");
  });

  it("SSE error 事件保留服务端会话 ID", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: error\ndata: {"error":"上游限流","session_id":"failed-session"}\n\n',
    ]));
    await expect(chatStream("hi", [], null, [], () => {}, new AbortController().signal))
      .rejects.toMatchObject({ message: "上游限流", sessionId: "failed-session" });
  });

  it("缺少错误消息的 SSE error 使用稳定兜底文案", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: error\ndata: {}\n\n',
    ]));
    await expect(chatStream("hi", [], null, [], () => {}, new AbortController().signal))
      .rejects.toThrow("chat stream failed");
  });

  it("没有响应流时明确报错", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    await expect(chatStream("hi", [], null, [], () => {}, new AbortController().signal))
      .rejects.toThrow("empty response body");
  });

  it("UTF-8 字符跨字节 chunk 仍正确解码", async () => {
    const bytes = new TextEncoder().encode('event: text\ndata: {"text":"你好"}\n\n');
    const chinese = bytes.findIndex((value) => value >= 0xe0);
    global.fetch = vi.fn().mockResolvedValue(byteResponse([
      bytes.slice(0, chinese + 1),
      bytes.slice(chinese + 1, chinese + 4),
      bytes.slice(chinese + 4),
    ]));
    const events: [string, unknown][] = [];
    await chatStream("hi", [], null, [], (e, d) => events.push([e, d]), new AbortController().signal);
    expect(events).toEqual([["text", { text: "你好" }]]);
  });

  it("done 事件作为返回值", async () => {
    global.fetch = vi.fn().mockResolvedValue(sseResponse([
      'event: done\ndata: {"session_id":"s1"}\n\n',
    ]));
    const r = await chatStream("hi", [], null, [], () => {}, new AbortController().signal);
    expect(r).toEqual({ session_id: "s1" });
  });

  it("401 派发全局未授权事件并保留后端 detail", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: "令牌已吊销" }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    }));
    const listener = vi.fn();
    window.addEventListener(EV.unauthorized, listener);
    try {
      await expect(chatStream("hi", [], null, [], () => {}, new AbortController().signal))
        .rejects.toMatchObject({ status: 401, message: "令牌已吊销" });
      try {
        await chatStream("hi", [], null, [], () => {}, new AbortController().signal);
      } catch (error) {
        expect(error).toBeInstanceOf(ApiError);
      }
      expect(listener).toHaveBeenCalledTimes(2);
    } finally {
      window.removeEventListener(EV.unauthorized, listener);
    }
  });

  it("HTTP 错误抛异常", async () => {
    global.fetch = vi.fn().mockResolvedValue(new Response("err", { status: 500 }));
    await expect(chatStream("hi", [], null, [], () => {}, new AbortController().signal))
      .rejects.toThrow("HTTP 500");
  });
});
