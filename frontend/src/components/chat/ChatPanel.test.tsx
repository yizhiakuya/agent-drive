import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAppStore } from "@/lib/store";

// 需要被测组件：mock 掉组件依赖的网络层，只保留纯组件行为。
import ChatPanel from "./ChatPanel";

const chatStream = vi.fn();
vi.mock("@/lib/api/chat", () => ({
  chatStream: (...args: unknown[]) => chatStream(...args),
}));

const getConfig = vi.fn(async () => ({
  configured: true,
  llm: { type: "openai_compat", base_url: "https://example.com/v1", model: "default-model", api_key_masked: "sk-..." },
  embeddings: null,
}));
const listModels = vi.fn(async () => ({ ok: true, models: ["default-model", "fast-model"] }));
vi.mock("@/lib/api/config", () => ({
  getConfig: () => getConfig(),
  listModels: () => listModels(),
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
  const promise = new Promise<T>((settle) => { resolve = settle; });
  return { promise, resolve };
}

describe("ChatPanel 主流程", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAppStore.getState().setSessionId(null);
    getSession.mockReset();
    getSession.mockResolvedValue({ messages: [] });
    // jsdom 未实现 scrollIntoView，组件在发送/结束时调用它。
    Element.prototype.scrollIntoView = vi.fn();
    // 默认无报告，避免 automation/latest 拉取干扰断言。
    api.mockResolvedValue({ report: null });
    getConfig.mockResolvedValue({
      configured: true,
      llm: { type: "openai_compat", base_url: "https://example.com/v1", model: "default-model", api_key_masked: "sk-..." },
      embeddings: null,
    });
    listModels.mockResolvedValue({ ok: true, models: ["default-model", "fast-model"] });
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

    expect(await screen.findByText("高风险操作确认")).toBeInTheDocument();
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
      expect(screen.getByText("高风险操作确认")).toBeInTheDocument();
    });
    expect(screen.getByText("delete_file")).toBeInTheDocument();
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
});
