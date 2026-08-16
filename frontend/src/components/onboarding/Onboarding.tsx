"use client";
import { useState } from "react";
import { configureLLM } from "@/lib/api/config";
import { useAppStore } from "@/lib/store";
import { PROTOCOLS, protocolOf } from "@/lib/llm-options";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Alert } from "@/components/ui/alert";

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
      <span className="block text-xs text-muted mb-1.5">{label}</span>
      <Input type={type} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)}
             className="w-full text-sm" />
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
          {PROTOCOLS.map((p) => (
            <button key={p.type} type="button" onClick={() => setType(p.type)}
                    className={`text-left bg-card border rounded-xl px-3.5 py-3 cursor-pointer transition-colors ${type === p.type ? "border-accent bg-accent-soft" : "border-border hover:bg-muted"}`}>
              <div className="font-semibold text-sm">{p.label}</div>
              <small className="text-muted text-xs">{p.desc}</small>
            </button>
          ))}
        </div>

        {field("接口地址", baseUrl, setBaseUrl, protocolOf(type)?.defaultBaseUrl || "https://...")}
        {field("模型名", model, setModel, protocolOf(type)?.placeholderModel || "模型名")}
        {field("API Key", apiKey, setApiKey, "sk-...", "password")}

        {msg && (
          <Alert variant={msg.ok ? "default" : "destructive"}
                 className={`mb-3.5 text-sm ${msg.ok ? "bg-success-soft text-success border-success/30" : "bg-danger-soft text-danger border-danger/30"}`}>{msg.text}</Alert>
        )}

        <div className="flex justify-end">
          <Button onClick={submit} disabled={busy || !baseUrl || !model || !apiKey}>
            {busy ? "测试连接中…" : "连接并启动"}
          </Button>
        </div>
      </div>
    </div>
  );
}
