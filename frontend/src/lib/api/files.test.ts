import { afterEach, describe, expect, it, vi } from "vitest";
import { EV } from "@/lib/events";
import { ApiError, setDeviceToken } from "./client";
import { uploadFile } from "./files";

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
