import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import FilePage from "./FilePage.jsx";

vi.mock("../../api/files.js", () => ({
  listFiles: vi.fn(async () => ({
    path: "",
    items: [
      { name: "资料", path: "资料", is_dir: true, size: 0, mtime: 1750000000 },
      { name: "合同.txt", path: "合同.txt", is_dir: false, size: 75, mtime: 1750000000 },
    ],
    disk: { used: 1024, total: 1e9, free: 9e8 },
  })),
  uploadFile: vi.fn(),
}));

describe("FilePage 文件管理页", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    global.fetch = vi.fn(async () => ({
      ok: true,
      json: async () => ({ path: "合同.txt", size: 75, modified: 1750000000, preview_kind: "text", snippet: "房屋租赁合同", indexed: null }),
    }));
  });

  it("渲染目录列表 + 磁盘信息", async () => {
    render(<FilePage />);
    await act(async () => {});
    expect(screen.getByText("资料")).toBeInTheDocument();
    expect(screen.getByText("合同.txt")).toBeInTheDocument();
    expect(screen.getByText(/已用/)).toBeInTheDocument();
  });

  it("点击文件显示预览面板（fetch info）", async () => {
    render(<FilePage />);
    await act(async () => {});
    fireEvent.click(screen.getByText("合同.txt"));
    await act(async () => {});
    expect(global.fetch).toHaveBeenCalledWith(expect.stringContaining("/api/v1/files/info?path="));
    expect(screen.getByText("房屋租赁合同")).toBeInTheDocument();
  });

  it("面包屑显示当前路径 + 根目录", async () => {
    render(<FilePage />);
    await act(async () => {});
    expect(screen.getByText("🏠 根目录")).toBeInTheDocument();
  });
});
