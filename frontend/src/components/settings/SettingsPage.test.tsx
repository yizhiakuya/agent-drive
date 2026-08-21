import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
  listModels: vi.fn(),
  listVisionModels: vi.fn(),
  configureLLM: vi.fn(),
  saveEmbeddings: vi.fn(),
  saveVision: vi.fn(),
}));

vi.mock("@/lib/api/config", () => ({
  getConfig: mocks.getConfig,
  getVisionConfig: mocks.getVisionConfig,
  saveEmbeddings: mocks.saveEmbeddings,
  saveVision: mocks.saveVision,
  configureLLM: mocks.configureLLM,
  listModels: mocks.listModels,
  listVisionModels: mocks.listVisionModels,
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
vi.mock("./SkillsManager", () => ({ default: () => null }));

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
    mocks.listModels.mockResolvedValue({ ok: true, models: ["new-model"] });
    mocks.listVisionModels.mockResolvedValue({ ok: true, models: ["new-vision-model"] });
    mocks.configureLLM.mockResolvedValue({ ok: true });
    mocks.saveEmbeddings.mockResolvedValue({ ok: true, test: { ok: true, dimensions: 1024 } });
    mocks.saveVision.mockResolvedValue({ ok: true });
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

  it("切换接口后忽略过期的模型列表响应", async () => {
    const pending = deferred<{ ok: true; models: string[] }>();
    mocks.listModels.mockImplementationOnce(() => pending.promise);

    render(<SettingsPage />);
    await waitFor(() => expect(screen.getByDisplayValue("https://example.com/v1")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "获取模型" }));
    await waitFor(() => expect(mocks.listModels).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByDisplayValue("https://example.com/v1"), {
      target: { value: "https://new.example/v1" },
    });
    pending.resolve({ ok: true, models: ["old-model"] });
    await act(async () => { await pending.promise; });

    expect(screen.queryByText("old-model")).not.toBeInTheDocument();
  });

  it("切换视觉接口后忽略过期的视觉模型响应", async () => {
    const pending = deferred<{ ok: true; models: string[] }>();
    mocks.listVisionModels.mockImplementationOnce(() => pending.promise);

    render(<SettingsPage />);
    await waitFor(() => expect(screen.getByDisplayValue("https://api.openai.com/v1")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "获取视觉模型" }));
    await waitFor(() => expect(mocks.listVisionModels).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByDisplayValue("https://api.openai.com/v1"), {
      target: { value: "https://vision-new.example/v1" },
    });
    pending.resolve({ ok: true, models: ["old-vision-model"] });
    await act(async () => { await pending.promise; });

    expect(screen.queryByText("old-vision-model")).not.toBeInTheDocument();
  });

  it("配置边界变化时清空对应的明文密钥", async () => {
    render(<SettingsPage />);
    await waitFor(() => expect(screen.getByDisplayValue("https://example.com/v1")).toBeInTheDocument());

    const llm = within(screen.getByRole("heading", { name: "LLM 模型" }).closest("section")!);
    const embeddings = within(screen.getByRole("heading", { name: /向量化/ }).closest("section")!);
    const vision = within(screen.getByRole("heading", { name: /视觉模型/ }).closest("section")!);
    const llmKey = llm.getByLabelText("API Key");
    fireEvent.change(llmKey, { target: { value: "sk-llm-draft" } });
    expect(llmKey).toHaveValue("sk-llm-draft");
    fireEvent.change(llm.getByLabelText("接口地址"), {
      target: { value: "https://new.example/v1" },
    });
    expect(llmKey).toHaveValue("");

    fireEvent.change(llmKey, { target: { value: "sk-protocol-draft" } });
    fireEvent.click(llm.getByRole("button", { name: /Anthropic/ }));
    expect(llmKey).toHaveValue("");

    const embeddingKey = embeddings.getByLabelText("API Key");
    fireEvent.change(embeddingKey, { target: { value: "jina_draft" } });
    fireEvent.change(embeddings.getByLabelText("模型"), {
      target: { value: "jina-embeddings-v4" },
    });
    expect(embeddingKey).toHaveValue("");

    const visionKey = vision.getByLabelText("API Key");
    fireEvent.change(visionKey, { target: { value: "sk-vision-draft" } });
    fireEvent.change(vision.getByLabelText("接口地址"), {
      target: { value: "https://vision.example/v1" },
    });
    expect(visionKey).toHaveValue("");
  });

  it("所有模型 API Key 输入都可以查看和重新隐藏当前草稿", async () => {
    render(<SettingsPage />);
    await waitFor(() => expect(screen.getByDisplayValue("https://example.com/v1")).toBeInTheDocument());

    const sections = [
      within(screen.getByRole("heading", { name: "LLM 模型" }).closest("section")!),
      within(screen.getByRole("heading", { name: /向量化/ }).closest("section")!),
      within(screen.getByRole("heading", { name: /视觉模型/ }).closest("section")!),
    ];

    sections.forEach((section, index) => {
      const input = section.getByLabelText("API Key");
      fireEvent.change(input, { target: { value: `draft-${index}` } });
      expect(input).toHaveAttribute("type", "password");

      fireEvent.click(section.getByRole("button", { name: "显示本次输入的 API Key" }));
      expect(input).toHaveAttribute("type", "text");

      fireEvent.click(section.getByRole("button", { name: "隐藏本次输入的 API Key" }));
      expect(input).toHaveAttribute("type", "password");
    });
  });

  it("重载无配置响应时也销毁所有明文密钥", async () => {
    render(<SettingsPage />);
    await waitFor(() => expect(screen.getByDisplayValue("https://example.com/v1")).toBeInTheDocument());

    const llm = within(screen.getByRole("heading", { name: "LLM 模型" }).closest("section")!);
    const embeddings = within(screen.getByRole("heading", { name: /向量化/ }).closest("section")!);
    const vision = within(screen.getByRole("heading", { name: /视觉模型/ }).closest("section")!);
    const llmKey = llm.getByLabelText("API Key");
    const embeddingKey = embeddings.getByLabelText("API Key");
    const visionKey = vision.getByLabelText("API Key");
    fireEvent.change(llmKey, { target: { value: "sk-llm-draft" } });
    fireEvent.change(embeddingKey, { target: { value: "jina_draft" } });
    fireEvent.change(visionKey, { target: { value: "sk-vision-draft" } });

    mocks.getConfig.mockResolvedValue({ configured: false, preferences: {} });
    mocks.getVisionConfig.mockResolvedValue(visionConfig());
    await act(async () => {
      window.dispatchEvent(new CustomEvent(EV.refresh));
    });

    await waitFor(() => {
      expect(llmKey).toHaveValue("");
      expect(embeddingKey).toHaveValue("");
      expect(visionKey).toHaveValue("");
    });
  });

  it("保存成功后不在表单状态中保留明文密钥", async () => {
    mocks.getVisionConfig.mockResolvedValue({
      ...visionConfig(),
      configured: true,
      model: "vision-model",
      api_key_masked: "sk-v-***",
    });
    render(<SettingsPage />);
    await waitFor(() => expect(screen.getByDisplayValue("vision-model")).toBeInTheDocument());

    const llm = within(screen.getByRole("heading", { name: "LLM 模型" }).closest("section")!);
    const embeddings = within(screen.getByRole("heading", { name: /向量化/ }).closest("section")!);
    const vision = within(screen.getByRole("heading", { name: /视觉模型/ }).closest("section")!);
    const llmKey = llm.getByLabelText("API Key");
    fireEvent.change(llmKey, { target: { value: "sk-llm-secret" } });
    expect(llmKey).toHaveValue("sk-llm-secret");
    fireEvent.click(llm.getByRole("button", { name: "保存并测试连接" }));
    await waitFor(() => expect(mocks.configureLLM).toHaveBeenCalledWith(
      expect.objectContaining({ api_key: "sk-llm-secret" }),
    ));
    await waitFor(() => expect(llmKey).toHaveValue(""));
    await waitFor(() => expect(embeddings.getByRole("button", { name: "保存并测试" })).toBeEnabled());

    const embeddingKey = embeddings.getByLabelText("API Key");
    fireEvent.change(embeddingKey, { target: { value: "jina_secret" } });
    expect(embeddingKey).toHaveValue("jina_secret");
    fireEvent.click(embeddings.getByRole("button", { name: "保存并测试" }));
    await waitFor(() => expect(mocks.saveEmbeddings).toHaveBeenCalledWith(
      expect.objectContaining({ api_key: "jina_secret" }),
    ));
    await waitFor(() => expect(embeddingKey).toHaveValue(""));
    await waitFor(() => expect(vision.getByRole("button", { name: "保存并测试" })).toBeEnabled());

    const visionKey = vision.getByLabelText("API Key");
    fireEvent.change(visionKey, { target: { value: "sk-vision-secret" } });
    expect(visionKey).toHaveValue("sk-vision-secret");
    fireEvent.click(vision.getByRole("button", { name: "保存并测试" }));
    await waitFor(() => expect(mocks.saveVision).toHaveBeenCalledWith(
      expect.objectContaining({ api_key: "sk-vision-secret" }),
    ));
    await waitFor(() => expect(visionKey).toHaveValue(""));
  });
});
