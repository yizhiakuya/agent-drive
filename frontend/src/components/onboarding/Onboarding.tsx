"use client";
import { useState } from "react";
import { configureLLM } from "@/lib/api/config";
import { useAppStore } from "@/lib/store";
import { PROTOCOLS, protocolOf } from "@/lib/llm-options";
import { Input } from "@/components/ui/input";
import { SecretInput } from "@/components/ui/secret-input";
import { Button } from "@/components/ui/button";
import { Alert } from "@/components/ui/alert";
import { ArrowRight, Bot, Check, HardDrive } from "lucide-react";

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

  const field = (id: string, label: string, value: string, onChange: (v: string) => void, placeholder: string, type = "text") => (
    <div className="mb-4">
      <label htmlFor={id} className="mb-1.5 block text-xs text-muted">{label}</label>
      {type === "password" ? (
        <SecretInput id={id} value={value} placeholder={placeholder}
                     onChange={(e) => onChange(e.target.value)} className="w-full text-sm" />
      ) : (
        <Input id={id} type={type} value={value} placeholder={placeholder}
               onChange={(e) => onChange(e.target.value)} className="w-full text-sm" />
      )}
    </div>
  );

  return (
    <div className="flex min-h-screen items-center justify-center bg-bg p-5">
      <div className="w-full max-w-[640px] border border-border bg-panel p-6 sm:p-8">
        <div className="mb-6 text-center">
          <span className="mx-auto mb-4 grid size-10 place-items-center bg-text text-panel"><HardDrive className="size-5" /></span>
          <h1 className="flex items-center justify-center gap-2 text-xl font-bold"><Bot className="size-5 text-muted" /> 欢迎使用 Agent Drive</h1>
          <p className="text-muted text-sm mt-2 leading-relaxed">AI 中心的私人网盘 — 配置好 Agent 后，一切通过对话完成</p>
        </div>

        <div className="grid gap-2 mb-4">
          {PROTOCOLS.map((p) => (
            <button key={p.type} type="button" onClick={() => setType(p.type)}
                    className={`flex cursor-pointer items-start gap-2 border px-3.5 py-3 text-left transition-colors ${type === p.type ? "border-text bg-card" : "border-border bg-panel hover:bg-card"}`}>
              <span className={`mt-0.5 grid size-4 place-items-center border ${type === p.type ? "border-text bg-text text-panel" : "border-border text-transparent"}`}><Check className="size-3" /></span>
              <div><div className="font-semibold text-sm">{p.label}</div>
              <small className="text-muted text-xs">{p.desc}</small>
              </div>
            </button>
          ))}
        </div>

        {field("onboarding-base-url", "接口地址", baseUrl, setBaseUrl, protocolOf(type)?.defaultBaseUrl || "https://...")}
        {field("onboarding-model", "模型名", model, setModel, protocolOf(type)?.placeholderModel || "模型名")}
        {field("onboarding-api-key", "API Key", apiKey, setApiKey, "sk-...", "password")}

        {msg && (
          <Alert variant={msg.ok ? "default" : "destructive"}
                 className={`mb-3.5 text-sm ${msg.ok ? "bg-success-soft text-success border-success/30" : "bg-danger-soft text-danger border-danger/30"}`}>{msg.text}</Alert>
        )}

        <div className="flex justify-end">
          <Button onClick={submit} disabled={busy || !baseUrl || !model || !apiKey}>
            {busy ? "测试连接中…" : "连接并启动"} <ArrowRight className="size-4" />
          </Button>
        </div>
      </div>
    </div>
  );
}
