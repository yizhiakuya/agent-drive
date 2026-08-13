import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import ChatPanel from "./ChatPanel.jsx";
import FilePanel from "../files/FilePanel.jsx";

// mock API 模块
vi.mock("../../api/chat.js", () => ({
  chatStream: vi.fn(() => new Promise(() => {})), // 挂起：模拟执行中的流
}));
vi.mock("../../api/files.js", () => ({
  listFiles: vi.fn(async () => ({ path: "/", items: [{ name: "a.txt", path: "a.txt", is_dir: false, size: 10 }], disk: { used: 1, total: 100, free: 99 } })),
  uploadFile: vi.fn(),
}));
vi.mock("../../api/sessions.js", () => ({
  getSession: vi.fn(async () => ({ messages: [] })),
  summarizeSession: vi.fn(async () => ({})),
}));
vi.mock("react-markdown", () => ({ default: ({ children }) => <div>{children}</div> }));

describe("产品体验闭环", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("busy 时显示停止按钮（发送按钮被替换）", async () => {
    render(<ChatPanel />);
    const input = screen.getByPlaceholderText(/和你的 Agent 对话/);
    fireEvent.change(input, { target: { value: "测试" } });
    fireEvent.click(screen.getByText("发送"));
    await act(async () => {});
    // 发送后 busy=true → 显示"⏹ 停止"
    expect(screen.getByText(/停止/)).toBeInTheDocument();
  });

  it("FilePanel 监听 files-changed 事件自动刷新", async () => {
    const { listFiles } = await import("../../api/files.js");
    render(<FilePanel />);
    await act(async () => {});
    const callsBefore = listFiles.mock.calls.length;
    await act(async () => {
      window.dispatchEvent(new CustomEvent("agent-drive:files-changed"));
    });
    expect(listFiles.mock.calls.length).toBeGreaterThan(callsBefore);
  });

  it("FilePanel 折叠切换", async () => {
    render(<FilePanel />);
    await act(async () => {});
    const panel = document.querySelector(".file-panel");
    expect(panel.classList.contains("collapsed")).toBe(false);
    fireEvent.click(screen.getByTitle("收起"));
    expect(panel.classList.contains("collapsed")).toBe(true);
  });
});
