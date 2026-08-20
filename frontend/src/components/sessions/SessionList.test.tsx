
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import SessionList from "./SessionList";
import { useAppStore } from "@/lib/store";

const listSessions = vi.fn();
const summarizeSession = vi.fn();
const deleteSession = vi.fn();

vi.mock("@/lib/api/sessions", () => ({
  listSessions: (...args: unknown[]) => listSessions(...args),
  summarizeSession: (...args: unknown[]) => summarizeSession(...args),
  deleteSession: (...args: unknown[]) => deleteSession(...args),
}));

type SessionRow = { id: string; title: string; summary?: string };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

function bumpSessions() {
  act(() => { useAppStore.getState().bumpSessions(); });
}

describe("SessionList 空标题 → 标题生成与列表刷新", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // 重置 zustand 会话信号，避免跨用例累积
    useAppStore.setState({ sessionsVersion: 0, sessionId: null });
  });

  it("空标题会话：总结成功后在刷新列表中显示标题", async () => {
    listSessions
      .mockResolvedValueOnce({ sessions: [{ id: "a", title: "" }] as SessionRow[] })
      .mockResolvedValueOnce({ sessions: [{ id: "a", title: "帮我整理文件" }] as SessionRow[] });
    summarizeSession.mockResolvedValue({ ok: true });

    render(<SessionList />);
    await waitFor(() => expect(summarizeSession).toHaveBeenCalledWith("a"));
    await waitFor(() => expect(screen.getByText("帮我整理文件")).toBeInTheDocument());
    // summarize 是写请求（清 GET 缓存），刷新必然是第二次列表拉取
    expect(listSessions).toHaveBeenCalledTimes(2);
  });

  it("会话记录显示完整会话 ID", async () => {
    listSessions.mockResolvedValueOnce({
      sessions: [{ id: "session-2026-08-19-abc", title: "文件整理" }] as SessionRow[],
    });

    render(<SessionList />);

    expect(await screen.findByText("ID: session-2026-08-19-abc")).toBeInTheDocument();
  });

  it("会话列表可收缩/展开，并支持键盘调整宽度", async () => {
    const onResize = vi.fn();
    render(<SessionList width={240} onResize={onResize} />);

    await act(async () => {});
    const handle = screen.getByTestId("sessions-panel-resize-handle");
    expect(handle.className).toContain("-right-3");
    expect(handle.className).not.toContain("right-0");
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(onResize).toHaveBeenCalledWith(256);

    fireEvent.click(screen.getByTitle("收起会话列表"));
    expect(screen.getByTestId("session-panel-collapsed")).toBeInTheDocument();
    fireEvent.click(screen.getByTitle("展开会话列表"));
    expect(screen.getByTestId("sessions-panel-resize-handle")).toBeInTheDocument();
  });

  it("异步标题返回：并发 load 等待他人总结落地后刷新显示标题", async () => {
    const summary = deferred<{ ok: boolean }>();
    // load#1 首次列表：空标题 → 开始总结（挂起）
    // load#2 首次列表：仍是空标题（总结未提交），但会话已在总结中 → 等待后重拉
    // load#2 重拉：标题已落地
    listSessions
      .mockResolvedValueOnce({ sessions: [{ id: "a", title: "" }] as SessionRow[] })
      .mockResolvedValueOnce({ sessions: [{ id: "a", title: "" }] as SessionRow[] })
      .mockResolvedValueOnce({ sessions: [{ id: "a", title: "帮我整理文件" }] as SessionRow[] });
    summarizeSession.mockReturnValue(summary.promise);

    render(<SessionList />);
    await waitFor(() => expect(summarizeSession).toHaveBeenCalledTimes(1));

    // 总结仍在途：触发第二次列表刷新（如另一轮会话变更）
    bumpSessions();
    await act(async () => { await Promise.resolve(); });

    // load#1 的总结此刻落库：等 load#2 的重拉拿到新标题
    await act(async () => {
      summary.resolve({ ok: true });
      await new Promise((r) => setTimeout(r, 400));
    });

    await waitFor(() => expect(screen.getByText("帮我整理文件")).toBeInTheDocument());
    // 去重：同一会话只总结一次；两次列表 + 等待后的一次重拉
    expect(summarizeSession).toHaveBeenCalledTimes(1);
    expect(listSessions).toHaveBeenCalledTimes(3);
  });

  it("旧列表不覆盖新列表：被取代的 load 总结落地后不得回写旧快照", async () => {
    const summary = deferred<{ ok: boolean }>();
    // load#1 首次列表：A 空标题 → 开始总结（挂起）
    // load#2 首次列表：A 已被删除，只剩新的 B（即较新的服务端状态）
    listSessions
      .mockResolvedValueOnce({ sessions: [{ id: "a", title: "" }] as SessionRow[] })
      .mockResolvedValueOnce({ sessions: [{ id: "b", title: "新会话B" }] as SessionRow[] });
    summarizeSession.mockReturnValue(summary.promise);

    render(<SessionList />);
    await waitFor(() => expect(summarizeSession).toHaveBeenCalledWith("a"));

    // 新 load 抢占序列并先落列表
    bumpSessions();
    await act(async () => { await Promise.resolve(); });
    expect(screen.getByText("新会话B")).toBeInTheDocument();

    // 旧 load 的总结落地：序列守卫必须拦截其刷新，旧快照 [A 有标题] 不得覆盖 [B]
    await act(async () => {
      summary.resolve({ ok: true });
      await Promise.resolve();
    });
    expect(screen.getByText("新会话B")).toBeInTheDocument();
    expect(screen.queryByText("（无标题会话）")).not.toBeInTheDocument();
    expect(screen.queryByText("帮我整理文件")).not.toBeInTheDocument();
    // 被取代的 load 不再发起第三次列表请求
    expect(listSessions).toHaveBeenCalledTimes(2);
  });
});
