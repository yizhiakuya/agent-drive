import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Onboarding from "./Onboarding";

const mocks = vi.hoisted(() => ({
  configureLLM: vi.fn(),
  listModels: vi.fn(),
}));

vi.mock("@/lib/api/config", () => ({
  configureLLM: mocks.configureLLM,
  listModels: mocks.listModels,
}));

describe("Onboarding", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listModels.mockResolvedValue({ ok: true, models: ["model-a", "model-b"] });
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
    await waitFor(() => expect(screen.getByRole("option", { name: "model-a" })).toBeInTheDocument());

    fireEvent.click(screen.getByRole("option", { name: "model-b" }));
    expect(screen.getByRole("combobox", { name: "模型名" })).toHaveValue("model-b");
  });
});
