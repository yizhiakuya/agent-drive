"use client";
import { useState } from "react";
import { configureLLM } from "@/lib/api/config";
import { useAppStore } from "@/lib/store";

const PROVIDERS = [
  { type: "openai_compat", name: "OpenAI 兼容", desc: "DeepSeek / Ollama / vLLM / Groq 等" },
  { type: "openai_responses", name: "OpenAI Responses", desc: "OpenAI 官方新协议" },
  { type: "anthropic", name: "Anthropic", desc: "Claude 及兼容服务" },
];

export default function Onboarding() {
  const setConfigured = useAppStore((s) => s.setConfigured);
  const [type, setType] = useState("openai_compat");
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  async function submit() {
    setBusy(true);
    setMsg(null);
    try {
      const r = await configureLLM({ type, base_url: baseUrl, api_key: apiKey, model }) as { ok?: boolean; test?: Record<string, unknown> };
      if (r.ok === false) {
        setMsg({ ok: false, text: "连接测试失败: " + JSON.stringify(r.test) });
      } else {
        setConfigured(true);
      }
    } catch (e) {
      setMsg({ ok: false, text: String(e) });
    } finally {
      setBusy(false);
    }
  }

  const field = (label: string, value: string, onChange: (v: string) => void, placeholder: string, type = "text") => (
    <label className="block mb-4">
      <span className="block text-xs text-muted mb-1.5 font-semibold">{label}</span>
      <input type={type} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)}
             className="w-full bg-card border border-border text-text px-3.5 py-2.5 rounded-lg outline-none text-sm focus:border-accent" />
    </label>
  );

  return (
    <div className="flex items-center justify-center min-h-screen p-5">
      <div className="w-[640px] bg-panel border border-border rounded-2xl p-8 shadow-md">
        <div className="text-center mb-6">
          <div className="text-5xl mb-2">🦋</div>
          <h1 className="text-xl font-bold">欢迎使用 Agent Drive</h1>
          <p className="text-muted text-sm mt-2 leading-relaxed">AI 中心的私人网盘 — 配置好 Agent 后，一切通过对话完成</p>
        </div>

        <div className="grid gap-2 mb-4">
          {PROVIDERS.map((p) => (
            <button key={p.type} onClick={() => setType(p.type)}
                    className={`text-left bg-card border rounded-xl px-3.5 py-3 cursor-pointer ${type === p.type ? "border-accent bg-accent-soft" : "border-border"}`}>
              <div className="font-semibold text-sm">{p.name}</div>
              <small className="text-muted text-xs">{p.desc}</small>
            </button>
          ))}
        </div>

        {field("接口地址", baseUrl, setBaseUrl, "https://api.deepseek.com/v1")}
        {field("模型名", model, setModel, "如 deepseek-v4-flash")}
        {field("API Key", apiKey, setApiKey, "sk-...", "password")}

        {msg && (
          <div className={`px-3.5 py-2.5 rounded-lg mb-3.5 text-sm ${msg.ok ? "bg-success-soft text-success" : "bg-danger-soft text-danger"}`}>{msg.text}</div>
        )}

        <div className="flex justify-end">
          <button className="bg-accent text-white px-5 py-2.5 rounded-lg font-semibold cursor-pointer disabled:opacity-60"
                  onClick={submit} disabled={busy || !baseUrl || !model || !apiKey}>
            {busy ? "测试连接中…" : "连接并启动"}
          </button>
        </div>
      </div>
    </div>
  );
}
