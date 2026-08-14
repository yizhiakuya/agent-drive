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

describe("FilePanel 导航与预览", () => {
  it("点击文件显示预览面板（fetch info + 下载链接）", async () => {
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ path: "合同.txt", size: 75, modified: 1750000000, preview_kind: "text", snippet: "房屋租赁合同内容", indexed: null }),
    }));
    const { listFiles } = await import("../../api/files.js");
    listFiles.mockResolvedValueOnce({
      path: "",
      items: [
        { name: "资料", path: "资料", is_dir: true, size: 0, mtime: 1750000000 },
        { name: "合同.txt", path: "合同.txt", is_dir: false, size: 75, mtime: 1750000000 },
      ],
      disk: { used: 1, total: 100, free: 99 },
    });
    render(<FilePanel />);
    await act(async () => {});
    fireEvent.click(screen.getByText("合同.txt"));
    await act(async () => {});
    expect(global.fetch).toHaveBeenCalledWith(expect.stringContaining("/api/v1/files/info?path="));
    expect(screen.getByText("房屋租赁合同内容")).toBeInTheDocument();
    expect(screen.getByTitle("关闭预览")).toBeInTheDocument();
  });

  it("双击目录进入 + 面包屑显示路径", async () => {
    const { listFiles } = await import("../../api/files.js");
    listFiles
      .mockResolvedValueOnce({
        path: "",
        items: [{ name: "资料", path: "资料", is_dir: true, size: 0, mtime: 0 }],
        disk: null,
      })
      .mockResolvedValueOnce({
        path: "资料",
        items: [],
        disk: null,
      });
    render(<FilePanel />);
    await act(async () => {});
    fireEvent.doubleClick(screen.getByText("资料"));
    await act(async () => {});
    expect(screen.getByText("资料")).toBeInTheDocument(); // 面包屑
    expect(screen.getByTitle("返回上级")).toBeInTheDocument();
  });
});
