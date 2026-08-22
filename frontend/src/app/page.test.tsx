import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Home from "./page";
import { useAppStore } from "@/lib/store";

const mocks = vi.hoisted(() => ({
  authenticatedFetch: vi.fn(),
  ensureBase: vi.fn(),
  getDeviceToken: vi.fn(),
  getStatus: vi.fn(),
  getConfig: vi.fn(),
  isNativePlatform: vi.fn(),
  getServer: vi.fn(),
  heartbeat: vi.fn(),
  currentServer: vi.fn(),
}));

vi.mock("@capacitor/core", () => ({
  Capacitor: { isNativePlatform: mocks.isNativePlatform },
}));

vi.mock("@/lib/api/client", () => ({
  ApiError: class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
    }
  },
  authenticatedFetch: mocks.authenticatedFetch,
  ensureBase: mocks.ensureBase,
  getDeviceToken: mocks.getDeviceToken,
}));

vi.mock("@/lib/api/config", () => ({
  getStatus: mocks.getStatus,
  getConfig: mocks.getConfig,
}));

vi.mock("@/lib/native/server-config", () => ({
  ServerConfig: {
    getServer: mocks.getServer,
    heartbeat: mocks.heartbeat,
  },
  currentServer: mocks.currentServer,
}));

vi.mock("@/components/chat/ChatPanel", () => ({
  default: () => <input aria-label="chat draft" defaultValue="" />,
}));
vi.mock("@/components/files/FilePanel", () => ({ default: () => <div>file panel</div> }));
vi.mock("@/components/files/FilePage", () => ({ default: () => <div>files</div> }));
vi.mock("@/components/sessions/SessionList", () => ({ default: () => <div>sessions</div> }));
vi.mock("@/components/settings/SettingsPage", () => ({ default: () => <div>settings</div> }));
vi.mock("@/components/onboarding/Onboarding", () => ({ default: () => <div>onboarding</div> }));
vi.mock("@/components/auth/LoginCard", () => ({
  default: ({ mode }: { mode: string }) => <div>login:{mode}</div>,
}));
vi.mock("@/components/auth/RescanCard", () => ({ default: () => <div>rescan</div> }));
vi.mock("@/components/ToastStack", () => ({ default: () => null }));
vi.mock("@/components/PullToRefresh", () => ({
  default: ({ onRefresh }: { onRefresh: () => Promise<void> }) => (
    <button type="button" onClick={() => void onRefresh()}>refresh all</button>
  ),
}));
vi.mock("@/components/WorkspaceHeader", () => ({ default: () => <div>header</div> }));

function authResponse(status: number, initialized: unknown = true): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue({ initialized }),
  } as unknown as Response;
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((settle) => { resolve = settle; });
  return { promise, resolve };
}

describe("Home startup error handling", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.isNativePlatform.mockReturnValue(false);
    mocks.ensureBase.mockResolvedValue(undefined);
    mocks.getServer.mockResolvedValue({ server: "https://drive.example" });
    mocks.heartbeat.mockResolvedValue({ sent: true });
    mocks.currentServer.mockResolvedValue("https://drive.example");
    mocks.getConfig.mockResolvedValue({ configured: false });
    useAppStore.setState({
      loading: true,
      authMode: "loading",
      configured: false,
      modelName: "",
      tab: "chat",
    });
  });

  afterEach(() => cleanup());

  it("shows login only for an unauthorized auth status", async () => {
    mocks.authenticatedFetch.mockResolvedValue(authResponse(401));

    render(<Home />);

    expect(await screen.findByText("login:login")).toBeInTheDocument();
  });

  it("shows a retryable server error for auth 5xx", async () => {
    mocks.authenticatedFetch.mockResolvedValue(authResponse(503));

    render(<Home />);

    expect(await screen.findByRole("heading", { name: "暂时无法连接服务器" })).toBeInTheDocument();
  });

  it("shows a retryable server error for a network failure", async () => {
    mocks.authenticatedFetch.mockRejectedValue(new TypeError("Failed to fetch"));

    render(<Home />);

    expect(await screen.findByRole("heading", { name: "暂时无法连接服务器" })).toBeInTheDocument();
  });

  it("rejects an auth status payload with a non-boolean initialized flag", async () => {
    mocks.authenticatedFetch.mockResolvedValue(authResponse(200, "yes"));

    render(<Home />);

    expect(await screen.findByRole("heading", { name: "暂时无法连接服务器" })).toBeInTheDocument();
    expect(screen.queryByText("login:setup")).not.toBeInTheDocument();
  });

  it("does not turn a configuration status failure into a login screen", async () => {
    mocks.authenticatedFetch.mockResolvedValue(authResponse(200));
    mocks.getStatus.mockRejectedValue({ status: 500 });

    render(<Home />);

    expect(await screen.findByRole("heading", { name: "暂时无法连接服务器" })).toBeInTheDocument();
    expect(screen.queryByText("login:login")).not.toBeInTheDocument();
  });

  it("rejects a configuration status payload with a non-boolean configured flag", async () => {
    mocks.authenticatedFetch.mockResolvedValue(authResponse(200));
    mocks.getStatus.mockResolvedValue({ configured: "yes" });

    render(<Home />);

    expect(await screen.findByRole("heading", { name: "暂时无法连接服务器" })).toBeInTheDocument();
    expect(screen.queryByText("onboarding")).not.toBeInTheDocument();
  });

  it("retries the complete startup check and recovers", async () => {
    mocks.authenticatedFetch
      .mockResolvedValueOnce(authResponse(503))
      .mockResolvedValueOnce(authResponse(200));
    mocks.getStatus.mockResolvedValue({ configured: false });

    render(<Home />);
    await screen.findByRole("heading", { name: "暂时无法连接服务器" });
    fireEvent.click(screen.getByRole("button", { name: "重新检查" }));

    expect(await screen.findByText("onboarding")).toBeInTheDocument();
    expect(mocks.authenticatedFetch).toHaveBeenCalledTimes(2);
  });

  it("keeps the chat draft mounted while a pull refresh is pending", async () => {
    mocks.authenticatedFetch.mockResolvedValueOnce(authResponse(200));
    mocks.getStatus.mockResolvedValue({ configured: true });
    mocks.getConfig.mockResolvedValue({ llm: { model: "test-model" } });

    render(<Home />);
    const draft = await screen.findByRole("textbox", { name: "chat draft" });
    fireEvent.change(draft, { target: { value: "unfinished message" } });

    const refresh = deferred<Response>();
    mocks.authenticatedFetch.mockReturnValueOnce(refresh.promise);
    fireEvent.click(screen.getByRole("button", { name: "refresh all" }));
    await waitFor(() => expect(mocks.authenticatedFetch).toHaveBeenCalledTimes(2));

    expect(screen.getByRole("textbox", { name: "chat draft" })).toBe(draft);
    expect(draft).toHaveValue("unfinished message");
    expect(useAppStore.getState().loading).toBe(true);

    await act(async () => { refresh.resolve(authResponse(200)); });
    await waitFor(() => expect(useAppStore.getState().loading).toBe(false));
  });
});
