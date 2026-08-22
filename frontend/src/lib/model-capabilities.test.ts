import { describe, expect, it } from "vitest";
import { supportsInlineImages } from "./model-capabilities";

describe("supportsInlineImages", () => {
  it("does not treat every Anthropic model as vision-capable", () => {
    expect(supportsInlineImages("anthropic", "claude-3-5-sonnet")).toBe(true);
    expect(supportsInlineImages("anthropic", "claude-sonnet-4-20250514")).toBe(true);
    expect(supportsInlineImages("anthropic", "claude-2.1")).toBe(false);
    expect(supportsInlineImages("openai_compat", "claude-3-haiku")).toBe(true);
    expect(supportsInlineImages("openai_compat", "claude-2.1")).toBe(false);
  });

  it("prefers an explicit provider capability over the model-name fallback", () => {
    expect(supportsInlineImages("openai_compat", "unknown-model", true)).toBe(true);
    expect(supportsInlineImages("openai_compat", "gpt-4o", false)).toBe(false);
  });
});
