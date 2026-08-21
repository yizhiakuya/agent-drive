import { beforeEach, describe, expect, it, vi } from "vitest"

import {
  revealEmbeddingApiKey,
  revealLlmApiKey,
  revealVisionApiKey,
} from "./config"

const mocks = vi.hoisted(() => ({ api: vi.fn() }))

vi.mock("./client", () => ({
  api: mocks.api,
}))

describe("config API key reveal", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockResolvedValue({ api_key: "saved-secret" })
  })

  it.each([
    [revealLlmApiKey, "/config/api-key/reveal"],
    [revealEmbeddingApiKey, "/config/embeddings/api-key/reveal"],
    [revealVisionApiKey, "/config/vision/api-key/reveal"],
  ])("uses an uncached POST endpoint", async (reveal, path) => {
    await expect(reveal()).resolves.toBe("saved-secret")
    expect(mocks.api).toHaveBeenCalledWith(path, { method: "POST", cache: "no-store" })
  })

  it("rejects a malformed secret response", async () => {
    mocks.api.mockResolvedValueOnce({ api_key: null })

    await expect(revealLlmApiKey()).rejects.toThrow("服务端未返回已保存的 API Key")
  })
})
