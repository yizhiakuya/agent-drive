import { describe, it, expect } from "vitest";
import { PROTOCOLS, protocolOf, EMBEDDING_PROVIDERS } from "./llm-options";

describe("llm-options（协议枚举单一来源）", () => {
  it("协议类型与后端 ProviderType 三值一致", () => {
    expect(PROTOCOLS.map((p) => p.type).sort()).toEqual([
      "anthropic",
      "openai_compat",
      "openai_responses",
    ]);
  });

  it("每个协议都有合法默认地址与模型占位", () => {
    for (const p of PROTOCOLS) {
      expect(p.defaultBaseUrl).toMatch(/^https?:\/\//);
      expect(p.placeholderModel.length).toBeGreaterThan(0);
      expect(p.baseUrlPresets.length).toBeGreaterThan(0);
    }
  });

  it("protocolOf 命中/未命中行为", () => {
    expect(protocolOf("openai_compat")?.defaultBaseUrl).toMatch(/^https?:\/\//);
    expect(protocolOf("nope")).toBeUndefined();
  });

  it("embedding provider 仅 Jina 一项（后端同样只接受 jina）", () => {
    expect(EMBEDDING_PROVIDERS).toHaveLength(1);
    expect(EMBEDDING_PROVIDERS[0].value).toBe("jina");
  });
});
