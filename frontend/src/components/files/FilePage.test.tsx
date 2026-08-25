import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import FilePage from "./FilePage";

const listFiles = vi.fn();
const listFavorites = vi.fn();
const listRecent = vi.fn();
const listVersions = vi.fn();
const restoreVersion = vi.fn();
const setFavorite = vi.fn();
const uploadFile = vi.fn();
const getFileInfo = vi.fn();
const getFileContent = vi.fn();
const renameFile = vi.fn();
const moveFile = vi.fn();
const copyFile = vi.fn();
const mkdir = vi.fn();
const deleteToTrash = vi.fn();
const listTrash = vi.fn();
const restoreFromTrash = vi.fn();
const emptyTrash = vi.fn();

vi.mock("@/lib/api/files", () => ({
  listFiles: (...a: unknown[]) => listFiles(...a),
  listFavorites: (...a: unknown[]) => listFavorites(...a),
  listRecent: (...a: unknown[]) => listRecent(...a),
  listVersions: (...a: unknown[]) => listVersions(...a),
  restoreVersion: (...a: unknown[]) => restoreVersion(...a),
  setFavorite: (...a: unknown[]) => setFavorite(...a),
  uploadFile: (...a: unknown[]) => uploadFile(...a),
  getFileInfo: (...a: unknown[]) => getFileInfo(...a),
  getFileContent: (...a: unknown[]) => getFileContent(...a),
  renameFile: (...a: unknown[]) => renameFile(...a),
  moveFile: (...a: unknown[]) => moveFile(...a),
  copyFile: (...a: unknown[]) => copyFile(...a),
  mkdir: (...a: unknown[]) => mkdir(...a),
  deleteToTrash: (...a: unknown[]) => deleteToTrash(...a),
  listTrash: (...a: unknown[]) => listTrash(...a),
  restoreFromTrash: (...a: unknown[]) => restoreFromTrash(...a),
  emptyTrash: (...a: unknown[]) => emptyTrash(...a),
  fileDownloadUrl: (p: string) => "/dl?path=" + p,
  fileRawUrl: (p: string) => "/raw?path=" + p,
}));

const indexFiles = vi.fn();
const indexVision = vi.fn();
const vectorize = vi.fn();

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((settle, fail) => { resolve = settle; reject = fail; });
  return { promise, resolve, reject };
}

function fileInfo(path: string, snippet: string) {
  return {
    path,
    name: path,
    size: 75,
    modified: 1750000000,
    preview_kind: "text" as const,
    snippet,
    indexed: null,
  };
}

vi.mock("@/lib/api/index", () => ({
  indexFiles: (...a: unknown[]) => indexFiles(...a),
  indexVision: (...a: unknown[]) => indexVision(...a),
  vectorize: (...a: unknown[]) => vectorize(...a),
}));

// 预览面板依赖较重，测试关注文件操作主流程，直接桩掉。
vi.mock("./FilePreview", () => ({
  default: ({ text }: { text?: string }) => <div data-testid="preview">{text}</div>,
}));

const rootListing = {
  path: "",
  items: [
    { name: "资料", path: "资料", is_dir: true, size: 0, mtime: 1750000000 },
    { name: "合同.txt", path: "合同.txt", is_dir: false, size: 75, mtime: 1750000000 },
  ],
  disk: { used: 1, total: 100, free: 99 },
};

