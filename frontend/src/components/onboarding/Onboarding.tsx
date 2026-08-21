"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { configureLLM, listModels } from "@/lib/api/config";
import { useAppStore } from "@/lib/store";
import { PROTOCOLS, protocolOf } from "@/lib/llm-options";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Combobox, ComboboxInput, ComboboxContent, ComboboxList, ComboboxItem, ComboboxEmpty } from "@/components/ui/combobox";
import { Alert } from "@/components/ui/alert";
import { ArrowRight, Bot, Check, HardDrive, RefreshCw } from "lucide-react";

type FieldProps = Readonly<{
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  type?: string;
}>;

function Field({ label, value, onChange, placeholder, type = "text" }: FieldProps) {
  return (
    <label className="mb-4 block">
      <span className="mb-1.5 block text-xs text-muted">{label}</span>
      <Input
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        className="w-full text-sm"
      />
    </label>
  );
}

export default function Onboarding() {
  const setConfigured = useAppStore((s) => s.setConfigured);
  const [type, setType] = useState("openai_compat");
  const [baseUrl, setBaseUrl] = useState(protocolOf("openai_compat")?.defaultBaseUrl || "");
  const [model, setModel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [modelMsg, setModelMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [modelList, setModelList] = useState<string[] | null>(null);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelsOpen, setModelsOpen] = useState(false);
  const modelRequestRef = useRef(0);

  useEffect(() => () => {
    modelRequestRef.current += 1;
  }, []);

  const invalidateModelLookup = useCallback(() => {
    modelRequestRef.current += 1;
    setModelsLoading(false);
    setModelList(null);
    setModelsOpen(false);
  }, []);

  function changeProtocol(nextType: string) {
    const next = protocolOf(nextType);
    const previous = protocolOf(type);
    const baseIsDefault = !baseUrl || baseUrl === previous?.defaultBaseUrl;
    setType(nextType);
    if (baseIsDefault && next) setBaseUrl(next.defaultBaseUrl);
    invalidateModelLookup();
  }

  async function fetchModels() {
    const request = ++modelRequestRef.current;
    const form = { type, base_url: baseUrl, api_key: apiKey };
    setModelMsg(null);
    setModelsLoading(true);
    try {
      const result = await listModels(form);
      if (request !== modelRequestRef.current) return;
      if (result.ok && result.models?.length) {
        setModelList(result.models);
        setModelsOpen(true);
        setModelMsg({ ok: true, text: `已获取 ${result.models.length} 个可用模型` });
      } else {
        setModelList(null);
        setModelMsg({ ok: false, text: result.error || "获取模型列表失败" });
      }
    } catch (error) {
      if (request === modelRequestRef.current) {
        setModelMsg({ ok: false, text: String(error) });
      }
    } finally {
      if (request === modelRequestRef.current) setModelsLoading(false);
    }
  }

  const handleBaseUrlChange = useCallback((value: string) => {
    setBaseUrl(value);
    invalidateModelLookup();
  }, [invalidateModelLookup]);

  const handleApiKeyChange = useCallback((value: string) => {
    setApiKey(value);
    invalidateModelLookup();
  }, [invalidateModelLookup]);

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
            <button key={p.type} type="button" onClick={() => changeProtocol(p.type)}
                    className={`flex cursor-pointer items-start gap-2 border px-3.5 py-3 text-left transition-colors ${type === p.type ? "border-text bg-card" : "border-border bg-panel hover:bg-card"}`}>
              <span className={`mt-0.5 grid size-4 place-items-center border ${type === p.type ? "border-text bg-text text-panel" : "border-border text-transparent"}`}><Check className="size-3" /></span>
              <div><div className="font-semibold text-sm">{p.label}</div>
              <small className="text-muted text-xs">{p.desc}</small>
              </div>
            </button>
          ))}
        </div>

        <Field label="接口地址" value={baseUrl} onChange={handleBaseUrlChange} placeholder={protocolOf(type)?.defaultBaseUrl || "https://..."} />
        <div className="mb-4 flex flex-col gap-1.5">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs text-muted">模型名</span>
            <Combobox
              onValueChange={(value) => setModel(value == null ? "" : String(value))}
              inputValue={model}
              onInputValueChange={setModel}
              open={modelsOpen}
              onOpenChange={setModelsOpen}
              items={modelList ? modelList.map((item) => ({ value: item, label: item })) : []}
            >
              <ComboboxInput aria-label="模型名" className="w-full" placeholder={protocolOf(type)?.placeholderModel || "模型名"} showClear />
              <ComboboxContent>
                <ComboboxList>
                  {(item) => <ComboboxItem key={String(item.value)} value={String(item.value)}>{String(item.label)}</ComboboxItem>}
                </ComboboxList>
                <ComboboxEmpty>{modelsLoading ? "获取中…" : "暂无可用模型，请点击下方获取"}</ComboboxEmpty>
              </ComboboxContent>
            </Combobox>
          </label>
          <div className="mt-1.5 flex items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="shrink-0 whitespace-nowrap"
              aria-label="获取模型"
              title="从当前接口获取模型列表"
              onClick={fetchModels}
              disabled={modelsLoading || busy || !baseUrl || !apiKey}
            >
              <RefreshCw className={modelsLoading ? "animate-spin" : undefined} />
              {modelsLoading ? "获取中…" : "获取模型"}
            </Button>
            {modelMsg && <span className={`text-[10px] ${modelMsg.ok ? "text-muted" : "text-danger"}`}>{modelMsg.text}</span>}
          </div>
        </div>
        <Field label="API Key" value={apiKey} onChange={handleApiKeyChange} placeholder="sk-..." type="password" />

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
