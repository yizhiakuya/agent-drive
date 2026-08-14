import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import FilePanel from "./FilePanel";

vi.mock("@/lib/api/files", () => ({
  listFiles: vi.fn(async () => ({
    path: "/",
    items: [
      { name: "资料", path: "资料", is_dir: true, size: 0, mtime: 1750000000 },
      { name: "合同.txt", path: "合同.txt", is_dir: false, size: 75, mtime: 1750000000 },
    ],
    disk: { used: 1, total: 100, free: 99 },
  })),
  uploadFile: vi.fn(),
  getFileInfo: vi.fn(async () => ({
    path: "合同.txt", size: 75, modified: 1750000000,
    preview_kind: "text", snippet: "房屋租赁合同内容", indexed: null,
  })),
  fileRawUrl: (p: string) => `/raw?path=${p}`,
  fileDownloadUrl: (p: string) => `/dl?path=${p}`,
}));

describe("FilePanel（Next 版）", () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it("渲染文件列表", async () => {
    render(<FilePanel />);
    await act(async () => {});
    expect(screen.getByText("资料")).toBeInTheDocument();
    expect(screen.getByText("合同.txt")).toBeInTheDocument();
    expect(screen.getByText(/已用/)).toBeInTheDocument();
  });

  it("点击文件显示预览", async () => {
    render(<FilePanel />);
    await act(async () => {});
    fireEvent.click(screen.getByText("合同.txt"));
    await act(async () => {});
    expect(screen.getByText("房屋租赁合同内容")).toBeInTheDocument();
    expect(screen.getByText("✕")).toBeInTheDocument();
  });

  it("折叠切换", async () => {
    render(<FilePanel />);
    await act(async () => {});
    fireEvent.click(screen.getByTitle("收起"));
    // collapsed 后文件列表隐藏
    expect(screen.queryByText("合同.txt")).not.toBeInTheDocument();
  });

  it("监听 files-changed 事件自动刷新", async () => {
    const { listFiles } = await import("@/lib/api/files");
    render(<FilePanel />);
    await act(async () => {});
    const before = (listFiles as ReturnType<typeof vi.fn>).mock.calls.length;
    await act(async () => {
      window.dispatchEvent(new CustomEvent("agent-drive:files-changed"));
    });
    expect((listFiles as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(before);
  });
});
