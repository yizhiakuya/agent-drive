import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAppStore } from "@/lib/store";

// 需要被测组件：mock 掉组件依赖的网络层，只保留纯组件行为。
import ChatPanel from "./ChatPanel";

const chatStream = vi.fn();
const chatRunActive = vi.fn(async () => ({ active: false }));
const chatReconnect = vi.fn(async () => null);
const cancelChatRun = vi.fn(async () => ({ cancelled: true }));
vi.mock("@/lib/api/chat", () => ({
  chatStream: (...args: unknown[]) => chatStream(...args),
  chatRunActive: (...args: unknown[]) => { void args; return chatRunActive(); },
  chatReconnect: (...args: unknown[]) => { void args; return chatReconnect(); },
  cancelChatRun: (...args: unknown[]) => { void args; return cancelChatRun(); },
}));

const getConfig = vi.fn(async () => ({
  configured: true,
  llm: { type: "openai_compat", base_url: "https://example.com/v1", model: "default-model", api_key_masked: "sk-...", supports_images: true },
  embeddings: null,
}));
const listModels = vi.fn(async () => ({ ok: true, models: ["default-model", "fast-model"] }));
vi.mock("@/lib/api/config", () => ({
  getConfig: () => getConfig(),
  listModels: () => listModels(),
}));

const listFiles = vi.fn(async (path = "", query = "", mode = "name") => {
  void query;
  void mode;
  return {
    path,
    items: [{ name: "today.md", path: "notes/today.md", is_dir: false, size: 12 }],
    disk: null,
  };
});
const uploadFile = vi.fn(async (file?: File, path?: string) => {
  void path;
  return { uploaded: { path: `聊天附件/${file?.name || "photo.jpg"}`, size: file?.size || 12 }, indexed: null };
});
vi.mock("@/lib/api/files", () => ({
  listFiles: (...args: [string?, string?, string?]) => listFiles(...args),
  uploadFile: (...args: [File?, string?]) => uploadFile(...args),
}));

const api = vi.fn(async (path: string, options?: RequestInit) => { void path; void options; return { report: null }; });
vi.mock("@/lib/api/client", () => ({
  api: (...args: [string, RequestInit?]) => api(...args),
}));

const getSession = vi.fn(async (sid: string): Promise<{ messages: { role: string; content: string }[] }> => {
  void sid;
  return { messages: [] };
});
const summarizeSession = vi.fn(async (sid: string) => { void sid; return {}; });
vi.mock("@/lib/api/sessions", () => ({
  getSession: (...args: [string]) => getSession(...args),
  summarizeSession: (...args: [string]) => summarizeSession(...args),
}));

async function typeAndSend(text: string) {
  const ta = screen.getByPlaceholderText("和你的 Agent 对话…");
  fireEvent.change(ta, { target: { value: text } });
  fireEvent.keyDown(ta, { key: "Enter" });
  await act(async () => {});
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((settle, fail) => {
    resolve = settle;
    reject = fail;
  });
  return { promise, resolve, reject };
}

