import { act, fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import TaskPage from "./TaskPage";

const listTasks = vi.fn();
const getTaskDetail = vi.fn();
const cancelTask = vi.fn();
const retryTask = vi.fn();
const rebuildIndex = vi.fn();

vi.mock("@capacitor/core", () => ({
  Capacitor: { isNativePlatform: () => true },
  registerPlugin: vi.fn(() => ({})),
}));
vi.mock("@/lib/api/tasks", () => ({
  listTasks: (...args: unknown[]) => listTasks(...args),
  getTaskDetail: (...args: unknown[]) => getTaskDetail(...args),
  cancelTask: (...args: unknown[]) => cancelTask(...args),
  retryTask: (...args: unknown[]) => retryTask(...args),
  rebuildIndex: (...args: unknown[]) => rebuildIndex(...args),
  taskEventsUrl: () => "/api/v1/tasks/events",
}));

const response = {
  items: [{
    id: "task-1",
    type: "index.rebuild",
    lane: "orchestration",
    status: "running",
    payload: { prefix: "", force: true },
    result: null,
    error: null,
    priority: 5,
    resource_key: "index:*",
    parent_id: null,
    origin: "api",
    attempts: 1,
    max_attempts: 2,
    cancel_requested: false,
    progress: { current: 2, total: 10, message: "已处理 2/10" },
    created_at: 1_750_000_000,
    updated_at: 1_750_000_001,
    started_at: 1_750_000_000,
    finished_at: null,
  }],
  has_more: false,
  overview: {
    counts: { running: 1, failed: 2 },
    workers: { online: true, count: 1 },
    index: {
      eligible_files: 10,
      extracted_files: 8,
      vector_files: 4,
      non_vectorizable_files: 1,
      missing_vectors: 5,
      stale_vectors: 0,
      embedding_configured: true,
      model: "jina-embeddings-v3",
    },
  },
};

type MockTask = Omit<typeof response.items[number], "result"> & { result: Record<string, unknown> | null };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

describe("TaskPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listTasks.mockResolvedValue(response);
    getTaskDetail.mockResolvedValue({ task: response.items[0], children: [] });
    cancelTask.mockResolvedValue({ task: { ...response.items[0], status: "cancelling" } });
    rebuildIndex.mockResolvedValue({ queued: true, task: response.items[0] });
  });

  it("shows task progress and can request cancellation", async () => {
    render(<TaskPage />);
    await act(async () => {});
    expect(screen.getByText("重建搜索索引")).toBeInTheDocument();
    expect(screen.getByText("已处理 2/10")).toBeInTheDocument();
    expect(screen.getByText(/20%/)).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("取消任务"));
    await act(async () => {});
    expect(cancelTask).toHaveBeenCalledWith("task-1");
  });

  it("展开任务详情显示输入、结果和子任务", async () => {
    getTaskDetail.mockResolvedValue({
      task: {
        ...response.items[0],
        status: "succeeded",
        result: { indexed: 8, embedding: { vectorized: true } },
        finished_at: 1_750_000_010,
      },
      children: [{
        ...response.items[0],
        id: "child-1",
        type: "index.file",
        status: "succeeded",
        parent_id: "task-1",
        resource_key: "file:notes/report.md",
        progress: { current: 1, total: 1, message: "索引完成" },
      }],
    });
    render(<TaskPage />);
    await act(async () => {});

    fireEvent.click(screen.getByRole("button", { name: "展开任务详情" }));
    await act(async () => {});

    expect(getTaskDetail).toHaveBeenCalledWith("task-1");
    expect(screen.getByTestId("task-details-task-1")).toBeInTheDocument();
    expect(screen.getByText("任务详情")).toBeInTheDocument();
    expect(screen.getByText(/\"force\": true/)).toBeInTheDocument();
    expect(screen.getByTestId("task-details-task-1").textContent).toContain('"indexed": 8');
    expect(screen.getByText("notes/report.md")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "收起任务详情" })).toBeInTheDocument();
  });

  it("展开失败任务显示完整失败原因", async () => {
    const failedTask = {
      ...response.items[0],
      status: "failed" as const,
      error: "embedding provider returned 502: upstream unavailable",
    };
    listTasks.mockResolvedValue({ ...response, items: [failedTask] });
    getTaskDetail.mockResolvedValue({ task: failedTask, children: [] });
    render(<TaskPage />);
    await act(async () => {});

    fireEvent.click(screen.getByRole("button", { name: "展开任务详情" }));
    await act(async () => {});

    expect(screen.getByText("失败原因")).toBeInTheDocument();
    expect(screen.getAllByText("embedding provider returned 502: upstream unavailable").length).toBeGreaterThan(0);
  });

  it("任务事件刷新列表时同步刷新已展开详情的进度", async () => {
    const updatedTask = {
      ...response.items[0],
      progress: { current: 8, total: 10, message: "正在处理 8/10" },
      updated_at: 1_750_000_008,
    };
    getTaskDetail
      .mockResolvedValueOnce({ task: response.items[0], children: [] })
      .mockResolvedValue({ task: updatedTask, children: [] });
    listTasks
      .mockResolvedValueOnce(response)
      .mockResolvedValue({ ...response, items: [updatedTask] });
    render(<TaskPage />);
    await act(async () => {});

    fireEvent.click(screen.getByRole("button", { name: "展开任务详情" }));
    await act(async () => {});
    expect(screen.getByTestId("task-progress-task-1").textContent).toContain("2/10");

    window.dispatchEvent(new CustomEvent("agent-drive:tasks-changed"));
    await act(async () => {});

    expect(getTaskDetail).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("task-progress-task-1").textContent).toContain("8/10");
    expect(screen.getAllByText("正在处理 8/10").length).toBeGreaterThanOrEqual(2);
  });

  it("忽略过期的任务详情响应", async () => {
    const first = deferred<{ task: MockTask; children: [] }>();
    const second = deferred<{ task: MockTask; children: [] }>();
    getTaskDetail.mockImplementation((id: string) => id === "task-1" ? first.promise : second.promise);
    listTasks.mockResolvedValue({
      ...response,
      items: [response.items[0], { ...response.items[0], id: "task-2", resource_key: "file:second.md" }],
    });
    render(<TaskPage />);
    await act(async () => {});

    const expandButtons = screen.getAllByRole("button", { name: "展开任务详情" });
    fireEvent.click(expandButtons[0]);
    fireEvent.click(expandButtons[1]);
    second.resolve({ task: { ...response.items[0], id: "task-2", resource_key: "file:second.md", result: { current: 2 } }, children: [] });
    await act(async () => { await second.promise; });
    expect(screen.getByTestId("task-details-task-2").textContent).toContain('"current": 2');

    first.resolve({ task: { ...response.items[0], result: { stale: true } }, children: [] });
    await act(async () => { await first.promise; });
    expect(screen.getByTestId("task-details-task-2").textContent).not.toContain('"stale": true');
  });

  it("confirms a forced index rebuild", async () => {
    render(<TaskPage />);
    await act(async () => {});
    fireEvent.click(screen.getByRole("button", { name: "重建索引" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "开始重建" }));
    await act(async () => {});
    expect(rebuildIndex).toHaveBeenCalledWith(true);
  });

  it("忽略过期筛选条件的任务列表响应", async () => {
    const initial = deferred<typeof response>();
    const filtered = deferred<typeof response>();
    listTasks.mockImplementation((status: string) => status ? filtered.promise : initial.promise);
    const filteredResponse = {
      ...response,
      items: [{ ...response.items[0], resource_key: "filtered-task" }],
    };

    render(<TaskPage />);
    fireEvent.click(screen.getByRole("tab", { name: "异常" }));

    filtered.resolve(filteredResponse);
    await act(async () => { await filtered.promise; });
    expect(screen.getByText("filtered-task")).toBeInTheDocument();

    initial.resolve(response);
    await act(async () => { await initial.promise; });
    expect(screen.getByText("filtered-task")).toBeInTheDocument();
    expect(screen.queryByText("index:*")).not.toBeInTheDocument();
  });

  it("按后端 has_more 显示并加载下一页任务", async () => {
    const extraTask = { ...response.items[0], id: "task-2", resource_key: "file:second.md" };
    listTasks
      .mockResolvedValueOnce({ ...response, has_more: true })
      .mockResolvedValueOnce({ ...response, items: [...response.items, extraTask], has_more: false });

    render(<TaskPage />);
    await act(async () => {});
    expect(screen.getByRole("button", { name: "加载更多任务" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "加载更多任务" }));
    await act(async () => {});

    expect(screen.getByText("second.md")).toBeInTheDocument();
    expect(listTasks).toHaveBeenLastCalledWith("", { limit: 100 });
    expect(screen.queryByRole("button", { name: "加载更多任务" })).not.toBeInTheDocument();
  });

  it("未知总量的运行中任务显示不定进度而不是 0%", async () => {
    const indeterminateTask = {
      ...response.items[0],
      progress: { current: 0, total: 0, message: "正在连接向量服务" },
    };
    listTasks.mockResolvedValue({ ...response, items: [indeterminateTask] });

    render(<TaskPage />);
    await act(async () => {});

    expect(screen.getByText("正在连接向量服务")).toBeInTheDocument();
    expect(screen.queryByText("0%")).not.toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "进度处理中" })).toBeInTheDocument();
  });
});
