import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getConfig, listModels } from "@/lib/api/config";
import { useModelCatalog } from "./useModelCatalog";

vi.mock("@/lib/api/config", () => ({ getConfig: vi.fn(), listModels: vi.fn() }));

const getConfigMock = vi.mocked(getConfig);
const listModelsMock = vi.mocked(listModels);

const config = {
  configured: true,
  llm: {
    type: "openai_compat",
    base_url: "https://provider.example/v1",
    api_key_masked: "sk-****",
    model: "text-model",
    supports_images: false,
  },
};

describe("useModelCatalog", () => {
  beforeEach(() => {
    getConfigMock.mockReset();
    listModelsMock.mockReset();
    getConfigMock.mockResolvedValue(config);
  });

  it("loads model capabilities without changing the saved default", async () => {
    listModelsMock.mockResolvedValue({
      ok: true,
      models: ["text-model", "vision-model"],
      model_capabilities: { "text-model": false, "vision-model": true },
    });
    const { result } = renderHook(() => useModelCatalog("text-model"));

    await act(async () => {
      await Promise.all([result.current.loadModels(), result.current.loadModels()]);
    });

    expect(listModelsMock).toHaveBeenCalledOnce();
    expect(result.current.selectedModel).toBe("text-model");
    expect(result.current.modelOptions).toEqual(["text-model", "vision-model"]);
    act(() => result.current.setSelectedModel("vision-model"));
    expect(result.current.modelSupportsImages).toBe(true);
  });

  it("drops a late model response after the configured model changes", async () => {
    let resolveModels!: (value: Awaited<ReturnType<typeof listModels>>) => void;
    listModelsMock.mockImplementation(() => new Promise((resolve) => { resolveModels = resolve; }));
    const { result, rerender } = renderHook(
      ({ model }) => useModelCatalog(model),
      { initialProps: { model: "old-model" } },
    );

    let pending!: Promise<void>;
    act(() => { pending = result.current.loadModels(); });
    await waitFor(() => expect(listModelsMock).toHaveBeenCalledOnce());
    rerender({ model: "new-model" });

    await act(async () => {
      resolveModels({ ok: true, models: ["stale-model"], model_capabilities: { "stale-model": true } });
      await pending;
    });

    expect(result.current.selectedModel).toBe("new-model");
    expect(result.current.modelOptions).toEqual(["new-model"]);
    expect(result.current.modelsLoading).toBe(false);
  });
});
