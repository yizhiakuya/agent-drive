import { act, fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import OperationActivityCenter from "./OperationActivityCenter";
import {
  clearFinishedOperationActivities,
  finishOperationActivity,
  startOperationActivity,
} from "@/lib/operation-activity";

describe("OperationActivityCenter", () => {
  beforeEach(() => {
    clearFinishedOperationActivities();
    window.localStorage.clear();
  });

  it("shows running work and a finished result in the global panel", () => {
    const id = startOperationActivity({
      id: "activity-center-test",
      source: "ui",
      kind: "index-vector",
      title: "文件向量化",
      target: "docs/a.md",
      phase: "embedding",
      message: "正在生成向量",
      startedAt: Date.now(),
      completed: 1,
      total: 3,
    });
    render(<OperationActivityCenter />);
    fireEvent.click(screen.getByRole("button", { name: "打开操作活动中心" }));
    expect(screen.getByRole("dialog", { name: "操作活动中心" })).toBeInTheDocument();
    expect(screen.getByText("正在生成向量")).toBeInTheDocument();
    act(() => finishOperationActivity(id, "partial", {
      phase: "finished",
      message: "已处理 2/3 项",
      completed: 3,
      succeeded: 2,
      failed: 1,
      error: "1 项操作失败",
    }));
    expect(screen.getByText("部分完成")).toBeInTheDocument();
    expect(screen.getByText(/1 项操作失败/)).toBeInTheDocument();
  });
});
