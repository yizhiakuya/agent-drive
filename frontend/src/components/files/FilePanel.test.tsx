import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import FilePanel from "./FilePanel";
import type { FileInfo } from "@/lib/api/files";

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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

describe("FilePanel（Next 版）", () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it("渲染文件列表", async () => {
    render(<FilePanel />);
    await act(async () => {});
    expect(screen.getByText("资料")).toBeInTheDocument();
    expect(screen.getByText("合同.txt")).toBeInTheDocument();
    expect(screen.getByText(/已用/)).toBeInTheDocument();
    expect(screen.getByText("合同.txt").closest("[draggable=\"true\"]")).not.toBeNull();
  });

  it("点击文件显示预览", async () => {
    render(<FilePanel />);
    await act(async () => {});
    fireEvent.click(screen.getByText("合同.txt"));
    await act(async () => {});
    expect(screen.getByText("房屋租赁合同内容")).toBeInTheDocument();
    expect(screen.getByLabelText("关闭预览")).toBeInTheDocument();
  });

  it("忽略过期的文件详情响应", async () => {
    const first = deferred<FileInfo>();
    const second = deferred<FileInfo>();
    const filesApi = await import("@/lib/api/files");
    vi.mocked(filesApi.listFiles).mockResolvedValue({
      path: "/",
      items: [
        { name: "A.txt", path: "A.txt", is_dir: false, size: 1, mtime: 1750000000 },
        { name: "B.txt", path: "B.txt", is_dir: false, size: 1, mtime: 1750000000 },
      ],
      disk: { used: 1, total: 100, free: 99 },
    });
    vi.mocked(filesApi.getFileInfo).mockImplementation((path) => path === "A.txt" ? first.promise : second.promise);

    render(<FilePanel />);
    await act(async () => {});
    fireEvent.click(screen.getByText("A.txt"));
    fireEvent.click(screen.getByText("B.txt"));

    second.resolve({ name: "B.txt", path: "B.txt", size: 1, modified: 1, preview_kind: "text", snippet: "B 摘要", indexed: null });
    await act(async () => { await second.promise; });
    expect(screen.getByText("B 摘要")).toBeInTheDocument();

    first.resolve({ name: "A.txt", path: "A.txt", size: 1, modified: 1, preview_kind: "text", snippet: "A 摘要", indexed: null });
    await act(async () => { await first.promise; });
    expect(screen.getByText("B 摘要")).toBeInTheDocument();
    expect(screen.queryByText("A 摘要")).not.toBeInTheDocument();
  });

  it("折叠切换", async () => {
    render(<FilePanel />);
    await act(async () => {});
    fireEvent.click(screen.getByTitle("收起文件栏"));
    // collapsed 后文件列表隐藏
    expect(screen.queryByText("合同.txt")).not.toBeInTheDocument();
    expect(screen.getByTestId("file-panel-collapsed")).toBeInTheDocument();
  });

  it("拖动分隔轨道可以调整宽度", async () => {
    const onResize = vi.fn();
    render(<FilePanel width={320} onResize={onResize} />);
    await act(async () => {});

    const handle = screen.getByTestId("files-panel-resize-handle");
    fireEvent.pointerDown(handle, { button: 0, clientX: 500 });
    fireEvent.pointerMove(window, { clientX: 450 });
    fireEvent.pointerUp(window);

    expect(onResize).toHaveBeenLastCalledWith(370);
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
