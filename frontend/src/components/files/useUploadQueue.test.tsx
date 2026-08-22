import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { uploadFile } from "@/lib/api/files";
import { emitToast } from "@/lib/events";
import { useUploadQueue } from "./useUploadQueue";

vi.mock("@/lib/api/files", () => ({ uploadFile: vi.fn() }));
vi.mock("@/lib/events", () => ({ emitToast: vi.fn() }));

const uploadFileMock = vi.mocked(uploadFile);
const emitToastMock = vi.mocked(emitToast);

describe("useUploadQueue", () => {
  beforeEach(() => {
    uploadFileMock.mockReset();
    emitToastMock.mockReset();
  });

  it("tracks progress, completion, and refreshes the current listing", async () => {
    uploadFileMock.mockImplementation(async (_file, _path, onProgress) => {
      onProgress?.(64);
      return { uploaded: { path: "photos/a.txt", size: 1 } };
    });
    const onSettled = vi.fn();
    const pathRef = { current: "photos" };
    const { result } = renderHook(() => useUploadQueue(pathRef, onSettled));

    await act(async () => {
      await result.current.uploadFiles([new File(["a"], "a.txt")]);
    });

    expect(uploadFileMock).toHaveBeenCalledWith(
      expect.any(File),
      "photos",
      expect.any(Function),
      expect.any(AbortSignal),
    );
    expect(result.current.uploadQueue[0]).toMatchObject({
      name: "a.txt",
      status: "succeeded",
      progress: 100,
    });
    expect(onSettled).toHaveBeenCalledOnce();
  });

  it("keeps a failed file available for retry", async () => {
    uploadFileMock
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce({ uploaded: { path: "a.txt", size: 1 } });
    const { result } = renderHook(() => useUploadQueue({ current: "" }, vi.fn()));

    await act(async () => {
      await result.current.uploadFiles([new File(["a"], "a.txt")]);
    });
    expect(result.current.uploadQueue[0].status).toBe("failed");

    await act(async () => {
      await result.current.retryUpload(result.current.uploadQueue[0].id);
    });
    expect(result.current.uploadQueue[0].status).toBe("succeeded");
    expect(uploadFileMock).toHaveBeenCalledTimes(2);
  });

  it("aborts an active upload without reporting it as an error", async () => {
    uploadFileMock.mockImplementation((_file, _path, _onProgress, signal) => new Promise((_, reject) => {
      signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true });
    }));
    const { result } = renderHook(() => useUploadQueue({ current: "" }, vi.fn()));

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.uploadFiles([new File(["a"], "a.txt")]);
    });
    await waitFor(() => expect(result.current.uploadQueue[0]?.status).toBe("uploading"));

    await act(async () => {
      result.current.cancelUpload(result.current.uploadQueue[0].id);
      await pending;
    });

    expect(result.current.uploadQueue[0].status).toBe("cancelled");
    expect(emitToastMock).not.toHaveBeenCalledWith(expect.objectContaining({ kind: "error" }));
  });

  it("aborts on unmount without refreshing an unmounted page", async () => {
    let capturedSignal: AbortSignal | undefined;
    uploadFileMock.mockImplementation((_file, _path, _onProgress, signal) => {
      capturedSignal = signal;
      return new Promise((_, reject) => {
        signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true });
      });
    });
    const onSettled = vi.fn();
    const { result, unmount } = renderHook(() => useUploadQueue({ current: "" }, onSettled));

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.uploadFiles([new File(["a"], "a.txt")]);
    });
    await waitFor(() => expect(result.current.uploadQueue[0]?.status).toBe("uploading"));

    await act(async () => {
      unmount();
      await pending;
    });

    expect(capturedSignal?.aborted).toBe(true);
    expect(onSettled).not.toHaveBeenCalled();
  });
});
