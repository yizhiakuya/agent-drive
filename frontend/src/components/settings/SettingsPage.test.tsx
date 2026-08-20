import { act, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EV } from "@/lib/events";
import SettingsPage from "./SettingsPage";

const mocks = vi.hoisted(() => ({
  getConfig: vi.fn(),
  getVisionConfig: vi.fn(),
  setAuthMode: vi.fn(),
  setDeviceToken: vi.fn(),
  authenticatedFetch: vi.fn(),
  clearDeviceToken: vi.fn(),
}));

vi.mock("@/lib/api/config", () => ({
  getConfig: mocks.getConfig,
  getVisionConfig: mocks.getVisionConfig,
  saveEmbeddings: vi.fn(),
  saveVision: vi.fn(),
  configureLLM: vi.fn(),
  listModels: vi.fn(),
  listVisionModels: vi.fn(),
}));

vi.mock("@capacitor/core", () => ({
  Capacitor: { isNativePlatform: () => false },
}));

vi.mock("@/lib/api/client", () => ({
  apiErrorMessage: vi.fn((_body: unknown, fallback: string) => fallback),
  authenticatedFetch: mocks.authenticatedFetch,
  setDeviceToken: mocks.setDeviceToken,
}));

vi.mock("@/lib/native/server-config", () => ({
  ServerConfig: { clearDeviceToken: mocks.clearDeviceToken },
}));

vi.mock("@/lib/store", () => ({
  useAppStore: (selector: (state: { setAuthMode: typeof mocks.setAuthMode }) => unknown) =>
    selector({ setAuthMode: mocks.setAuthMode }),
}));

vi.mock("./ConnectAppCard", () => ({ default: () => null }));
vi.mock("./DevicesCard", () => ({ default: () => null }));
vi.mock("./PhotoSyncCard", () => ({ default: () => null }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => { resolve = res; });
  return { promise, resolve };
}

function config(model: string, baseUrl = "https://example.com/v1") {
  return {
    configured: true,
    llm: {
      type: "openai_compat",
      base_url: baseUrl,
      model,
      api_key_masked: "sk-***",
    },
    embeddings: {
      provider: "jina",
      base_url: "https://api.jina.ai/v1",
      model: "jina-embeddings-v3",
      api_key_masked: "jina_***",
    },
    preferences: {},
  };
}

function visionConfig() {
  return {
    configured: false,
    provider: "openai_compat",
    base_url: "https://api.openai.com/v1",
    model: "",
    api_key_masked: "",
  };
}

describe("SettingsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getConfig.mockResolvedValue(config("default-model"));
    mocks.getVisionConfig.mockResolvedValue(visionConfig());
  });

  it("忽略过期的配置加载响应", async () => {
    const first = deferred<ReturnType<typeof config>>();
    const second = deferred<ReturnType<typeof config>>();
    mocks.getConfig
      .mockReset()
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(() => second.promise);

    render(<SettingsPage />);
    await waitFor(() => expect(mocks.getConfig).toHaveBeenCalledTimes(1));
    await act(async () => {
      window.dispatchEvent(new CustomEvent(EV.refresh));
    });
    await waitFor(() => expect(mocks.getConfig).toHaveBeenCalledTimes(2));

    second.resolve(config("new-model", "https://new.example/v1"));
    await waitFor(() => expect(screen.getByDisplayValue("https://new.example/v1")).toBeInTheDocument());
    expect(screen.getByDisplayValue("new-model")).toBeInTheDocument();

    first.resolve(config("old-model", "https://old.example/v1"));
    await act(async () => { await first.promise; });
    expect(screen.getByDisplayValue("https://new.example/v1")).toBeInTheDocument();
    expect(screen.getByDisplayValue("new-model")).toBeInTheDocument();
    expect(screen.queryByDisplayValue("https://old.example/v1")).not.toBeInTheDocument();
  });
});
