import { describe, expect, it, vi } from "vitest";
import { vectorize } from "./index";

const api = vi.fn();

vi.mock("./client", () => ({
  api: (...args: unknown[]) => api(...args),
}));

describe("index API", () => {
  it("sends an empty paths list for owner-wide vectorization", async () => {
    api.mockResolvedValue({ vectorized: true });

    await vectorize();

    expect(api).toHaveBeenCalledWith("/index/vectors", {
      method: "PUT",
      body: JSON.stringify({ paths: [], force: false, limit: 64 }),
    });
  });
});
