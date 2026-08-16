import { afterEach, describe, expect, it, vi } from "vitest";
import { EV } from "@/lib/events";
import {
  api,
  authenticatedFetch,
  ApiError,
  setDeviceToken,
} from "./client";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const jsonResponse = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status,
  headers: { "Content-Type": "application/json" },
});

afterEach(() => {
  setDeviceToken(null);
  vi.restoreAllMocks();
});

describe("API 身份与 GET 缓存", () => {
  it("切换设备令牌后不复用旧身份缓存", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ owner: "old" }))
      .mockResolvedValueOnce(jsonResponse({ owner: "new" }));
    global.fetch = fetchMock;

    setDeviceToken("old-token");
    expect(await api("/whoami")).toEqual({ owner: "old" });
    expect(await api("/whoami")).toEqual({ owner: "old" });
    setDeviceToken("new-token");
    expect(await api("/whoami")).toEqual({ owner: "new" });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get("Authorization"))
      .toBe("Bearer old-token");
    expect(new Headers(fetchMock.mock.calls[1][1]?.headers).get("Authorization"))
      .toBe("Bearer new-token");
  });

  it("旧身份的迟到 401 不会触发当前身份退出", async () => {
    const pending = deferred<Response>();
    global.fetch = vi.fn().mockReturnValue(pending.promise);
    const listener = vi.fn();
    window.addEventListener(EV.unauthorized, listener);
    try {
      setDeviceToken("old-token");
      const request = authenticatedFetch("/slow");
      await vi.waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));
      setDeviceToken("new-token");
      pending.resolve(jsonResponse({ detail: "old revoked" }, 401));
      expect((await request).status).toBe(401);
      expect(listener).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener(EV.unauthorized, listener);
    }
  });

  it("写请求完成后再次失效写入期间取得的 GET", async () => {
    const mutation = deferred<Response>();
    const fetchMock = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") return mutation.promise;
      const reads = fetchMock.mock.calls.filter(([, options]) => options?.method !== "POST").length;
      return Promise.resolve(jsonResponse({ version: reads }));
    });
    global.fetch = fetchMock;

    const writing = api("/files/rename", { method: "POST", body: "{}" });
    await Promise.resolve();
    expect(await api("/files")).toEqual({ version: 1 });
    mutation.resolve(jsonResponse({ ok: true }));
    await writing;
    expect(await api("/files")).toEqual({ version: 2 });
  });

  it("交错写请求各自结束时都会清理其窗口内的 GET", async () => {
    const first = deferred<Response>();
    const second = deferred<Response>();
    const fetchMock = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") {
        return String(url).endsWith("/write-a") ? first.promise : second.promise;
      }
      const reads = fetchMock.mock.calls.filter(([, options]) => options?.method !== "POST").length;
      return Promise.resolve(jsonResponse({ version: reads }));
    });
    global.fetch = fetchMock;

    const writeA = api("/write-a", { method: "POST", body: "{}" });
    const writeB = api("/write-b", { method: "POST", body: "{}" });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(await api("/files")).toEqual({ version: 1 });

    first.resolve(jsonResponse({ ok: true }));
    await writeA;
    expect(await api("/files")).toEqual({ version: 2 });

    second.resolve(jsonResponse({ ok: true }));
    await writeB;
    expect(await api("/files")).toEqual({ version: 3 });
  });

  it("写入结束后才返回的旧 GET 不会回填缓存", async () => {
    const mutation = deferred<Response>();
    const staleRead = deferred<Response>();
    const fetchMock = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") return mutation.promise;
      const reads = fetchMock.mock.calls.filter(([, options]) => options?.method !== "POST").length;
      return reads === 1 ? staleRead.promise : Promise.resolve(jsonResponse({ version: reads }));
    });
    global.fetch = fetchMock;

    const writing = api("/write", { method: "POST", body: "{}" });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const stale = api("/files"); // 写窗口内启动的 GET
    mutation.resolve(jsonResponse({ ok: true }));
    await writing;
    staleRead.resolve(jsonResponse({ version: "stale" }));
    expect(await stale).toEqual({ version: "stale" }); // 只回给该调用者，不入缓存
    expect(await api("/files")).toEqual({ version: 2 }); // 下次读取重新请求
  });

  it.each([
    ["网络错误", new TypeError("offline")],
    ["主动中止", new DOMException("aborted", "AbortError")],
  ])("raw 写请求%s后再次失效写窗口内的 GET", async (_label, failure) => {
    const mutation = deferred<Response>();
    const fetchMock = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") return mutation.promise;
      const reads = fetchMock.mock.calls.filter(([, options]) => options?.method !== "POST").length;
      return Promise.resolve(jsonResponse({ version: reads }));
    });
    global.fetch = fetchMock;

    const writing = authenticatedFetch("/files/upload", { method: "POST", body: "payload" });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(await api("/files")).toEqual({ version: 1 });
    mutation.reject(failure);
    await expect(writing).rejects.toBe(failure);
    expect(await api("/files")).toEqual({ version: 2 });
  });

  it("结构化 detail 会成为可读 ApiError 消息", async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({
      detail: [{ loc: ["query", "path"], msg: "required" }],
    }, 422));
    await expect(api("/bad", { cache: "no-store" })).rejects.toMatchObject({
      status: 422,
      message: '[{"loc":["query","path"],"msg":"required"}]',
    } satisfies Partial<ApiError>);
  });
});
