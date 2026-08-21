import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Onboarding from "./Onboarding";

const mocks = vi.hoisted(() => ({
  configureLLM: vi.fn(),
  listModels: vi.fn(),
  setConfigured: vi.fn(),
}));

vi.mock("@/lib/api/config", () => ({
  configureLLM: mocks.configureLLM,
  listModels: mocks.listModels,
}));

vi.mock("@/lib/store", () => ({
  useAppStore: (selector: (state: { setConfigured: typeof mocks.setConfigured }) => unknown) =>
    selector({ setConfigured: mocks.setConfigured }),
}));

describe("Onboarding", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listModels.mockResolvedValue({ ok: true, models: ["model-a", "model-b"] });
  });

  it("允许查看和重新隐藏本次输入的模型 API Key", () => {
    render(<Onboarding />);

    const input = screen.getByLabelText("API Key");
    fireEvent.change(input, { target: { value: "sk-onboarding-draft" } });
    expect(input).toHaveAttribute("type", "password");

    fireEvent.click(screen.getByRole("button", { name: "显示本次输入的 API Key" }));
    expect(input).toHaveAttribute("type", "text");

    fireEvent.click(screen.getByRole("button", { name: "隐藏 API Key" }));
    expect(input).toHaveAttribute("type", "password");
  });

  it("获取并选择模型列表中的模型", async () => {
    render(<Onboarding />);

    fireEvent.change(screen.getByPlaceholderText("sk-..."), {
      target: { value: "test-key" },
    });
    fireEvent.click(screen.getByRole("button", { name: "获取模型" }));

    await waitFor(() => expect(mocks.listModels).toHaveBeenCalledWith({
      type: "openai_compat",
      base_url: "https://api.deepseek.com/v1",
      api_key: "test-key",
    }));
    expect(await screen.findByRole("option", { name: "model-a" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("option", { name: "model-b" }));
    expect(screen.getByRole("combobox", { name: "模型名" })).toHaveValue("model-b");
  });

  it("协议或接口地址变化时销毁旧 API Key 草稿", () => {
    render(<Onboarding />);

    const key = screen.getByLabelText("API Key");
    fireEvent.change(key, { target: { value: "sk-old-provider" } });
    fireEvent.click(screen.getByRole("button", { name: /Anthropic/ }));
    expect(key).toHaveValue("");

    fireEvent.change(key, { target: { value: "sk-old-address" } });
    fireEvent.change(screen.getByLabelText("接口地址"), {
      target: { value: "https://new.example/v1" },
    });
    expect(key).toHaveValue("");
  });
});