describe("FilePage 核心操作", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listFiles.mockResolvedValue(rootListing);
    listFavorites.mockResolvedValue({ ...rootListing, mode: "favorites", items: [] });
    listRecent.mockResolvedValue({ ...rootListing, mode: "recent", items: [] });
    listVersions.mockResolvedValue({ path: "合同.txt", items: [], has_more: false });
    restoreVersion.mockResolvedValue({ restored: { path: "合同.txt", size: 75 }, version_id: "v1" });
    setFavorite.mockResolvedValue({ path: "合同.txt", favorite: true });
    listTrash.mockResolvedValue({
      items: [{ path: "旧文件.txt", trash_id: "t1", deleted_at: 1750000000, size: 10, is_dir: false }],
    });
    getFileInfo.mockResolvedValue({
      path: "合同.txt", name: "合同.txt", size: 75, modified: 1750000000,
      preview_kind: "text", snippet: "内容", indexed: null,
    });
    getFileContent.mockResolvedValue({
      path: "合同.txt", name: "合同.txt", content: "完整合同内容", encoding: "UTF-8", size: 20, truncated: false,
    });
    indexFiles.mockResolvedValue({ ok: true, operation: "index.file", status: "succeeded", items: [] });
    indexVision.mockResolvedValue({ ok: true, operation: "index.vision", status: "succeeded", items: [] });
    vectorize.mockResolvedValue({ ok: true, operation: "index.vectors", vectorized: true, embedded: 1 });
    emptyTrash.mockResolvedValue({ removed: 1 });
  });

  it("渲染文件列表", async () => {
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("资料")).toBeInTheDocument());
    expect(screen.getByText("合同.txt")).toBeInTheDocument();
    expect(screen.getByText(/已用/)).toBeInTheDocument();
  });

  it("切换收藏集合并支持取消收藏", async () => {
    listFavorites.mockResolvedValue({
      ...rootListing,
      mode: "favorites",
      items: [{ ...rootListing.items[1], favorite: true }],
    });
    setFavorite.mockResolvedValue({ path: "合同.txt", favorite: false });
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /^收藏$/ }));
    await waitFor(() => expect(listFavorites).toHaveBeenCalledWith());
    expect(screen.getByRole("button", { name: "取消收藏 合同.txt" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "取消收藏 合同.txt" }));
    await waitFor(() => expect(setFavorite).toHaveBeenCalledWith("合同.txt", false));
    await waitFor(() => expect(screen.queryByText("合同.txt")).not.toBeInTheDocument());
  });

  it("显示最近访问集合并按访问记录加载", async () => {
    listRecent.mockResolvedValue({
      ...rootListing,
      mode: "recent",
      items: [{ ...rootListing.items[1], last_accessed: 1750000100, access_count: 3 }],
    });
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /^最近访问$/ }));
    await waitFor(() => expect(listRecent).toHaveBeenCalledWith());
    expect(screen.getByText("合同.txt")).toBeInTheDocument();
  });

  it("目录单击直接进入，文件单击才打开预览", async () => {
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("资料")).toBeInTheDocument());

    fireEvent.click(screen.getByText("资料"));
    await waitFor(() => expect(listFiles).toHaveBeenLastCalledWith("资料", "", "name"));
    expect(screen.getByTestId("file-selection-toolbar")).not.toHaveTextContent("已选");
  });

  it("支持多选并批量移入回收站", async () => {
    deleteToTrash.mockResolvedValue({});
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText("选择 合同.txt"));
    expect(screen.getByTestId("file-batch-toolbar")).toHaveTextContent("已选 1 项");
    fireEvent.click(screen.getByRole("button", { name: "删除", hidden: true }));
    fireEvent.click(screen.getByText("确认删除"));
    await waitFor(() => expect(deleteToTrash).toHaveBeenCalledWith("合同.txt"));
    expect(confirm).toHaveBeenCalled();
    confirm.mockRestore();
  });

  it("选择文件时操作栏保留固定高度占位", async () => {
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

    const toolbar = screen.getByTestId("file-selection-toolbar");
    expect(toolbar).toHaveClass("h-12", "sm:h-9", "overflow-hidden");
    expect(toolbar).not.toHaveTextContent("已选");

    fireEvent.click(screen.getByText("合同.txt"));

    expect(screen.getByTestId("file-selection-toolbar")).toBe(toolbar);
    expect(toolbar).toHaveTextContent("已选: 合同.txt");
    expect(toolbar).toHaveClass("h-12", "sm:h-9", "overflow-hidden");
  });

  it("忽略过期的目录列表响应", async () => {
    const first = deferred<typeof rootListing>();
    const second = deferred<typeof rootListing>();
    const directories = {
      ...rootListing,
      items: [
        { name: "目录A", path: "目录A", is_dir: true, size: 0, mtime: 1750000000 },
        { name: "目录B", path: "目录B", is_dir: true, size: 0, mtime: 1750000000 },
      ],
    };
    listFiles.mockImplementation((requestedPath: string) => {
      if (!requestedPath) return Promise.resolve(directories);
      return requestedPath === "目录A" ? first.promise : second.promise;
    });

    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("目录A")).toBeInTheDocument());
    fireEvent.doubleClick(screen.getByText("目录A"));
    fireEvent.doubleClick(screen.getByText("目录B"));

    second.resolve({
      ...rootListing,
      path: "目录B",
      items: [{ name: "B.txt", path: "目录B/B.txt", is_dir: false, size: 1, mtime: 1750000000 }],
    });
    await waitFor(() => expect(screen.getByText("B.txt")).toBeInTheDocument());

    first.resolve({
      ...rootListing,
      path: "目录A",
      items: [{ name: "A.txt", path: "目录A/A.txt", is_dir: false, size: 1, mtime: 1750000000 }],
    });
    await act(async () => { await first.promise; });
    expect(screen.getByText("B.txt")).toBeInTheDocument();
    expect(screen.queryByText("A.txt")).not.toBeInTheDocument();
  });

  it("忽略过期的文件详情响应", async () => {
    const first = deferred<ReturnType<typeof fileInfo>>();
    const second = deferred<ReturnType<typeof fileInfo>>();
    listFiles.mockResolvedValue({
      ...rootListing,
      items: [
        { name: "A.txt", path: "A.txt", is_dir: false, size: 75, mtime: 1750000000 },
        { name: "B.txt", path: "B.txt", is_dir: false, size: 75, mtime: 1750000000 },
      ],
    });
    getFileInfo.mockImplementation((requestedPath: string) =>
      requestedPath === "A.txt" ? first.promise : second.promise);

    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("A.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("A.txt"));
    fireEvent.click(screen.getByText("B.txt"));

    second.resolve(fileInfo("B.txt", "B 的摘要"));
    await waitFor(() => expect(screen.getByText("B 的摘要")).toBeInTheDocument());
    first.resolve(fileInfo("A.txt", "A 的摘要"));
    await act(async () => { await first.promise; });

    expect(screen.getByText("B 的摘要")).toBeInTheDocument();
    expect(screen.queryByText("A 的摘要")).not.toBeInTheDocument();
  });

  it("当前文件详情加载失败时发送错误 toast", async () => {
    getFileInfo.mockRejectedValue(new Error("detail unavailable"));
    const listener = vi.fn();
    window.addEventListener("agent-drive:toast", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

      fireEvent.click(screen.getByText("合同.txt"));

      await waitFor(() => expect(listener).toHaveBeenCalled());
      expect(listener.mock.calls[0][0]).toHaveProperty(
        "detail.text",
        "文件详情加载失败：Error: detail unavailable",
      );
    } finally {
      window.removeEventListener("agent-drive:toast", listener);
    }
  });

  it("过期的文件详情失败不会发送 toast", async () => {
    const first = deferred<ReturnType<typeof fileInfo>>();
    listFiles.mockResolvedValue({
      ...rootListing,
      items: [
        { name: "A.txt", path: "A.txt", is_dir: false, size: 75, mtime: 1750000000 },
        { name: "B.txt", path: "B.txt", is_dir: false, size: 75, mtime: 1750000000 },
      ],
    });
    getFileInfo.mockImplementation((requestedPath: string) => requestedPath === "A.txt"
      ? first.promise
      : Promise.resolve(fileInfo("B.txt", "B 的摘要")));
    const listener = vi.fn();
    window.addEventListener("agent-drive:toast", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("A.txt")).toBeInTheDocument());
      fireEvent.click(screen.getByText("A.txt"));
      fireEvent.click(screen.getByText("B.txt"));
      await waitFor(() => expect(screen.getByText("B 的摘要")).toBeInTheDocument());

      await act(async () => { first.reject(new Error("stale detail failure")); });

      expect(listener).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener("agent-drive:toast", listener);
    }
  });

  it("忽略当前选中文件变化后的全文响应", async () => {
    const content = deferred<{ content: string; truncated: boolean }>();
    listFiles.mockResolvedValue({
      ...rootListing,
      items: [
        { name: "A.txt", path: "A.txt", is_dir: false, size: 75, mtime: 1750000000 },
        { name: "B.txt", path: "B.txt", is_dir: false, size: 75, mtime: 1750000000 },
      ],
    });
    getFileInfo.mockImplementation((requestedPath: string) =>
      Promise.resolve(fileInfo(requestedPath, `${requestedPath} 的摘要`)));
    getFileContent.mockReturnValue(content.promise);

    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("A.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("A.txt"));
    await waitFor(() => expect(screen.getByText("A.txt 的摘要")).toBeInTheDocument());
    fireEvent.click(screen.getByTitle("查看内容"));
    fireEvent.click(screen.getByText("B.txt"));
    await waitFor(() => expect(screen.getByText("B.txt 的摘要")).toBeInTheDocument());

    content.resolve({ content: "A 的完整内容", truncated: false });
    await act(async () => { await content.promise; });
    expect(screen.getByText("B.txt 的摘要")).toBeInTheDocument();
    expect(screen.queryByText("A 的完整内容")).not.toBeInTheDocument();
  });

  it("重命名成功后派发 files-changed", async () => {
    renameFile.mockResolvedValue({});
    const listener = vi.fn();
    window.addEventListener("agent-drive:files-changed", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
      // 单击选中后出现操作条
      fireEvent.click(screen.getByText("合同.txt"));
      await act(async () => {});
      fireEvent.click(screen.getByText("重命名"));
      const input = screen.getByPlaceholderText("新名称");
      fireEvent.change(input, { target: { value: "合同2.txt" } });
      fireEvent.click(screen.getByText("确定"));
      await waitFor(() => expect(renameFile).toHaveBeenCalled());
      expect(renameFile).toHaveBeenCalledWith("合同.txt", "合同2.txt");
      expect(listener).toHaveBeenCalled();
    } finally {
      window.removeEventListener("agent-drive:files-changed", listener);
    }
  });

  it("重命名后可从 toast 撤销", async () => {
    renameFile.mockResolvedValue({});
    const listener = vi.fn();
    window.addEventListener("agent-drive:toast", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
      fireEvent.click(screen.getByText("合同.txt"));
      await waitFor(() => expect(screen.getByText("重命名")).toBeInTheDocument());
      fireEvent.click(screen.getByText("重命名"));
      fireEvent.change(screen.getByPlaceholderText("新名称"), { target: { value: "合同2.txt" } });
      fireEvent.click(screen.getByRole("button", { name: "确定" }));
      await waitFor(() => expect(renameFile).toHaveBeenCalledWith("合同.txt", "合同2.txt"));

      const toast = listener.mock.calls.at(-1)?.[0] as CustomEvent<{ action?: { onClick: () => void } }>;
      expect(toast.detail.action?.onClick).toBeTypeOf("function");
      toast.detail.action?.onClick();
      await waitFor(() => expect(renameFile).toHaveBeenCalledWith("合同2.txt", "合同.txt"));
    } finally {
      window.removeEventListener("agent-drive:toast", listener);
    }
  });

  it("移动后可从 toast 撤销到原目录", async () => {
    moveFile.mockResolvedValue({});
    const listener = vi.fn();
    window.addEventListener("agent-drive:toast", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
      fireEvent.click(screen.getByText("合同.txt"));
      await waitFor(() => expect(screen.getByText("移动")).toBeInTheDocument());
      fireEvent.click(screen.getByText("移动"));
      fireEvent.change(screen.getByPlaceholderText("目标目录（如 资料/合同）"), { target: { value: "资料" } });
      fireEvent.click(screen.getByRole("button", { name: "确定" }));
      await waitFor(() => expect(moveFile).toHaveBeenCalledWith("合同.txt", "资料"));

      const toast = listener.mock.calls.at(-1)?.[0] as CustomEvent<{ action?: { onClick: () => void } }>;
      toast.detail.action?.onClick();
      await waitFor(() => expect(moveFile).toHaveBeenCalledWith("资料/合同.txt", ""));
    } finally {
      window.removeEventListener("agent-drive:toast", listener);
    }
  });

  it("复制后可从 toast 撤销副本", async () => {
    copyFile.mockResolvedValue({});
    deleteToTrash.mockResolvedValue({ trash_id: "copy-trash" });
    const listener = vi.fn();
    window.addEventListener("agent-drive:toast", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
      fireEvent.click(screen.getByText("合同.txt"));
      await waitFor(() => expect(screen.getByText("复制到")).toBeInTheDocument());
      fireEvent.click(screen.getByText("复制到"));
      fireEvent.change(screen.getByPlaceholderText("目标目录（如 资料/合同）"), { target: { value: "资料" } });
      fireEvent.click(screen.getByRole("button", { name: "确定" }));
      await waitFor(() => expect(copyFile).toHaveBeenCalledWith("合同.txt", "资料/合同.txt"));

      const toast = listener.mock.calls.at(-1)?.[0] as CustomEvent<{ action?: { onClick: () => void } }>;
      toast.detail.action?.onClick();
      await waitFor(() => expect(deleteToTrash).toHaveBeenCalledWith("资料/合同.txt"));
    } finally {
      window.removeEventListener("agent-drive:toast", listener);
    }
  });

  it("删除后可从 toast 恢复回收站条目", async () => {
    deleteToTrash.mockResolvedValue({ trash_id: "t2" });
    restoreFromTrash.mockResolvedValue({});
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const listener = vi.fn();
    window.addEventListener("agent-drive:toast", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
      fireEvent.click(screen.getByText("合同.txt"));
      await waitFor(() => expect(screen.getByText("删除")).toBeInTheDocument());
      fireEvent.click(screen.getByText("删除"));
      fireEvent.click(screen.getByText("确认删除"));
      await waitFor(() => expect(deleteToTrash).toHaveBeenCalledWith("合同.txt"));

      const toast = listener.mock.calls.at(-1)?.[0] as CustomEvent<{ action?: { onClick: () => void } }>;
      toast.detail.action?.onClick();
      await waitFor(() => expect(restoreFromTrash).toHaveBeenCalledWith("t2"));
    } finally {
      confirm.mockRestore();
      window.removeEventListener("agent-drive:toast", listener);
    }
  });

  it("删除确认后调用 deleteToTrash", async () => {
    deleteToTrash.mockResolvedValue({});
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("合同.txt"));
    await act(async () => {});
    fireEvent.click(screen.getByText("删除"));
    // 二次确认按钮
    fireEvent.click(screen.getByText("确认删除"));
    await waitFor(() => expect(deleteToTrash).toHaveBeenCalledWith("合同.txt"));
    expect(confirm).toHaveBeenCalled();
  });

  it("上传触发（点击上传按钮 → 选择文件）", async () => {
    const file = new File(["x"], "新文件.txt", { type: "text/plain" });
    uploadFile.mockResolvedValue({ uploaded: { path: "新文件.txt", size: 1 }, indexed: null });
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [file] } });
    await waitFor(() => expect(uploadFile).toHaveBeenCalled());
    expect(uploadFile.mock.calls[0][0].name).toBe("新文件.txt");
  });

  it("失败上传保留原文件并支持重试", async () => {
    const file = new File(["x"], "重试.txt", { type: "text/plain" });
    uploadFile
      .mockRejectedValueOnce(new Error("network unavailable"))
      .mockResolvedValueOnce({ uploaded: { path: "重试.txt", size: 1 }, indexed: null });
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [file] } });
    await waitFor(() => expect(screen.getByText("失败")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "重试上传 重试.txt" }));
    await waitFor(() => expect(uploadFile).toHaveBeenCalledTimes(2));
    expect(screen.getByText("完成 1/1")).toBeInTheDocument();
  });

  it("上传队列显示真实进度并可取消正在上传的请求", async () => {
    const file = new File(["x"], "进度.txt", { type: "text/plain" });
    uploadFile.mockImplementationOnce((_: File, __: string, onProgress?: (value: number) => void, signal?: AbortSignal) =>
      new Promise((_resolve, reject) => {
        onProgress?.(42);
        signal?.addEventListener("abort", () => reject(new DOMException("上传已取消", "AbortError")), { once: true });
      }));
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [file] } });
    await waitFor(() => expect(screen.getByText("上传中 42%")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "取消上传 进度.txt" }));
    await waitFor(() => expect(screen.getByText("已取消")).toBeInTheDocument());
  });

  it("回收站恢复", async () => {
    restoreFromTrash.mockResolvedValue({});
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText("回收站"));
    await waitFor(() => expect(screen.getByText("旧文件.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("恢复"));
    await waitFor(() => expect(restoreFromTrash).toHaveBeenCalledWith("t1"));
  });

  it("当前回收站请求失败时发送错误 toast", async () => {
    listTrash.mockRejectedValue(new Error("trash unavailable"));
    const listener = vi.fn();
    window.addEventListener("agent-drive:toast", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

      fireEvent.click(screen.getByLabelText("回收站"));

      await waitFor(() => expect(listener).toHaveBeenCalled());
      expect(listener.mock.calls[0][0]).toHaveProperty(
        "detail.text",
        "回收站加载失败：Error: trash unavailable",
      );
    } finally {
      window.removeEventListener("agent-drive:toast", listener);
    }
  });

  it("回收站关闭后的迟到失败不会发送 toast", async () => {
    const trash = deferred<{ items: [] }>();
    listTrash.mockReturnValue(trash.promise);
    const listener = vi.fn();
    window.addEventListener("agent-drive:toast", listener);
    try {
      render(<FilePage />);
      await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
      fireEvent.click(screen.getByLabelText("回收站"));
      fireEvent.click(screen.getByLabelText("关闭回收站"));

      await act(async () => { trash.reject(new Error("stale trash failure")); });

      expect(listener).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener("agent-drive:toast", listener);
    }
  });

  it("回收站清空（确认后调用 emptyTrash）", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText("回收站"));
    await waitFor(() => expect(screen.getByText("旧文件.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("清空"));
    await waitFor(() => expect(emptyTrash).toHaveBeenCalled());
    expect(confirm).toHaveBeenCalled();
  });

  it("空目录显示空态文案", async () => {
    listFiles.mockResolvedValue({ ...rootListing, items: [] });
    render(<FilePage />);
    await waitFor(() =>
      expect(screen.getByText(/目录为空/)).toBeInTheDocument()
    );
  });

  it("搜索、查看详情和读取完整文本内容", async () => {
    listFiles.mockImplementation(async (_path: string, query: string) =>
      query ? { ...rootListing, items: [rootListing.items[1]], query } : rootListing
    );
    getFileInfo.mockResolvedValue({
      path: "合同.txt", name: "合同.txt", size: 75, modified: 1750000000, revision: 3,
      content_type: "text/plain", preview_kind: "text", snippet: "摘要",
      indexed: {
        text_indexed: true, vectorized: true, vector_status: "vectorized",
        chunk_count: 1, vector_chunks: 1, stored_vector_chunks: 1, embedding_configured: true,
      },
    });
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

    fireEvent.change(screen.getByRole("textbox", { name: "搜索当前目录及子目录" }), { target: { value: "合同" } });
    fireEvent.submit(screen.getByRole("search"));
    await waitFor(() => expect(listFiles).toHaveBeenLastCalledWith("", "合同", "name"));

    fireEvent.click(screen.getByText("合同.txt"));
    await waitFor(() => expect(screen.getByTitle("文件详情")).toBeInTheDocument());
    fireEvent.click(screen.getByTitle("文件详情"));
    expect(screen.getAllByText("已向量化").length).toBeGreaterThan(0);
    expect(screen.getByText("版本").parentElement).toHaveTextContent("3");

    fireEvent.click(screen.getByTitle("查看内容"));
    await waitFor(() => expect(getFileContent).toHaveBeenCalledWith("合同.txt"));
    expect(screen.getByText("完整合同内容")).toBeInTheDocument();
  });

  it("用语义模式搜索并展示最佳匹配片段和相关度", async () => {
    listFiles.mockImplementation(async (_path: string, query: string, mode: string) =>
      mode === "semantic"
        ? {
            ...rootListing,
            query,
            mode,
            items: [
              { ...rootListing.items[1], search_score: 0.923, search_snippet: "合同付款节点和验收条件" },
              { ...rootListing.items[1], name: "低分.txt", path: "低分.txt", search_score: 0.12, search_snippet: "弱相关内容" },
            ],
          }
        : rootListing
    );
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "语义" }));
    fireEvent.change(screen.getByRole("textbox", { name: "描述你要找的内容" }), {
      target: { value: "付款和验收" },
    });
    fireEvent.submit(screen.getByRole("search"));

    await waitFor(() => expect(listFiles).toHaveBeenLastCalledWith("", "付款和验收", "semantic"));
    expect(screen.getByText("合同付款节点和验收条件")).toBeInTheDocument();
    expect(screen.getByText("相关度 92.3%")).toBeInTheDocument();
    expect(screen.getByText("已向量化")).toBeInTheDocument();
    expect(screen.queryByText("低分.txt")).not.toBeInTheDocument();
  });

  it("提交修改日期筛选时把时间范围传给列表 API", async () => {
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("修改时间起点"), { target: { value: "2026-08-01" } });
    fireEvent.change(screen.getByLabelText("修改时间终点"), { target: { value: "2026-08-20" } });
    fireEvent.submit(screen.getByRole("search"));

    await waitFor(() => expect(listFiles).toHaveBeenLastCalledWith(
      "", "", "name", expect.objectContaining({ type: "all" }),
    ));
    const filters = listFiles.mock.calls.at(-1)?.[3] as { modifiedAfter?: number; modifiedBefore?: number };
    expect(filters.modifiedAfter).toBeGreaterThan(0);
    expect(filters.modifiedBefore).toBeGreaterThan(filters.modifiedAfter ?? 0);
  });

  it("直接执行图片视觉索引", async () => {
    getFileInfo.mockResolvedValue({
      path: "合同.txt", name: "合同.txt", size: 75, modified: 1750000000,
      preview_kind: "image", snippet: null, indexed: null,
    });
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("合同.txt"));
    await waitFor(() => expect(screen.getByRole("button", { name: /视觉索引/ })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /视觉索引/ }));
    await waitFor(() => expect(indexVision).toHaveBeenCalledWith(["合同.txt"]));
  });

  it("文本向量化先抽取正文再写入向量", async () => {
    getFileInfo.mockResolvedValue({
      path: "合同.txt", name: "合同.txt", size: 75, modified: 1750000000,
      preview_kind: "text", snippet: "摘要", indexed: null,
    });
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("合同.txt"));
    await waitFor(() => expect(screen.getByTitle("为当前文件创建文本向量索引")).toBeInTheDocument());
    fireEvent.click(screen.getByTitle("为当前文件创建文本向量索引"));
    await waitFor(() => expect(indexFiles).toHaveBeenCalledWith(["合同.txt"]));
    expect(vectorize).toHaveBeenCalledWith(["合同.txt"]);
  });
});
