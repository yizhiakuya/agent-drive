import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import SystemStatusCenter from "./SystemStatusCenter";

const mocks = vi.hoisted(() => ({
  getReadiness: vi.fn(),
  getConfig: vi.fn(),
  getStatus: vi.fn(),
  getDevices: vi.fn(),
  listFiles: vi.fn(),
}));

vi.mock("@/lib/api/readiness", () => ({ getReadiness: mocks.getReadiness }));
vi.mock("@/lib/api/config", () => ({ getConfig: mocks.getConfig, getStatus: mocks.getStatus }));
vi.mock("@/lib/api/devices", () => ({ getDevices: mocks.getDevices }));
vi.mock("@/lib/api/files", () => ({ listFiles: mocks.listFiles }));

describe("SystemStatusCenter", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getReadiness.mockResolvedValue({
      ready: true,
      database: { ok: true, detail: "PostgreSQL 可用" },
      storage: { ok: true, free_bytes: 60, total_bytes: 100 },
      backup: { ok: true, retained: 3, last_backup_at: 1_750_000_000 },
    });
    mocks.getConfig.mockResolvedValue({ configured: true, llm: { model: "gpt-test" } });
    mocks.getStatus.mockResolvedValue({ embeddings: { configured: true } });
    mocks.getDevices.mockResolvedValue({ devices: [{ id: "phone-1", sync: { enabled: true, last_error: null } }] });
    mocks.listFiles.mockResolvedValue({ disk: { used: 40, total: 100, free: 60 } });
  });

  it("aggregates readiness, provider, index, storage and device states", async () => {
    render(<SystemStatusCenter />);
    await waitFor(() => expect(screen.getByText("系统状态")).toBeInTheDocument());
    expect(screen.getByText("数据库")).toBeInTheDocument();
    expect(screen.getByText("对话 Provider")).toBeInTheDocument();
    expect(screen.getByText("语义索引")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /语义索引.*正常/ })).toBeInTheDocument();
    expect(screen.getByText("相册同步")).toBeInTheDocument();
    expect(screen.getByText("备份")).toBeInTheDocument();
    expect(screen.getByText("Jina embedding 已配置")).toBeInTheDocument();
    expect(screen.getByText("1 台已登记")).toBeInTheDocument();
    expect(screen.getByText(/保留 3 份/)).toBeInTheDocument();
  });

  it("retains available results and reports partial request failures", async () => {
    mocks.getDevices.mockRejectedValueOnce(new Error("设备接口不可用"));
    render(<SystemStatusCenter />);
    await waitFor(() => expect(screen.getByText(/1 项状态检查暂时不可用/)).toBeInTheDocument());
    expect(screen.getByText("PostgreSQL 可用")).toBeInTheDocument();
    expect(screen.getByText("Jina embedding 已配置")).toBeInTheDocument();
  });

  it("refreshes all checks from the explicit refresh control", async () => {
    render(<SystemStatusCenter />);
    await waitFor(() => expect(screen.getByText("系统状态")).toBeInTheDocument());
    const callsBefore = mocks.getReadiness.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "刷新系统状态" }));
    await waitFor(() => expect(mocks.getReadiness.mock.calls.length).toBeGreaterThan(callsBefore));
  });
});
