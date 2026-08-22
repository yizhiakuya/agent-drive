import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AutomationCenter from "./AutomationCenter";

const mocks = vi.hoisted(() => ({
  listSchedules: vi.fn(),
  runSchedule: vi.fn(),
  saveSchedule: vi.fn(),
  deleteSchedule: vi.fn(),
}));

vi.mock("@/lib/api/schedules", () => ({
  listSchedules: mocks.listSchedules,
  runSchedule: mocks.runSchedule,
  saveSchedule: mocks.saveSchedule,
  deleteSchedule: mocks.deleteSchedule,
}));

vi.mock("@/lib/events", () => ({
  emitTasksChanged: vi.fn(),
}));

const schedule = {
  name: "daily-report",
  cron: null,
  schedule_kind: "daily" as const,
  schedule_value: "09:00",
  task_type: "automation.run",
  lane: "automation",
  payload: { prompt: "整理今天的文件" },
  enabled: true,
  priority: 3,
  max_attempts: 2,
  timezone: "Asia/Shanghai",
  next_run_at: 1_750_000_000,
  last_run_at: 1_749_900_000,
  last_error: null,
};

describe("AutomationCenter", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listSchedules.mockResolvedValue({ schedules: [schedule] });
    mocks.runSchedule.mockResolvedValue({ queued: true, task: {}, schedule: schedule.name });
    mocks.saveSchedule.mockResolvedValue({ schedule: { ...schedule, enabled: false } });
    mocks.deleteSchedule.mockResolvedValue({ deleted: schedule.name });
  });

  it("displays schedule timing and queues an immediate run", async () => {
    render(<AutomationCenter />);
    await waitFor(() => expect(screen.getByText("daily-report")).toBeInTheDocument());
    expect(screen.getByText(/每天 09:00/)).toBeInTheDocument();
    expect(screen.getByText(/下次：/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "立即运行" }));
    await waitFor(() => expect(mocks.runSchedule).toHaveBeenCalledWith("daily-report"));
    expect(screen.getByText("daily-report 已进入任务队列")).toBeInTheDocument();
  });

  it("persists enable/disable changes with the full schedule contract", async () => {
    render(<AutomationCenter />);
    await waitFor(() => expect(screen.getByText("daily-report")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "停用" }));
    await waitFor(() => expect(mocks.saveSchedule).toHaveBeenCalledWith("daily-report", expect.objectContaining({
      enabled: false,
      scheduleKind: "daily",
      scheduleValue: "09:00",
      taskType: "automation.run",
      lane: "automation",
    })));
  });

  it("keeps the previous list visible when refresh fails", async () => {
    render(<AutomationCenter />);
    await waitFor(() => expect(screen.getByText("daily-report")).toBeInTheDocument());
    mocks.listSchedules.mockRejectedValueOnce(new Error("服务暂时不可用"));

    fireEvent.click(screen.getByRole("button", { name: "刷新自动化计划" }));
    await waitFor(() => expect(screen.getByText("服务暂时不可用")).toBeInTheDocument());
    expect(screen.getByText("daily-report")).toBeInTheDocument();
  });
});
