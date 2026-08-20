import { act, fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import TaskPeekDrawer from "./TaskPeekDrawer";
import { EV } from "@/lib/events";

const listTasks = vi.fn();

vi.mock("@/lib/api/tasks", () => ({
  listTasks: (...args: unknown[]) => listTasks(...args),
}));

const task = {
  id: "task-1",
  type: "index.embed",
  lane: "index",
  status: "running" as const,
  payload: { files: ["docs/report.md"] },
  result: null,
  error: null,
  priority: 5,
  resource_key: "file:docs/report.md",
  parent_id: null,
  origin: "api",
  attempts: 1,
  max_attempts: 3,
  cancel_requested: false,
  progress: { current: 0, total: 0, message: "正在连接向量服务" },
  created_at: 1_750_000_000,
  updated_at: 1_750_000_001,
  started_at: 1_750_000_000,
  finished_at: null,
};

const response = {
  items: [task],
  has_more: false,
  overview: {
    counts: {},
    workers: { online: true, count: 1 },
    index: {
      eligible_files: 0,
      extracted_files: 0,
      vector_files: 0,
      non_vectorizable_files: 0,
      missing_vectors: 0,
      stale_vectors: 0,
      embedding_configured: true,
      model: "jina-embeddings-v3",
    },
  },
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

describe("TaskPeekDrawer", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listTasks.mockResolvedValue(response);
  });

  it("复用任务展示口径并保留未知总量的处理中状态", async () => {
    render(<TaskPeekDrawer open onClose={vi.fn()} onViewAll={vi.fn()} />);
    await act(async () => {});

    expect(listTasks).toHaveBeenCalledWith("queued,running,retry_wait,cancelling,failed,cancelled", { limit: 7 });
    expect(screen.getByText("文件向量化")).toBeInTheDocument();
    expect(screen.getByText("正在连接向量服务")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: "进度处理中" })).toBeInTheDocument();
    expect(screen.queryByText("0%")).not.toBeInTheDocument();
  });

  it("忽略刷新期间已经过期的抽屉请求", async () => {
    const first = deferred<typeof response>();
    const second = deferred<typeof response>();
    let calls = 0;
    listTasks.mockImplementation(() => (calls++ === 0 ? first.promise : second.promise));

    render(<TaskPeekDrawer open onClose={vi.fn()} onViewAll={vi.fn()} />);
    await act(async () => {});
    window.dispatchEvent(new CustomEvent(EV.tasksChanged));

    const fresh = {
      ...response,
      items: [{ ...task, resource_key: "file:new.md" }],
    };
    second.resolve(fresh);
    await act(async () => { await second.promise; });
    expect(screen.getByText("new.md")).toBeInTheDocument();

    first.resolve({ ...response, items: [{ ...task, resource_key: "file:old.md" }] });
    await act(async () => { await first.promise; });
    expect(screen.getByText("new.md")).toBeInTheDocument();
    expect(screen.queryByText("old.md")).not.toBeInTheDocument();
  });

  it("失败时提供可重复触发的读取操作", async () => {
    listTasks.mockRejectedValueOnce(new Error("任务服务暂不可用")).mockResolvedValueOnce(response);
    render(<TaskPeekDrawer open onClose={vi.fn()} onViewAll={vi.fn()} />);
    await act(async () => {});

    expect(screen.getByText("任务服务暂不可用")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    await act(async () => {});
    expect(screen.getByText("文件向量化")).toBeInTheDocument();
    expect(listTasks).toHaveBeenCalledTimes(2);
  });
});