describe("ChatPanel 主流程", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAppStore.getState().setSessionId(null);
    localStorage.removeItem("agent-drive-permission-mode");
    getSession.mockReset();
    getSession.mockResolvedValue({ messages: [] });
    // jsdom 未实现 scrollIntoView，组件在发送/结束时调用它。
    Element.prototype.scrollIntoView = vi.fn();
    // 默认无报告，避免 automation/latest 拉取干扰断言。
    api.mockResolvedValue({ report: null });
    getConfig.mockResolvedValue({
      configured: true,
      llm: { type: "openai_compat", base_url: "https://example.com/v1", model: "default-model", api_key_masked: "sk-...", supports_images: true },
      embeddings: null,
    });
    listModels.mockResolvedValue({ ok: true, models: ["default-model", "fast-model"] });
    listFiles.mockResolvedValue({
      path: "",
      items: [{ name: "today.md", path: "notes/today.md", is_dir: false, size: 12 }],
      disk: null,
    });
    useAppStore.setState({ frontendActions: [] });
  });

  afterEach(() => {
    useAppStore.getState().setSessionId(null);
    vi.restoreAllMocks();
  });

  it("发送后用户消息上屏", async () => {
    chatStream.mockImplementation(() => new Promise(() => {}));
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("帮我找文件");
    expect(screen.getByText("帮我找文件")).toBeInTheDocument();
    expect(chatStream).toHaveBeenCalledTimes(1);
  });

  it("忽略过期的会话历史响应", async () => {
    const first = deferred<{ messages: { role: string; content: string }[] }>();
    const second = deferred<{ messages: { role: string; content: string }[] }>();
    getSession.mockImplementation((sid: string) => sid === "session-a" ? first.promise : second.promise);

    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-a"); });
    await waitFor(() => expect(getSession).toHaveBeenCalledWith("session-a"));

    await act(async () => { useAppStore.getState().setSessionId("session-b"); });
    await waitFor(() => expect(getSession).toHaveBeenCalledWith("session-b"));

    second.resolve({ messages: [{ role: "assistant", content: "B 会话历史" }] });
    await waitFor(() => expect(screen.getByText("B 会话历史")).toBeInTheDocument());

    first.resolve({ messages: [{ role: "assistant", content: "A 会话历史" }] });
    await act(async () => { await first.promise; });
    expect(screen.getByText("B 会话历史")).toBeInTheDocument();
    expect(screen.queryByText("A 会话历史")).not.toBeInTheDocument();
  });

  it("进入会话后默认滚动到最新历史", async () => {
    getSession.mockResolvedValue({ messages: [{ role: "assistant", content: "最新历史回复" }] });
    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-latest"); });
    expect(await screen.findByText("最新历史回复")).toBeInTheDocument();
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
  });

  it("加载旧版 backend_api 历史时保留嵌套失败状态", async () => {
    getSession.mockResolvedValue({
      messages: [{
        role: "tool_call",
        content: "{\"ok\":true,\"result\":{\"ok\":false,\"detail\":\"视觉服务不可用\"}}",
        tool: "backend_api",
        arguments: { action: "call" },
        parsed: { ok: true, result: { ok: false, detail: "视觉服务不可用" } },
      }],
    } as never);
    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-legacy-tool-error"); });
    const step = await screen.findByText("backend_api");
    fireEvent.click(step);
    expect(await screen.findByText(/视觉服务不可用/)).toBeInTheDocument();
    expect(screen.getByText(/失败/)).toBeInTheDocument();
  });

  it("Agent 运行期间流式内容更新会持续跟随底部", async () => {
    vi.useFakeTimers();
    try {
      let emit!: (event: string, data: Record<string, unknown>) => void;
      chatStream.mockImplementation((_msg, _history, _sid, _confirmations, onEvent) => {
        emit = onEvent;
        return new Promise(() => {});
      });
      render(<ChatPanel />);
      await act(async () => {});
      await typeAndSend("持续任务");
      const before = (Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>).mock.calls.length;
      await act(async () => {
        emit("text", { text: "实时更新" });
        vi.advanceTimersByTime(80);
      });
      expect(screen.getByText("实时更新")).toBeInTheDocument();
      expect((Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(before);
    } finally {
      vi.useRealTimers();
    }
  });

  it("重新进入会话时恢复持久化的上下文用量", async () => {
    getSession.mockResolvedValue({
      meta: { context_usage: { used: 1800, total: 262144, percent: 0.69, input: 1600, output: 200 } },
      messages: [{ role: "assistant", content: "之前的回复" }],
    } as never);
    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-persisted-usage"); });
    await waitFor(() => expect(screen.getByTestId("context-usage-summary")).toHaveTextContent("1.8K / 262.1K"));
  });

  it("旧会话没有用量记录时显示消息估算而不是零", async () => {
    getSession.mockResolvedValue({
      messages: [{ role: "assistant", content: "这是一段已有历史消息，用于估算上下文窗口。" }],
    } as never);
    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-legacy-usage"); });
    await waitFor(() => expect(screen.getByTestId("context-usage-summary")).toHaveTextContent("估算"));
    expect(screen.getByTestId("context-usage-summary")).not.toHaveTextContent("估算 0 / 262.1K");
  });

  it("切换会话不会中止原会话流，返回后从持久历史收敛", async () => {
    const completion = deferred<Record<string, unknown>>();
    let emit!: (event: string, data: Record<string, unknown>) => void;
    let signal!: AbortSignal;
    let finished = false;
    chatStream.mockImplementation((_message, _history, _sid, _confirmations, onEvent, activeSignal) => {
      emit = onEvent;
      signal = activeSignal;
      return completion.promise;
    });
    getSession.mockImplementation(async (sid: string) => ({
      messages: sid === "session-a" && finished
        ? [{ role: "assistant", content: "后台回复完成" }]
        : sid === "session-b"
          ? [{ role: "assistant", content: "B 会话" }]
          : [],
    }));

    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-a"); });
    await waitFor(() => expect(getSession).toHaveBeenCalledWith("session-a"));
    await typeAndSend("长任务");

    await act(async () => { useAppStore.getState().setSessionId("session-b"); });
    expect(await screen.findByText("B 会话")).toBeInTheDocument();
    expect(signal.aborted).toBe(false);
    await act(async () => { emit("text", { text: "后台回复完成" }); });
    expect(screen.queryByText("后台回复完成")).not.toBeInTheDocument();

    await act(async () => { useAppStore.getState().setSessionId("session-a"); });
    finished = true;
    await act(async () => { completion.resolve({ session_id: "session-a" }); });
    expect(await screen.findByText("后台回复完成")).toBeInTheDocument();
    expect(signal.aborted).toBe(false);
  });

  it("不同会话可以各自保持一个活动流", async () => {
    const first = deferred<Record<string, unknown>>();
    const second = deferred<Record<string, unknown>>();
    const signals: AbortSignal[] = [];
    chatStream.mockImplementation((_message, _history, sid, _confirmations, _onEvent, signal) => {
      signals.push(signal);
      return sid === "session-a" ? first.promise : second.promise;
    });

    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-a"); });
    await typeAndSend("A 任务");
    await act(async () => { useAppStore.getState().setSessionId("session-b"); });
    await typeAndSend("B 任务");

    expect(chatStream).toHaveBeenCalledTimes(2);
    expect(signals).toHaveLength(2);
    expect(signals.every((item) => !item.aborted)).toBe(true);
    await act(async () => {
      first.resolve({ session_id: "session-a" });
      second.resolve({ session_id: "session-b" });
    });
  });

  it("从会话历史恢复可展开的上下文注入", async () => {
    getSession.mockResolvedValue({
      messages: [{
        role: "context",
        content: "Follow workspace rules",
        context_source: "AGENT.md",
        context_kind: "agent-instructions",
      }],
    } as never);

    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-context"); });

    const disclosure = await screen.findByTestId("context-injection");
    expect(disclosure).not.toHaveAttribute("open");
    expect(disclosure).toHaveTextContent("上下文注入");
    expect(disclosure).toHaveTextContent("AGENT.md");
    fireEvent.click(disclosure.querySelector("summary")!);
    expect(disclosure).toHaveAttribute("open");
  });

  it("从会话详情恢复后台生成的待确认操作", async () => {
    getSession.mockResolvedValue({
      meta: {
        pending_confirmation: {
          tool: "backend_api",
          arguments: { operation: "DELETE /api/v1/sessions/{sessionId}" },
          nonce: "nonce",
          ts: 1,
          signature: "signature",
        },
      },
      messages: [],
    } as never);

    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-pending"); });

    expect(await screen.findByText("需要你的批准")).toBeInTheDocument();
  });

  it("聊天输入区保持紧凑并提供稳定的聚焦反馈", async () => {
    render(<ChatPanel />);
    await act(async () => {});

    const composer = screen.getByTestId("chat-composer");
    const inputBar = screen.getByTestId("chat-input-bar");
    const textarea = screen.getByPlaceholderText("和你的 Agent 对话…");
    expect(inputBar).not.toHaveClass("border-t", "border-border");
    expect(composer).toHaveClass("transition-[border-color,box-shadow]", "has-[[data-slot=chat-input]:focus]:ring-2", "has-[[data-slot=chat-input]:focus]:ring-accent/10");
    expect(composer).not.toHaveClass("focus-within:border-accent");
    expect(textarea).toHaveClass("py-0.5");
    expect(textarea).toHaveAttribute("rows", "1");
    expect(textarea).toHaveAttribute("data-slot", "chat-input");

    textarea.focus();
    expect(textarea).toHaveFocus();
  });

  it("权限控件可切换模式、保存选择并随请求发送", async () => {
    chatStream.mockResolvedValue(null);
    render(<ChatPanel />);
    await act(async () => {});

    const summary = screen.getByTestId("permission-summary");
    expect(summary).toHaveAccessibleName("权限模式：帮我批准");
    fireEvent.click(summary);
    expect(screen.getByRole("dialog", { name: "权限模式" })).toHaveTextContent("请求批准");
    fireEvent.click(screen.getByRole("menuitemradio", { name: "请求批准" }));
    expect(summary).toHaveAccessibleName("权限模式：请求批准");
    expect(localStorage.getItem("agent-drive-permission-mode")).toBe("ask");

    fireEvent.click(summary);
    expect(screen.getByRole("dialog", { name: "权限模式" })).toBeInTheDocument();
    fireEvent.pointerDown(document.body);
    expect(screen.getByTestId("permission-control")).not.toHaveAttribute("open");

    await typeAndSend("执行操作");
    expect(chatStream.mock.calls[0][10]).toBe("ask");
  });

  it("权限控件从本地设置恢复完全访问模式", async () => {
    localStorage.setItem("agent-drive-permission-mode", "full");
    render(<ChatPanel />);
    await act(async () => {});
    expect(screen.getByTestId("permission-summary")).toHaveAccessibleName("权限模式：完全访问");
  });

  it("切换到完全访问时收起上一轮遗留的确认卡", async () => {
    chatStream.mockResolvedValue({
      pending_confirmation: {
        tool: "delete_file",
        arguments: { path: "a.txt" },
        nonce: "n1",
        ts: 1,
        signature: "sig",
      },
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("删除 a.txt");
    await waitFor(() => expect(screen.getByText("需要你的批准")).toBeInTheDocument());
    fireEvent.click(screen.getByTestId("permission-summary"));
    fireEvent.click(screen.getByRole("menuitemradio", { name: "完全访问" }));
    await waitFor(() => expect(screen.queryByText("需要你的批准")).not.toBeInTheDocument());
  });

  it("不在输入框下方显示旧的快捷操作按钮", async () => {
    render(<ChatPanel />);
    await act(async () => {});
    expect(screen.getAllByRole("button", { name: "看看网盘里有什么" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "按内容找文件" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "整理文件" })).toHaveLength(1);
  });

  it("滚离底部时显示居中的圆形回到最新按钮", async () => {
    render(<ChatPanel />);
    await act(async () => {});
    const list = screen.getByTestId("chat-message-list");
    Object.defineProperties(list, {
      scrollHeight: { configurable: true, value: 1200 },
      scrollTop: { configurable: true, value: 0 },
      clientHeight: { configurable: true, value: 500 },
    });
    fireEvent.scroll(list);
    const button = screen.getByRole("button", { name: "回到最新消息" });
    expect(button).toHaveClass("size-8", "rounded-full");
    expect(screen.queryByText("最新")).not.toBeInTheDocument();
  });

  it("text 事件累积为助手消息", async () => {
    chatStream.mockResolvedValue(null);
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("你好");
    // 流已结束：节流定时器也会在收尾时 flush，直接推进宏任务即可。
    expect(screen.getByText("你好")).toBeInTheDocument();
  });

  it("text 事件受 80ms 节流：定时器冲刷前不逐 token 更新", async () => {
    vi.useFakeTimers();
    try {
      chatStream.mockImplementation((_msg, _h, _s, _c, onEvent: (e: string, d: Record<string, unknown>) => void) => {
        onEvent("text", { text: "你" });
        onEvent("text", { text: "好" });
        return new Promise(() => {}); // 挂起，不复位定时器
      });
      render(<ChatPanel />);
      await act(async () => {});
      await typeAndSend("hello");
      // 两个 token 到达后，80ms 定时器尚未触发，助手内容应仍为空。
      expect(screen.queryByText("你好")).not.toBeInTheDocument();
      // 推进 80ms：定时器触发，累积内容一次性上屏。
      await act(async () => { vi.advanceTimersByTime(80); });
      expect(screen.getByText("你好")).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("tool_start / tool_trace 生成工具步骤", async () => {
    chatStream.mockImplementation((_msg, _h, _s, _c, onEvent: (e: string, d: Record<string, unknown>) => void) => {
      onEvent("tool_start", { tool: "list_files", arguments: {} });
      onEvent("tool_trace", { tool: "list_files", output: "[]", parsed: [{ name: "a.txt", is_dir: false, size: 1 }] });
      return Promise.resolve(null);
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("看看有什么文件");
    // tool_start 挂起 running 步骤，tool_trace 将其置为 done。
    expect(screen.getByText("list_files")).toBeInTheDocument();
    // ToolStep 完成态徽标包含“完成”（内含图标与空白，用正则匹配文字节点）
    expect(screen.getByText(/完成/)).toBeInTheDocument();
  });

  it("完成后显示服务端收敛的本轮总耗时", async () => {
    chatStream.mockResolvedValue({ latency_ms: 2345, total_elapsed_ms: 2345 });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("执行一个快速检查");
    await waitFor(() => expect(screen.getByText("本轮 00:02")).toBeInTheDocument());
    expect(screen.getByLabelText("本轮任务耗时 00:02")).toBeInTheDocument();
  });

  it("工具步骤后文本回复不残留空助手占位气泡", async () => {
    chatStream.mockImplementation((_msg, _h, _s, _c, onEvent: (e: string, d: Record<string, unknown>) => void) => {
      onEvent("tool_start", { tool: "list_files", arguments: {} });
      onEvent("tool_trace", { tool: "list_files", output: "[]", parsed: [] });
      onEvent("text", { text: "已列出文件" });
      return Promise.resolve(null);
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("看看有什么文件");
    expect(screen.getByText("list_files")).toBeInTheDocument();
    expect(screen.getByText("已列出文件")).toBeInTheDocument();
    // 空占位气泡被清掉：markdown-body 只剩最终回复一个
    expect(document.querySelectorAll(".markdown-body").length).toBe(1);
  });

  it("工具步骤之间的 reasoning 不重复上一模型轮次", async () => {
    chatStream.mockImplementation(async (_msg, _h, _s, _c, onEvent: (e: string, d: Record<string, unknown>) => void) => {
      onEvent("reasoning", { text: "第一步" });
      await new Promise((resolve) => setTimeout(resolve, 120));
      onEvent("tool_start", { tool: "list_files", arguments: {} });
      onEvent("tool_trace", { tool: "list_files", output: "[]", parsed: [] });
      onEvent("reasoning", { text: "第二步" });
      await new Promise((resolve) => setTimeout(resolve, 120));
      onEvent("tool_start", { tool: "index_stats", arguments: {} });
      onEvent("tool_trace", { tool: "index_stats", output: "{}", parsed: {} });
      onEvent("text", { text: "完成" });
      return null;
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("执行多步任务");
    await waitFor(() => expect(screen.getAllByTestId("reasoning-block")).toHaveLength(2), { timeout: 2000 });

    const blocks = screen.getAllByTestId("reasoning-block");
    expect(blocks).toHaveLength(2);
    expect(blocks.map((block) => block.querySelector(".markdown-body")?.textContent ?? "").sort())
      .toEqual(["第一步", "第二步"].sort());
  });

  it("仅工具调用无文本回复时移除空占位气泡", async () => {
    chatStream.mockImplementation((_msg, _h, _s, _c, onEvent: (e: string, d: Record<string, unknown>) => void) => {
      onEvent("tool_start", { tool: "list_files", arguments: {} });
      onEvent("tool_trace", { tool: "list_files", output: "[]", parsed: [] });
      return Promise.resolve(null);
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("看看有什么文件");
    expect(screen.getByText("list_files")).toBeInTheDocument();
    expect(document.querySelectorAll(".markdown-body").length).toBe(0);
  });

  it("done 返回 pending_confirmation 时弹确认框", async () => {
    chatStream.mockResolvedValue({
      pending_confirmation: {
        tool: "delete_file",
        arguments: { path: "a.txt" },
        nonce: "n1",
        ts: 1,
        signature: "sig",
      },
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("删除 a.txt");
    await waitFor(() => {
      expect(screen.getByText("需要你的批准")).toBeInTheDocument();
    });
    expect(screen.getByText("delete_file")).toBeInTheDocument();
  });

  it("完成响应的真实上下文用量替换默认零值", async () => {
    chatStream.mockResolvedValue({
      context_usage: { used: 1800, total: 262144, percent: 0.69, input: 1600, output: 200 },
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("你好");
    expect(screen.getByTestId("context-usage-summary")).toHaveTextContent("1.8K / 262.1K");
    fireEvent.click(screen.getByTestId("context-usage-summary"));
    expect(screen.getByTestId("context-usage-details")).toHaveTextContent("本轮输入");
    expect(screen.getByTestId("context-usage-details")).toHaveTextContent("200");
  });

  it("流错误显示错误文案", async () => {
    chatStream.mockRejectedValue(new Error("后端连接失败"));
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("测试错误");
    await waitFor(() => {
      expect(screen.getByText(/出错了：后端连接失败/)).toBeInTheDocument();
    });
  });

  it("流错误保留服务端会话并阻止后续请求重复创建", async () => {
    chatStream.mockRejectedValueOnce(Object.assign(new Error("上游限流"), { sessionId: "failed-session" }))
      .mockResolvedValueOnce(null);
    render(<ChatPanel />);
    await act(async () => {});

    await typeAndSend("第一次提问");
    expect(await screen.findByText(/出错了：上游限流/)).toBeInTheDocument();
    expect(useAppStore.getState().sessionId).toBe("failed-session");

    await typeAndSend("第二次提问");
    expect(chatStream.mock.calls[1][2]).toBe("failed-session");
  });

  it("后台会话失败只刷新会话列表且不污染当前会话", async () => {
    const completion = deferred<Record<string, unknown>>();
    chatStream.mockImplementation(() => completion.promise);
    getSession.mockImplementation(async (sid: string) => ({
      messages: sid === "session-b"
        ? [{ role: "assistant", content: "B 会话仍在前台" }]
        : [],
    }));

    render(<ChatPanel />);
    await act(async () => { useAppStore.getState().setSessionId("session-a"); });
    await typeAndSend("A 后台任务");
    await act(async () => { useAppStore.getState().setSessionId("session-b"); });
    expect(await screen.findByText("B 会话仍在前台")).toBeInTheDocument();

    await act(async () => {
      completion.reject(Object.assign(new Error("A 会话失败"), { sessionId: "session-a" }));
      try { await completion.promise; } catch { /* 由流错误分支处理。 */ }
    });

    expect(useAppStore.getState().sessionId).toBe("session-b");
    expect(screen.getByText("B 会话仍在前台")).toBeInTheDocument();
    expect(screen.queryByText(/A 会话失败/)).not.toBeInTheDocument();
  });

  it("部分正文后流失败时保留正文且错误不被迟到帧覆盖", async () => {
    chatStream.mockImplementation((_msg, _h, _s, _c, onEvent: (e: string, d: Record<string, unknown>) => void) => {
      onEvent("text", { text: "已经生成的部分正文" });
      return Promise.reject(new Error("连接中断"));
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("测试部分失败");

    await waitFor(() => expect(screen.getByText("已经生成的部分正文")).toBeInTheDocument());
    expect(screen.getByText("出错了：连接中断")).toBeInTheDocument();
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 100)); });
    expect(screen.getByText("已经生成的部分正文")).toBeInTheDocument();
    expect(screen.getByText("出错了：连接中断")).toBeInTheDocument();
  });

  it("工具步骤后流失败时保留工具轨迹并追加错误", async () => {
    chatStream.mockImplementation((_msg, _h, _s, _c, onEvent: (e: string, d: Record<string, unknown>) => void) => {
      onEvent("tool_start", { tool: "list_files", arguments: {} });
      onEvent("tool_trace", { tool: "list_files", output: "[]", parsed: [] });
      return Promise.reject(new Error("工具后连接中断"));
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("测试工具后失败");

    await waitFor(() => expect(screen.getByText("list_files")).toBeInTheDocument());
    expect(screen.getByText(/完成/)).toBeInTheDocument();
    expect(screen.getByText("出错了：工具后连接中断")).toBeInTheDocument();
  });

  it("停止按钮中止流（AbortController）", async () => {
    const abortSpy = vi.spyOn(AbortController.prototype, "abort");
    chatStream.mockImplementation((_msg, _h, _s, _c, _onEvent: unknown, signal: AbortSignal) => {
      // 只监听 signal，不立即结束，模拟长任务。
      return new Promise((_resolve, reject) => {
        signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")));
      });
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("长时间任务");
    const stopBtn = await screen.findByText("停止");
    fireEvent.click(stopBtn);
    await act(async () => {});
    expect(abortSpy).toHaveBeenCalled();
    expect(screen.getByText("已停止本次任务。")).toBeInTheDocument();
  });

  it("reasoning 默认收叠并支持展开", async () => {
    chatStream.mockImplementation((_msg, _h, _s, _c, onEvent: (e: string, d: Record<string, unknown>) => void) => {
      onEvent("reasoning", { text: "先判断文件范围。" });
      onEvent("text", { text: "结果如下。" });
      return Promise.resolve(null);
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("帮我分析文件");

    const details = screen.getByTestId("reasoning-block");
    expect(details).not.toHaveAttribute("open");
    fireEvent.click(screen.getByText("思考过程"));
    expect(details).toHaveAttribute("open");
    expect(screen.getByText("先判断文件范围。")).toBeInTheDocument();
  });

  it("选择思考等级后随请求发送", async () => {
    chatStream.mockResolvedValue(null);
    render(<ChatPanel />);
    await act(async () => {});
    fireEvent.click(screen.getByRole("combobox", { name: "思考等级" }));
    fireEvent.click(screen.getByText("深度", { exact: true }));
    await typeAndSend("复杂任务");
    expect(chatStream.mock.calls[0][6]).toBe("high");
  });

  it("选择聊天模型后随请求发送", async () => {
    chatStream.mockResolvedValue(null);
    render(<ChatPanel />);
    await act(async () => {});

    const modelInput = screen.getByRole("combobox", { name: "聊天模型" });
    fireEvent.focus(modelInput);
    const modelTrigger = modelInput
      .closest('[data-slot="input-group"]')
      ?.querySelector('button');
    expect(modelTrigger).not.toBeNull();
    fireEvent.click(modelTrigger!);
    await waitFor(() => expect(listModels).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole("option", { name: "fast-model" })).toBeInTheDocument());
    const modelPopup = document.querySelector('[data-slot="combobox-content"]');
    expect(modelPopup).toHaveAttribute("data-side", "top");
    expect(modelPopup).toHaveClass("min-w-[min(16rem,calc(100vw-2rem))]");
    fireEvent.click(screen.getByRole("option", { name: "fast-model" }));

    await typeAndSend("用快速模型回答");
    expect(chatStream.mock.calls[0][8]).toBe("fast-model");
  });

  it("@ 选择文件后随请求发送 owner 文件上下文", async () => {
    chatStream.mockResolvedValue(null);
    render(<ChatPanel />);
    await act(async () => {});
    const textarea = screen.getByPlaceholderText("和你的 Agent 对话…");
    fireEvent.change(textarea, { target: { value: "请总结 @" } });
    expect(await screen.findByRole("option", { name: /notes\/today\.md/ })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: /notes\/today\.md/ }));
    expect(screen.getByLabelText("已附加文件")).toHaveTextContent("notes/today.md");
    fireEvent.keyDown(textarea, { key: "Enter" });
    await act(async () => {});
    expect(chatStream.mock.calls[0][9]).toEqual(["notes/today.md"]);
  });

  it("可将右侧文件拖到附件区并按 owner 路径附加", async () => {
    chatStream.mockResolvedValue(null);
    render(<ChatPanel />);
    await act(async () => {});
    const composer = screen.getByTestId("chat-composer");
    const data = JSON.stringify({ name: "合同.txt", path: "资料/合同.txt", is_dir: false, size: 75 });
    const dataTransfer = {
      types: ["application/x-agent-drive-file"],
      getData: (type: string) => type === "application/x-agent-drive-file" ? data : "",
      dropEffect: "none",
    };

    fireEvent.dragEnter(composer, { dataTransfer });
    expect(composer).toHaveClass("border-accent");
    fireEvent.drop(composer, { dataTransfer });

    expect(screen.getByLabelText("已附加文件")).toHaveTextContent("资料/合同.txt");
    const textarea = screen.getByPlaceholderText("和你的 Agent 对话…");
    fireEvent.change(textarea, { target: { value: "请查看这个附件" } });
    fireEvent.keyDown(textarea, { key: "Enter" });
    await waitFor(() => expect(chatStream).toHaveBeenCalled());
    expect(chatStream.mock.calls[0][9]).toEqual(["资料/合同.txt"]);
  });

  it("@ 文件夹默认进入浏览，点击子文件后才引用", async () => {
    chatStream.mockResolvedValue(null);
    listFiles.mockImplementation(async (path = "") => path === "projects"
      ? {
          path,
          items: [{ name: "readme.md", path: "projects/readme.md", is_dir: false, size: 20 }],
          disk: null,
        }
      : {
          path,
          items: [{ name: "projects", path: "projects", is_dir: true, size: 0 }],
          disk: null,
        });
    render(<ChatPanel />);
    await act(async () => {});
    const textarea = screen.getByPlaceholderText("和你的 Agent 对话…");
    fireEvent.change(textarea, { target: { value: "查看 @" } });

    fireEvent.click(await screen.findByRole("button", { name: "进入文件夹 projects" }));
    await waitFor(() => expect(listFiles).toHaveBeenCalledWith("projects", "", "name"));
    expect(screen.queryByLabelText("已附加文件")).not.toBeInTheDocument();
    expect(textarea).toHaveValue("查看 @projects/");

    fireEvent.click(await screen.findByRole("option", { name: /projects\/readme\.md/ }));
    expect(screen.getByLabelText("已附加文件")).toHaveTextContent("projects/readme.md");
  });

  it("@ 文件夹提供显式的引用整个文件夹动作", async () => {
    listFiles.mockResolvedValue({
      path: "",
      items: [{ name: "projects", path: "projects", is_dir: true, size: 0 }],
      disk: null,
    });
    render(<ChatPanel />);
    await act(async () => {});
    const textarea = screen.getByPlaceholderText("和你的 Agent 对话…");
    fireEvent.change(textarea, { target: { value: "总结 @" } });
    fireEvent.click(await screen.findByRole("button", { name: "引用文件夹 projects" }));
    expect(screen.getByLabelText("已附加文件")).toHaveTextContent("projects");
    expect(textarea).toHaveValue("总结 @projects ");
  });

  it("可直接粘贴图片作为本轮 Base64 内联内容，发送后仍显示并可预览", async () => {
    // 浏览器对同一张剪贴板图片的 items/files 视图可能给出不同的 lastModified。
    const imageFromItem = new File(["png"], "截图.png", { type: "image/png", lastModified: 101 });
    const imageFromFiles = new File(["png"], "截图.png", { type: "image/png", lastModified: 202 });
    render(<ChatPanel />);
    await act(async () => {});
    const textarea = screen.getByPlaceholderText("和你的 Agent 对话…");
    fireEvent.paste(textarea, {
      clipboardData: {
        items: [{ kind: "file", type: "image/png", getAsFile: () => imageFromItem }],
        files: [imageFromFiles],
        getData: () => "",
      },
    });
    await waitFor(() => expect(screen.getByLabelText("已附加图片")).toHaveTextContent("截图.png"));
    expect(screen.getByLabelText("已附加图片").querySelectorAll("img")).toHaveLength(1);
    expect(uploadFile).not.toHaveBeenCalled();
    expect(screen.queryByLabelText("已附加文件")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(chatStream).toHaveBeenCalled());
    expect(chatStream.mock.calls[0][11]).toEqual([
      expect.objectContaining({ name: "截图.png", mediaType: "image/png", data: expect.any(String) }),
    ]);
    const sentImages = screen.getByLabelText("已发送图片");
    expect(sentImages.querySelectorAll("img")).toHaveLength(1);
    fireEvent.click(within(sentImages).getByRole("button", { name: "预览已发送图片 截图.png" }));
    expect(screen.getByRole("dialog", { name: "预览图片 截图.png" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "关闭图片预览" }));
    expect(screen.queryByRole("dialog", { name: "预览图片 截图.png" })).not.toBeInTheDocument();
  });

  it("接受 5 MiB 图片且不再按旧的 4 MiB 上限拦截", async () => {
    const image = new File([new Uint8Array(5 * 1024 * 1024)], "5MiB.png", { type: "image/png" });
    render(<ChatPanel />);
    await act(async () => {});
    fireEvent.paste(screen.getByPlaceholderText("和你的 Agent 对话…"), {
      clipboardData: {
        items: [{ kind: "file", type: "image/png", getAsFile: () => image }],
        files: [image],
        getData: () => "",
      },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "预览图片 5MiB.png" })).toBeInTheDocument());
    expect(screen.queryByText(/单张上限 4 MiB/)).not.toBeInTheDocument();
    expect(uploadFile).not.toHaveBeenCalled();
  });

  it("当前模型不支持图片时阻止粘贴图片进入请求", async () => {
    getConfig.mockResolvedValueOnce({
      configured: true,
      llm: { type: "openai_compat", base_url: "https://example.com/v1", model: "deepseek-v3", api_key_masked: "sk-...", supports_images: false },
      embeddings: null,
    });
    const image = new File(["png"], "截图.png", { type: "image/png" });
    render(<ChatPanel />);
    await act(async () => {});
    fireEvent.paste(screen.getByPlaceholderText("和你的 Agent 对话…"), {
      clipboardData: {
        items: [{ kind: "file", type: "image/png", getAsFile: () => image }],
        files: [image],
        getData: () => "",
      },
    });
    await act(async () => {});
    expect(screen.queryByLabelText("已附加图片")).not.toBeInTheDocument();
    expect(uploadFile).not.toHaveBeenCalled();
  });

  it("点击已附加图片可打开预览并关闭，发送前仍保留原图", async () => {
    const image = new File(["png"], "核实.png", { type: "image/png" });
    render(<ChatPanel />);
    await act(async () => {});
    const textarea = screen.getByPlaceholderText("和你的 Agent 对话…");
    fireEvent.paste(textarea, {
      clipboardData: {
        items: [{ kind: "file", type: "image/png", getAsFile: () => image }],
        files: [image],
        getData: () => "",
      },
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "预览图片 核实.png" })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "预览图片 核实.png" }));
    expect(screen.getByRole("dialog", { name: "预览图片 核实.png" })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "预览图片 核实.png" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "预览图片 核实.png" }));
    fireEvent.click(screen.getByRole("button", { name: "关闭图片预览" }));
    expect(screen.queryByRole("dialog", { name: "预览图片 核实.png" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "预览图片 核实.png" })).toBeInTheDocument();
  });

  it("回答中的文件引用转换为可打开的前端动作", async () => {
    chatStream.mockImplementation((_msg, _history, _sid, _confirmations, onEvent: (event: string, data: Record<string, unknown>) => void) => {
      onEvent("text", { text: "请查看 [[file:notes/today.md]]" });
      return Promise.resolve(null);
    });
    render(<ChatPanel />);
    await act(async () => {});
    await typeAndSend("给我文件");
    const reference = screen.getByRole("button", { name: /打开文件 notes\/today\.md/ });
    fireEvent.click(reference);
    expect(useAppStore.getState().frontendActions[0]).toMatchObject({
      operation: "files.open",
      arguments: { path: "notes/today.md" },
    });
  });
});
