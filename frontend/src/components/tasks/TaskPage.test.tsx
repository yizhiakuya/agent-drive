import { act, fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import TaskPage from "./TaskPage";

const listTasks = vi.fn();
const cancelTask = vi.fn();
const retryTask = vi.fn();
const rebuildIndex = vi.fn();

vi.mock("@capacitor/core", () => ({
  Capacitor: { isNativePlatform: () => true },
  registerPlugin: vi.fn(() => ({})),
}));
vi.mock("@/lib/api/tasks", () => ({
  listTasks: (...args: unknown[]) => listTasks(...args),
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

describe("TaskPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listTasks.mockResolvedValue(response);
    cancelTask.mockResolvedValue({ task: { ...response.items[0], status: "cancelling" } });
    rebuildIndex.mockResolvedValue({ queued: true, task: response.items[0] });
  });

  it("shows task progress and can request cancellation", async () => {
    render(<TaskPage />);
    await act(async () => {});
    expect(screen.getByText("重建搜索索引")).toBeInTheDocument();
    expect(screen.getByText("已处理 2/10")).toBeInTheDocument();
    expect(screen.getByText("20%")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("取消任务"));
    await act(async () => {});
    expect(cancelTask).toHaveBeenCalledWith("task-1");
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
});
