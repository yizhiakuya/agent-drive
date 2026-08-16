import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import FilePage from "./FilePage";

const listFiles = vi.fn();
const uploadFile = vi.fn();
const getFileInfo = vi.fn();
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
  uploadFile: (...a: unknown[]) => uploadFile(...a),
  getFileInfo: (...a: unknown[]) => getFileInfo(...a),
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

// 预览面板依赖较重，测试关注文件操作主流程，直接桩掉。
vi.mock("./FilePreview", () => ({
  default: () => <div data-testid="preview" />,
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
    listTrash.mockResolvedValue({
      items: [{ path: "旧文件.txt", trash_id: "t1", deleted_at: 1750000000, size: 10, is_dir: false }],
    });
    getFileInfo.mockResolvedValue({
      path: "合同.txt", name: "合同.txt", size: 75, modified: 1750000000,
      preview_kind: "text", snippet: "内容", indexed: null,
    });
    emptyTrash.mockResolvedValue({ removed: 1 });
  });

  it("渲染文件列表", async () => {
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("资料")).toBeInTheDocument());
    expect(screen.getByText("合同.txt")).toBeInTheDocument();
    expect(screen.getByText(/已用/)).toBeInTheDocument();
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
      fireEvent.click(screen.getByText("✏️ 重命名"));
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

  it("删除确认后调用 deleteToTrash", async () => {
    deleteToTrash.mockResolvedValue({});
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("合同.txt"));
    await act(async () => {});
    fireEvent.click(screen.getByText("🗑️ 删除"));
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

  it("回收站恢复", async () => {
    restoreFromTrash.mockResolvedValue({});
    render(<FilePage />);
    await waitFor(() => expect(screen.getByText("合同.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText("回收站"));
    await waitFor(() => expect(screen.getByText("旧文件.txt")).toBeInTheDocument());
    fireEvent.click(screen.getByText("恢复"));
    await waitFor(() => expect(restoreFromTrash).toHaveBeenCalledWith("t1"));
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
});
