// LLM 协议与预设：协议枚举单一来源（Onboarding 与设置页共用）。
// 后端 PROVIDER_LABELS 仅作诊断文案；新增协议需与 backend LLMManager.ProviderType 同步。
export interface ProtocolOption {
  type: string;
  label: string;
  desc: string;
  defaultBaseUrl: string;
  baseUrlPresets: string[];
  placeholderModel: string;
}

export const PROTOCOLS: ProtocolOption[] = [
  {
    type: "openai_compat",
    label: "OpenAI 兼容",
    desc: "DeepSeek / Ollama / vLLM / Groq",
    defaultBaseUrl: "https://api.deepseek.com/v1",
    baseUrlPresets: [
      "https://api.deepseek.com/v1",
      "https://api.openai.com/v1",
      "https://api.groq.com/openai/v1",
      "http://localhost:11434/v1",
      "http://localhost:8000/v1",
    ],
    placeholderModel: "如 deepseek-v4-flash",
  },
  {
    type: "openai_responses",
    label: "OpenAI Responses",
    desc: "OpenAI 官方新协议",
    defaultBaseUrl: "https://api.openai.com/v1",
    baseUrlPresets: ["https://api.openai.com/v1"],
    placeholderModel: "如 gpt-5.4",
  },
  {
    type: "anthropic",
    label: "Anthropic",
    desc: "Claude 及兼容服务",
    defaultBaseUrl: "https://api.anthropic.com",
    baseUrlPresets: ["https://api.anthropic.com"],
    placeholderModel: "如 claude-sonnet-4-5",
  },
];

export const protocolOf = (type: string): ProtocolOption | undefined =>
  PROTOCOLS.find((p) => p.type === type);

export interface EmbeddingProviderOption {
  value: string;
  label: string;
  defaultBaseUrl: string;
  placeholderModel: string;
}

export const EMBEDDING_PROVIDERS: EmbeddingProviderOption[] = [
  {
    value: "jina",
    label: "Jina AI",
    defaultBaseUrl: "https://api.jina.ai/v1",
    placeholderModel: "jina-embeddings-v3",
  },
];
