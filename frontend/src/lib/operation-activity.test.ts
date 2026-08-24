import { beforeEach, describe, expect, it } from "vitest";
import {
  clearFinishedOperationActivities,
  finishOperationActivity,
  getOperationActivitiesSnapshot,
  startOperationActivity,
  updateOperationActivity,
} from "./operation-activity";

describe("operation activity store", () => {
  beforeEach(() => {
    clearFinishedOperationActivities();
    window.localStorage.clear();
  });

  it("keeps a running activity and records its final counters", () => {
    const id = startOperationActivity({
      id: "test-vectorize",
      source: "ui",
      kind: "index-vector",
      title: "文件向量化",
      target: "docs/a.md",
      phase: "embedding",
      message: "正在生成向量",
      startedAt: 100,
      total: 4,
    });
    updateOperationActivity(id, { completed: 2, message: "已完成 2/4" });
    expect(getOperationActivitiesSnapshot()[0]).toMatchObject({
      id,
      status: "running",
      completed: 2,
    });

    finishOperationActivity(id, "succeeded", {
      phase: "finished",
      message: "已完成",
      completed: 4,
      succeeded: 4,
      failed: 0,
    });
    expect(getOperationActivitiesSnapshot()[0]).toMatchObject({
      status: "succeeded",
      unread: true,
      completed: 4,
      succeeded: 4,
    });
  });

  it("does not replace a finished operation with a second record", () => {
    const first = startOperationActivity({
      id: "same-operation",
      source: "agent",
      kind: "agent-index",
      title: "Agent 索引",
      phase: "running",
      message: "开始",
      startedAt: 100,
    });
    finishOperationActivity(first, "failed", { message: "失败" });
    const second = startOperationActivity({
      id: first,
      source: "agent",
      kind: "agent-index",
      title: "Agent 索引",
      phase: "running",
      message: "重试",
      startedAt: 200,
    });
    expect(second).toBe(first);
    expect(getOperationActivitiesSnapshot()).toHaveLength(1);
    expect(getOperationActivitiesSnapshot()[0]).toMatchObject({ status: "running", message: "重试" });
  });
});
