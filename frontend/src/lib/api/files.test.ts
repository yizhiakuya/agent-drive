import { afterEach, describe, expect, it, vi } from "vitest";
import { EV } from "@/lib/events";
import { ApiError, setDeviceToken } from "./client";
import { listFavorites, listFiles, listRecent, listVersions, restoreVersion, setFavorite, uploadFile } from "./files";

const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status,
  headers: { "Content-Type": "application/json" },
});

afterEach(() => {
  setDeviceToken(null);
  vi.restoreAllMocks();
});

describe("uploadFile", () => {
  it("使用认证 multipart 请求并返回上传结果", async () => {
    const payload = {
      uploaded: { path: "照片/a.txt", size: 3 },
      indexed: { task_id: "t1", status: "queued" },
    };
    const fetchMock = vi.fn().mockResolvedValue(response(payload));
    global.fetch = fetchMock;
    setDeviceToken("device-token");

    const result = await uploadFile(new File(["abc"], "a.txt", { type: "text/plain" }), "照片");
    expect(result).toEqual(payload);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/v1/files/upload?path=%E7%85%A7%E7%89%87");
    expect(init?.method).toBe("POST");
    expect(init?.credentials).toBe("include");
    expect(init?.body).toBeInstanceOf(FormData);
    expect(new Headers(init?.headers).get("Authorization")).toBe("Bearer device-token");
    expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
  });

  it("401 只派发一次未授权事件并保留结构化 detail", async () => {
    global.fetch = vi.fn().mockResolvedValue(response({ detail: { reason: "revoked" } }, 401));
    const listener = vi.fn();
    window.addEventListener(EV.unauthorized, listener);
    try {
      await expect(uploadFile(new File(["x"], "x.txt"))).rejects.toMatchObject({
        status: 401,
        message: '{"reason":"revoked"}',
      } satisfies Partial<ApiError>);
      expect(listener).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener(EV.unauthorized, listener);
    }
  });
});

describe("文件生产力列表 API", () => {
  it("发送类型、时间和相关度筛选参数", async () => {
    const fetchMock = vi.fn().mockImplementation(() => response({ items: [], disk: null }));
    global.fetch = fetchMock;

    await listFiles("文档", "付款", "semantic", {
      type: "pdf",
      modifiedAfter: 10,
      modifiedBefore: 20,
      minScore: 0.8,
      limit: 25,
    });

    expect(fetchMock.mock.calls[0][0]).toBe(
      "/api/v1/files?path=%E6%96%87%E6%A1%A3&q=%E4%BB%98%E6%AC%BE&mode=semantic&type=pdf&modified_after=10&modified_before=20&min_score=0.8&limit=25",
    );
  });

  it("使用 owner scoped 收藏、最近访问和幂等收藏变更端点", async () => {
    const fetchMock = vi.fn().mockImplementation(() => response({ items: [], disk: null }));
    global.fetch = fetchMock;

    await listFavorites(20);
    await listRecent(10);
    await setFavorite("文档/合同.txt", true);
    await setFavorite("文档/合同.txt", false);

    expect(fetchMock.mock.calls.map(([url, init]) => [url, init?.method])).toEqual([
      ["/api/v1/files/favorites?limit=20", undefined],
      ["/api/v1/files/recent?limit=10", undefined],
      ["/api/v1/files/favorites?path=%E6%96%87%E6%A1%A3%2F%E5%90%88%E5%90%8C.txt", "POST"],
      ["/api/v1/files/favorites?path=%E6%96%87%E6%A1%A3%2F%E5%90%88%E5%90%8C.txt", "DELETE"],
    ]);
  });

  it("使用版本列表和恢复端点传递 owner 相对路径与版本 ID", async () => {
    const fetchMock = vi.fn().mockImplementation(() => response({ path: "文档/合同.txt", items: [] }));
    global.fetch = fetchMock;

    await listVersions("文档/合同.txt", 12);
    await restoreVersion("文档/合同.txt", "version-1");

    expect(fetchMock.mock.calls.map(([url, init]) => [url, init?.method])).toEqual([
      ["/api/v1/files/versions?path=%E6%96%87%E6%A1%A3%2F%E5%90%88%E5%90%8C.txt&limit=12", undefined],
      ["/api/v1/files/versions/restore?path=%E6%96%87%E6%A1%A3%2F%E5%90%88%E5%90%8C.txt&version_id=version-1", "POST"],
    ]);
  });

  it("progress callback follows XMLHttpRequest upload progress for an active queue item", async () => {
    const previous = globalThis.XMLHttpRequest;
    class FakeUploadXhr {
      upload = { onprogress: null as ((event: { lengthComputable: boolean; loaded: number; total: number }) => void) | null };
      status = 201;
      statusText = "Created";
      responseText = JSON.stringify({ uploaded: { path: "photo.jpg", size: 10 }, indexed: null });
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      onabort: (() => void) | null = null;
      open = vi.fn();
      setRequestHeader = vi.fn();
      send = vi.fn(() => {
        this.upload.onprogress?.({ lengthComputable: true, loaded: 5, total: 10 });
        this.onload?.();
      });
      abort = vi.fn(() => this.onabort?.());
    }
    globalThis.XMLHttpRequest = FakeUploadXhr as unknown as typeof XMLHttpRequest;
    try {
      const progress: number[] = [];
      const result = await uploadFile(new File(["0123456789"], "photo.jpg"), "相册", (value) => progress.push(value));
      expect(result.uploaded.path).toBe("photo.jpg");
      expect(progress).toEqual([0, 50, 100]);
    } finally {
      globalThis.XMLHttpRequest = previous;
    }
  });
});
