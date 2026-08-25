"use client";
import { type ChangeEvent, useCallback, useEffect, useId, useRef, useState } from "react";
import {
  configureLLM,
  getConfig,
  getVisionConfig,
  listModels,
  listVisionModels,
  revealEmbeddingApiKey,
  revealLlmApiKey,
  revealVisionApiKey,
  saveEmbeddings,
  saveVision,
} from "@/lib/api/config";
import { PROTOCOLS, protocolOf, EMBEDDING_PROVIDERS } from "@/lib/llm-options";
import ConnectAppCard from "./ConnectAppCard";
import DevicesCard from "./DevicesCard";
import PhotoSyncCard from "./PhotoSyncCard";
import SkillsManager from "./SkillsManager";
import SystemStatusCenter from "./SystemStatusCenter";
import { Capacitor } from "@capacitor/core";
import { apiErrorMessage, authenticatedFetch, setDeviceToken } from "@/lib/api/client";
import { ServerConfig } from "@/lib/native/server-config";
import { useAppStore } from "@/lib/store";
import { EV, emitToast } from "@/lib/events";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { SecretInput } from "@/components/ui/secret-input";
import { Combobox, ComboboxInput, ComboboxContent, ComboboxList, ComboboxItem, ComboboxEmpty } from "@/components/ui/combobox";
import { Alert } from "@/components/ui/alert";
import { Bot, BrainCircuit, Eye, LogOut, RefreshCw, Settings2, SlidersHorizontal } from "lucide-react";
import { getIndexPolicy, setIndexPolicy, type IndexPolicy } from "@/lib/index-policy";

function sameBaseUrl(left: string, right: string) {
  return left.trim().replace(/\/+$/, "") === right.trim().replace(/\/+$/, "");
}

function SettingsField({ label, value, onChange, placeholder, type = "text", step, hint,
  storedSecretAvailable, revealStoredSecret }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  type?: string;
  step?: string;
  hint?: string;
  storedSecretAvailable?: boolean;
  revealStoredSecret?: () => Promise<boolean>;
}) {
  const inputId = useId();

  return (
    <div className="mb-3 flex flex-col gap-1.5">
      <label htmlFor={inputId} className="text-xs text-muted">{label}</label>
      {type === "password" ? (
        <SecretInput id={inputId} value={value} placeholder={placeholder}
                     onChange={(event) => onChange(event.target.value)} className="text-sm"
                     storedSecretAvailable={storedSecretAvailable}
                     revealStoredSecret={revealStoredSecret} />
      ) : (
        <Input id={inputId} type={type} value={value} placeholder={placeholder} step={step}
               onChange={(event) => onChange(event.target.value)} className="text-sm" />
      )}
      {hint && <span className="text-[10px] leading-snug text-muted">{hint}</span>}
    </div>
  );
}

interface SettingsPageProps {
  /** 从工作区导航进入设置时定位到对应 section。 */
  initialSection?: "models" | "security" | null;
}

export default function SettingsPage({ initialSection = null }: SettingsPageProps = {}) {
  const [cfg, setCfg] = useState<Awaited<ReturnType<typeof getConfig>> | null>(null);
  const [msg, setMsg] = useState<{ kind: string; text: string } | null>(null);
  const [llmMsg, setLlmMsg] = useState<{ kind: string; text: string } | null>(null);
  const [embMsg, setEmbMsg] = useState<{ kind: string; text: string } | null>(null);
  const [visionCfg, setVisionCfg] = useState<Awaited<ReturnType<typeof getVisionConfig>> | null>(null);
  const [visionMsg, setVisionMsg] = useState<{ kind: string; text: string } | null>(null);
  const [llmForm, setLlmForm] = useState({ type: "openai_compat", base_url: "", model: "", api_key: "" });
  const [embForm, setEmbForm] = useState({ provider: "jina", base_url: "https://api.jina.ai/v1", model: "jina-embeddings-v3", api_key: "" });
  const [visionForm, setVisionForm] = useState({ provider: "openai_compat", base_url: "https://api.openai.com/v1", model: "", api_key: "" });
  const [indexPolicy, setIndexPolicyState] = useState<IndexPolicy>("manual");
  const [saving, setSaving] = useState<"llm" | "emb" | "vision" | null>(null);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [settingsLoadError, setSettingsLoadError] = useState("");
  const [modelList, setModelList] = useState<string[] | null>(null);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelsOpen, setModelsOpen] = useState(false);
  const [visionModelList, setVisionModelList] = useState<string[] | null>(null);
  const [visionModelsLoading, setVisionModelsLoading] = useState(false);
  const [visionModelsOpen, setVisionModelsOpen] = useState(false);
  const setAuthMode = useAppStore((s) => s.setAuthMode);
  const isNative = Capacitor.isNativePlatform();
  const loadRequestRef = useRef(0);
  const modelRequestRef = useRef(0);
  const visionModelRequestRef = useRef(0);
  const llmKeyRevealRef = useRef(0);
  const embeddingKeyRevealRef = useRef(0);
  const visionKeyRevealRef = useRef(0);

  useEffect(() => {
    if (!initialSection) return;
    const target = document.getElementById(`settings-${initialSection}`);
    if (target && typeof target.scrollIntoView === "function") {
      const scroll = () => target.scrollIntoView({ behavior: "smooth", block: "start" });
      if (typeof window.requestAnimationFrame === "function") window.requestAnimationFrame(scroll);
      else window.setTimeout(scroll, 0);
    }
  }, [initialSection, cfg]);

  async function logout() {
    const native = Capacitor.isNativePlatform();
    let serverLogoutWarning: string | null = null;
    try {
      const res = await authenticatedFetch("/auth/logout", { method: "POST" });
      if (!res.ok && !(native && (res.status === 401 || res.status === 403))) {
        const body = await res.json().catch(() => ({}));
        const detail = apiErrorMessage(body, `HTTP ${res.status}`);
        if (!native) throw new Error(detail);
        serverLogoutWarning = `无法确认服务端是否已吊销旧令牌（${detail}）`;
      }
    } catch (error) {
      if (!native) {
        setMsg({ kind: "error", text: `服务端登出失败：${String(error)}` });
        return;
      }
      // 原生端离线时仍销毁本地凭据；服务端状态未知，不能宣称吊销成功或失败。
      serverLogoutWarning = `网络异常，无法确认服务端是否已吊销旧令牌（${String(error)}）`;
    }
    if (native) {
      try {
        await ServerConfig.clearDeviceToken();
      } catch (error) {
        setDeviceToken(null); // 当前进程立即失败关闭，但保留页面显示持久清理失败。
        setMsg({ kind: "error", text: `安全配置存储清理失败，请重试或清除 App 数据：${String(error)}` });
        return;
      }
    }
    setDeviceToken(null);
    setAuthMode(native ? "rescan" : "login");
    if (serverLogoutWarning) {
      emitToast({
        kind: "error",
        text: `本机凭据已清除；${serverLogoutWarning}。联网后可从设备列表确认并移除旧设备。`,
      });
    }
  }

  /**
   * 并行读取普通模型和视觉模型配置。请求代次同时覆盖全局刷新与保存后的重载，
   * 避免较早响应把用户刚选中的表单值或新配置状态覆盖掉。
   */
  const load = useCallback(async () => {
    const request = ++loadRequestRef.current;
    llmKeyRevealRef.current += 1;
    embeddingKeyRevealRef.current += 1;
    visionKeyRevealRef.current += 1;
    setSettingsLoading(true);
    setSettingsLoadError("");
    try {
      const [d, vision] = await Promise.all([getConfig(), getVisionConfig()]);
      if (request !== loadRequestRef.current) return;
      setCfg(d);
      setVisionCfg(vision);
      setLlmForm((current) => d.llm ? {
        type: d.llm.type,
        base_url: d.llm.base_url,
        model: d.llm.model,
        api_key: "",
      } : { ...current, api_key: "" });
      setEmbForm((current) => d.embeddings ? {
        provider: d.embeddings.provider,
        base_url: d.embeddings.base_url,
        model: d.embeddings.model,
        api_key: "",
      } : { ...current, api_key: "" });
      setVisionForm((current) => vision.configured ? {
        provider: vision.provider,
        base_url: vision.base_url,
        model: vision.model,
        api_key: "",
      } : { ...current, api_key: "" });
    } catch (e) {
      if (request === loadRequestRef.current) {
        setSettingsLoadError(e instanceof Error ? e.message : String(e));
        setMsg({ kind: "error", text: String(e) });
      }
    } finally {
      if (request === loadRequestRef.current) setSettingsLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { setIndexPolicyState(getIndexPolicy()); }, []);
  useEffect(() => () => {
    loadRequestRef.current += 1;
    modelRequestRef.current += 1;
    visionModelRequestRef.current += 1;
    llmKeyRevealRef.current += 1;
    embeddingKeyRevealRef.current += 1;
    visionKeyRevealRef.current += 1;
  }, []);
  useEffect(() => {
    const h = () => { void load(); }; // 全局刷新
    window.addEventListener(EV.refresh, h);
    return () => window.removeEventListener(EV.refresh, h);
  }, [load]);

  async function saveLlm() {
    setLlmMsg(null);
    if (!llmForm.base_url || !/^https?:\/\//i.test(llmForm.base_url)) {
      setLlmMsg({ kind: "error", text: "接口地址需以 http(s):// 开头" });
      return;
    }
    if (!llmForm.model.trim()) {
      setLlmMsg({ kind: "error", text: "请填写或选择模型" });
      return;
    }
    setSaving("llm");
    try {
      const r = await configureLLM({ ...llmForm }) as { ok?: boolean; error?: string; test?: { error?: string } };
      if (r.ok === false) {
        setLlmMsg({ kind: "error", text: `保存失败：${r.error || r.test?.error || "连接测试失败"}` });
      } else {
        setLlmForm((form) => ({ ...form, api_key: "" }));
        setLlmMsg({ kind: "ok", text: "LLM 配置已保存并测试通过" });
      }
      await load();
    } catch (e) { setLlmMsg({ kind: "error", text: String(e) }); }
    finally { setSaving(null); }
  }

  const invalidateModelLookup = useCallback(() => {
    modelRequestRef.current += 1;
    setModelsLoading(false);
    setModelList(null);
    setModelsOpen(false);
  }, []);

  const invalidateVisionModelLookup = useCallback(() => {
    visionModelRequestRef.current += 1;
    setVisionModelsLoading(false);
    setVisionModelList(null);
    setVisionModelsOpen(false);
  }, []);

  function changeProtocol(type: string) {
    llmKeyRevealRef.current += 1;
    const proto = protocolOf(type);
    setLlmForm((f) => {
      const prev = protocolOf(f.type);
      const baseIsDefault = !f.base_url || (prev ? f.base_url === prev.defaultBaseUrl : false);
      return {
        ...f,
        type,
        base_url: baseIsDefault && proto ? proto.defaultBaseUrl : f.base_url,
        api_key: type === f.type ? f.api_key : "",
      };
    });
    invalidateModelLookup();
  }

  async function fetchModels() {
    const request = ++modelRequestRef.current;
    const form = { ...llmForm };
    setLlmMsg(null);
    setModelsLoading(true);
    try {
      const r = await listModels({ type: form.type, base_url: form.base_url, api_key: form.api_key });
      if (request !== modelRequestRef.current) return;
      if (r.ok && r.models && r.models.length > 0) {
        setModelList(r.models);
        setModelsOpen(true);
        setLlmMsg({ kind: "ok", text: `已获取 ${r.models.length} 个可用模型` });
      } else {
        setModelList(null);
        setLlmMsg({ kind: "error", text: r.error || "获取模型列表失败" });
      }
    } catch (e) {
      if (request === modelRequestRef.current) setLlmMsg({ kind: "error", text: String(e) });
    } finally {
      if (request === modelRequestRef.current) setModelsLoading(false);
    }
  }

  async function saveEmb() {
    setEmbMsg(null);
    setSaving("emb");
    try {
      const d = await saveEmbeddings(embForm) as {
        ok?: boolean;
        test?: { ok: boolean; dimensions?: number; error?: string };
      };
      if (d.ok) {
        setEmbForm((form) => ({ ...form, api_key: "" }));
        setEmbMsg({ kind: "ok", text: `向量化已保存 · 连接: ${d.test?.ok ? `ok(${d.test.dimensions}维)` : d.test?.error}` });
      }
      else setEmbMsg({ kind: "error", text: JSON.stringify(d) });
      await load();
    } catch (e) { setEmbMsg({ kind: "error", text: String(e) }); }
    finally { setSaving(null); }
  }

  /**
   * 保存视觉模型配置并执行 1x1 图片连接测试。
   */
  async function saveVisionConfig() {
    setVisionMsg(null);
    if (!visionForm.base_url || !/^https?:\/\//i.test(visionForm.base_url)) {
      setVisionMsg({ kind: "error", text: "接口地址需以 http(s):// 开头" });
      return;
    }
    if (!visionForm.model.trim()) {
      setVisionMsg({ kind: "error", text: "请填写视觉模型名" });
      return;
    }
    setSaving("vision");
    try {
      const result = await saveVision(visionForm) as { ok?: boolean; test?: { error?: string }; message?: string };
      if (result.ok) setVisionForm((form) => ({ ...form, api_key: "" }));
      setVisionMsg(result.ok
        ? { kind: "ok", text: "视觉模型已保存并测试通过" }
        : { kind: "error", text: result.message || result.test?.error || "视觉模型连接测试失败" });
      await load();
    } catch (error) {
      setVisionMsg({ kind: "error", text: String(error) });
    } finally {
      setSaving(null);
    }
  }

  /**
   * 获取视觉 provider 的模型目录，并把结果放进图片识别模型选择器。
   */
  async function fetchVisionModels() {
    const request = ++visionModelRequestRef.current;
    const form = { ...visionForm };
    setVisionMsg(null);
    setVisionModelsLoading(true);
    try {
      const result = await listVisionModels({
        provider: form.provider,
        base_url: form.base_url,
        api_key: form.api_key,
      });
      if (request !== visionModelRequestRef.current) return;
      if (result.ok && result.models && result.models.length > 0) {
        setVisionModelList(result.models);
        setVisionModelsOpen(true);
        setVisionMsg({ kind: "ok", text: `已获取 ${result.models.length} 个可用视觉模型` });
      } else {
        setVisionModelList(null);
        setVisionMsg({ kind: "error", text: result.error || "获取视觉模型列表失败" });
      }
    } catch (error) {
      if (request === visionModelRequestRef.current) setVisionMsg({ kind: "error", text: String(error) });
    } finally {
      if (request === visionModelRequestRef.current) setVisionModelsLoading(false);
    }
  }

  const handleLlmBaseUrlChange = useCallback((event: ChangeEvent<HTMLInputElement>) => {
    llmKeyRevealRef.current += 1;
    const baseUrl = event.target.value;
    setLlmForm((f) => ({
      ...f,
      base_url: baseUrl,
      api_key: baseUrl === f.base_url ? f.api_key : "",
    }));
    invalidateModelLookup();
  }, [invalidateModelLookup]);

  const handleLlmApiKeyChange = useCallback((value: string) => {
    llmKeyRevealRef.current += 1;
    setLlmForm((f) => ({ ...f, api_key: value }));
    invalidateModelLookup();
  }, [invalidateModelLookup]);

  const handleVisionBaseUrlChange = useCallback((value: string) => {
    visionKeyRevealRef.current += 1;
    setVisionForm((f) => ({
      ...f,
      base_url: value,
      api_key: value === f.base_url ? f.api_key : "",
    }));
    invalidateVisionModelLookup();
  }, [invalidateVisionModelLookup]);

  const handleVisionApiKeyChange = useCallback((value: string) => {
    visionKeyRevealRef.current += 1;
    setVisionForm((f) => ({ ...f, api_key: value }));
    invalidateVisionModelLookup();
  }, [invalidateVisionModelLookup]);

  const handleEmbeddingBaseUrlChange = useCallback((value: string) => {
    embeddingKeyRevealRef.current += 1;
    setEmbForm((f) => ({
      ...f,
      base_url: value,
      api_key: value === f.base_url ? f.api_key : "",
    }));
  }, []);

  const handleEmbeddingModelChange = useCallback((value: string) => {
    embeddingKeyRevealRef.current += 1;
    setEmbForm((f) => ({
      ...f,
      model: value,
      api_key: value === f.model ? f.api_key : "",
    }));
  }, []);

  const handleEmbeddingApiKeyChange = useCallback((value: string) => {
    embeddingKeyRevealRef.current += 1;
    setEmbForm((form) => ({ ...form, api_key: value }));
  }, []);

  const revealSavedLlmApiKey = useCallback(async () => {
    const request = ++llmKeyRevealRef.current;
    setLlmMsg(null);
    try {
      const apiKey = await revealLlmApiKey();
      if (request !== llmKeyRevealRef.current) return false;
      setLlmForm((form) => ({ ...form, api_key: apiKey }));
      return true;
    } catch (error) {
      if (request === llmKeyRevealRef.current) {
        setLlmMsg({ kind: "error", text: `读取已保存的 API Key 失败：${String(error)}` });
      }
      return false;
    }
  }, []);

  const revealSavedEmbeddingApiKey = useCallback(async () => {
    const request = ++embeddingKeyRevealRef.current;
    setEmbMsg(null);
    try {
      const apiKey = await revealEmbeddingApiKey();
      if (request !== embeddingKeyRevealRef.current) return false;
      setEmbForm((form) => ({ ...form, api_key: apiKey }));
      return true;
    } catch (error) {
      if (request === embeddingKeyRevealRef.current) {
        setEmbMsg({ kind: "error", text: `读取已保存的 API Key 失败：${String(error)}` });
      }
      return false;
    }
  }, []);

  const revealSavedVisionApiKey = useCallback(async () => {
    const request = ++visionKeyRevealRef.current;
    setVisionMsg(null);
    try {
      const apiKey = await revealVisionApiKey();
      if (request !== visionKeyRevealRef.current) return false;
      setVisionForm((form) => ({ ...form, api_key: apiKey }));
      return true;
    } catch (error) {
      if (request === visionKeyRevealRef.current) {
        setVisionMsg({ kind: "error", text: `读取已保存的 API Key 失败：${String(error)}` });
      }
      return false;
    }
  }, []);

  const llmStoredSecretAvailable = Boolean(cfg?.llm?.api_key_masked
    && cfg.llm.type === llmForm.type
    && sameBaseUrl(cfg.llm.base_url, llmForm.base_url));
  const embeddingStoredSecretAvailable = Boolean(cfg?.embeddings?.api_key_masked
    && cfg.embeddings.provider === embForm.provider
    && cfg.embeddings.model === embForm.model
    && sameBaseUrl(cfg.embeddings.base_url, embForm.base_url));
  const visionStoredSecretAvailable = Boolean(visionCfg?.configured && visionCfg.api_key_masked
    && visionCfg.provider === visionForm.provider
    && sameBaseUrl(visionCfg.base_url, visionForm.base_url));

  return (
    <section className="flex-1 overflow-auto bg-bg px-4 py-5 sm:px-6">
      <div className="mx-auto max-w-4xl">
      <div className="mb-5 flex items-end justify-between gap-3 border-b border-border pb-4">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold"><Settings2 className="size-5 text-muted" /> 设置</h2>
          <p className="mt-1 text-xs text-muted">模型、索引、设备与本地同步的运行配置。</p>
        </div>
        <span className="hidden font-mono text-[10px] uppercase tracking-[0.12em] text-muted sm:block">workspace config</span>
      </div>
      {settingsLoading && !cfg && <div className="mb-3 text-xs text-muted" role="status">正在读取设置…</div>}
      {settingsLoadError && !cfg && (
        <Alert variant="destructive" className="mb-3 text-xs bg-danger-soft text-danger border-danger/30" role="alert">
          <div className="flex items-center justify-between gap-3">
            <span>设置加载失败：{settingsLoadError}</span>
            <Button type="button" variant="outline" size="sm" className="h-8 shrink-0" onClick={() => void load()}>重试</Button>
          </div>
        </Alert>
      )}
      {msg && msg.kind === "error" && (
        <Alert variant="destructive" className="mb-3 text-xs bg-danger-soft text-danger border-danger/30">{msg.text}</Alert>
      )}

      {!isNative && (<>
      <section id="settings-models" className="border-b border-border py-5 first:pt-0">
        <h3 className="flex items-center gap-2 text-sm font-bold"><Bot className="size-4 text-muted" /> LLM 模型</h3>
        <p className="text-muted text-xs mb-3">Agent 的大脑。选择协议后可一键获取该服务商的可用模型。</p>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2 mb-3">
          {PROTOCOLS.map((p) => (
            <button key={p.type} type="button" onClick={() => changeProtocol(p.type)}
                    className={`cursor-pointer border px-2.5 py-2 text-left text-xs transition-colors ${llmForm.type === p.type ? "border-text bg-card" : "border-border bg-panel hover:bg-card"}`}>
              <div className="font-semibold">{p.label}</div>
              <small className="text-muted text-[10px]">{p.desc}</small>
            </button>
          ))}
        </div>
        <label className="flex flex-col gap-1.5 mb-3">
          <span className="text-xs text-muted">接口地址</span>
          <Input
            type="text"
            value={llmForm.base_url}
            placeholder={protocolOf(llmForm.type)?.defaultBaseUrl || "https://..."}
            list="base-url-presets"
            onChange={handleLlmBaseUrlChange}
            className="text-sm"
          />
          <datalist id="base-url-presets">
            {(protocolOf(llmForm.type)?.baseUrlPresets || []).map((u) => (
              <option key={u} value={u} />
            ))}
          </datalist>
        </label>
        <div className="flex flex-col gap-1.5 mb-3">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs text-muted">模型</span>
            <Combobox
            onValueChange={(v) => setLlmForm((f) => ({ ...f, model: v == null ? "" : String(v) }))}
            inputValue={llmForm.model}
            onInputValueChange={(v) => setLlmForm((f) => ({ ...f, model: v }))}
            open={modelsOpen}
            onOpenChange={setModelsOpen}
            items={modelList ? modelList.map((m) => ({ value: m, label: m })) : []}
          >
            <ComboboxInput
              className="w-full"
              placeholder="选择或输入模型名"
              showClear
            />
            <ComboboxContent>
              <ComboboxList>
                {(item) => <ComboboxItem value={String(item.value)}>{String(item.label)}</ComboboxItem>}
              </ComboboxList>
              <ComboboxEmpty>暂无可用模型，点击右侧图标获取</ComboboxEmpty>
            </ComboboxContent>
            </Combobox>
          </label>
          <div className="flex items-center gap-2 mt-1.5">
            <Button
              variant="outline"
              size="sm"
              className="shrink-0 whitespace-nowrap"
              aria-label="获取模型"
              title="从当前接口获取模型列表"
              onClick={fetchModels}
              disabled={modelsLoading || saving !== null}
            >
              <RefreshCw className={modelsLoading ? "animate-spin" : undefined} />
              {modelsLoading ? "获取中…" : "获取模型"}
            </Button>
            {llmMsg?.kind === "ok" && llmMsg.text.startsWith("已获取") && (
              <span className="text-[10px] text-muted">{llmMsg.text}</span>
            )}
          </div>
        </div>
        <SettingsField label="API Key" value={llmForm.api_key} onChange={handleLlmApiKeyChange}
                       placeholder={cfg?.llm?.api_key_masked ? `当前: ${cfg.llm.api_key_masked}（留空不变）` : "sk-..."}
                       type="password" hint="API Key 留空时沿用已保存的 Key"
                       storedSecretAvailable={llmStoredSecretAvailable}
                       revealStoredSecret={revealSavedLlmApiKey} />
        {llmMsg && !(llmMsg.kind === "ok" && llmMsg.text.startsWith("已获取")) && (
          <Alert variant={llmMsg.kind === "ok" ? "default" : "destructive"}
                 className={`mb-3 text-xs ${llmMsg.kind === "ok" ? "bg-success-soft text-success border-success/30" : "bg-danger-soft text-danger border-danger/30"}`}>{llmMsg.text}</Alert>
        )}
        <Button onClick={saveLlm} disabled={saving !== null}>{saving === "llm" ? "测试中…" : "保存并测试连接"}</Button>
      </section>

      <section className="border-b border-border py-5">
        <h3 className="flex items-center gap-2 text-sm font-bold"><BrainCircuit className="size-4 text-muted" /> 向量化（语义搜索）</h3>
        <p className="text-muted text-xs mb-3">文件语义搜索的 embedding 服务（云 API，如 Jina AI）。</p>
        <div className="flex flex-col gap-1.5 mb-3">
          <span className="text-xs text-muted">Provider</span>
          <p className="text-sm">{EMBEDDING_PROVIDERS[0]?.label || "Jina AI"}（当前唯一支持）</p>
        </div>
        <SettingsField label="接口地址" value={embForm.base_url}
                       onChange={handleEmbeddingBaseUrlChange}
                       placeholder={EMBEDDING_PROVIDERS[0].defaultBaseUrl} />
        <SettingsField label="模型" value={embForm.model}
                       onChange={handleEmbeddingModelChange}
                       placeholder={EMBEDDING_PROVIDERS[0].placeholderModel} />
        <SettingsField label="API Key" value={embForm.api_key}
                       onChange={handleEmbeddingApiKeyChange}
                       placeholder={cfg?.embeddings?.api_key_masked ? `当前: ${cfg.embeddings.api_key_masked}（留空不变）` : "jina_..."}
                       type="password" storedSecretAvailable={embeddingStoredSecretAvailable}
                       revealStoredSecret={revealSavedEmbeddingApiKey} />
        {embMsg && (
          <Alert variant={embMsg.kind === "ok" ? "default" : "destructive"}
                 className={`mb-3 text-xs ${embMsg.kind === "ok" ? "bg-success-soft text-success border-success/30" : "bg-danger-soft text-danger border-danger/30"}`}>{embMsg.text}</Alert>
        )}
        <Button onClick={saveEmb} disabled={saving !== null}>{saving === "emb" ? "测试中…" : "保存并测试"}</Button>
      </section>

      <section className="border-b border-border py-5">
        <h3 className="flex items-center gap-2 text-sm font-bold"><Eye className="size-4 text-muted" /> 视觉模型（图片识别）</h3>
        <p className="text-muted text-xs mb-3">为图片生成一段综合描述，再进入文件语义索引。使用 OpenAI 兼容的多模态 Chat Completions 接口。</p>
        <div className="flex flex-col gap-1.5 mb-3">
          <span className="text-xs text-muted">协议</span>
          <p className="text-sm">OpenAI 兼容（当前唯一支持）</p>
        </div>
        <SettingsField label="接口地址" value={visionForm.base_url} onChange={handleVisionBaseUrlChange}
                       placeholder="https://api.openai.com/v1" />
        <div className="flex flex-col gap-1.5 mb-3">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs text-muted">视觉模型</span>
            <Combobox
              onValueChange={(v) => setVisionForm((f) => ({ ...f, model: v == null ? "" : String(v) }))}
              inputValue={visionForm.model}
              onInputValueChange={(v) => setVisionForm((f) => ({ ...f, model: v }))}
              open={visionModelsOpen}
              onOpenChange={setVisionModelsOpen}
              items={visionModelList ? visionModelList.map((model) => ({ value: model, label: model })) : []}
            >
              <ComboboxInput className="w-full" placeholder="选择或输入视觉模型名" showClear />
              <ComboboxContent>
                <ComboboxList>
                  {(item) => <ComboboxItem value={String(item.value)}>{String(item.label)}</ComboboxItem>}
                </ComboboxList>
                <ComboboxEmpty>暂无可用视觉模型，请点击下方获取</ComboboxEmpty>
              </ComboboxContent>
            </Combobox>
          </label>
          <div className="flex items-center gap-2 mt-1.5">
            <Button
              variant="outline"
              size="sm"
              className="shrink-0 whitespace-nowrap"
              aria-label="获取视觉模型"
              title="从当前视觉接口获取模型列表"
              onClick={fetchVisionModels}
              disabled={visionModelsLoading || saving !== null}
            >
              <RefreshCw className={visionModelsLoading ? "animate-spin" : undefined} />
              {visionModelsLoading ? "获取中…" : "获取模型"}
            </Button>
            {visionMsg?.kind === "ok" && visionMsg.text.startsWith("已获取") && (
              <span className="text-[10px] text-muted">{visionMsg.text}</span>
            )}
          </div>
        </div>
        <SettingsField label="API Key" value={visionForm.api_key} onChange={handleVisionApiKeyChange}
                       placeholder={visionCfg?.api_key_masked ? `当前: ${visionCfg.api_key_masked}（留空不变）` : "API Key"}
                       type="password" storedSecretAvailable={visionStoredSecretAvailable}
                       revealStoredSecret={revealSavedVisionApiKey} />
        {visionMsg && (
          !(visionMsg.kind === "ok" && visionMsg.text.startsWith("已获取")) &&
          <Alert variant={visionMsg.kind === "ok" ? "default" : "destructive"}
                 className={`mb-3 text-xs ${visionMsg.kind === "ok" ? "bg-success-soft text-success border-success/30" : "bg-danger-soft text-danger border-danger/30"}`}>{visionMsg.text}</Alert>
        )}
        <Button onClick={saveVisionConfig} disabled={saving !== null}>{saving === "vision" ? "测试中…" : "保存并测试"}</Button>
      </section>
      <section id="settings-ingestion" className="border-b border-border py-5">
        <h3 className="flex items-center gap-2 text-sm font-bold"><RefreshCw className="size-4 text-muted" /> 智能摄入</h3>
        <p className="mb-3 text-xs text-muted">上传完成后决定是否自动抽取正文、理解图片并生成语义向量。</p>
        <div className="flex flex-col gap-1.5 sm:max-w-md">
          <label className="text-xs text-muted" htmlFor="index-policy">上传后的索引策略</label>
          <Select value={indexPolicy} onValueChange={(value) => {
            if (value === "manual" || value === "auto" || value === "images") {
              setIndexPolicyState(value);
              setIndexPolicy(value);
            }
          }}>
            <SelectTrigger id="index-policy" aria-label="上传后的索引策略"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="manual">手动索引</SelectItem>
              <SelectItem value="images">仅自动理解新图片</SelectItem>
              <SelectItem value="auto">自动理解全部新文件</SelectItem>
            </SelectContent>
          </Select>
          <span className="text-[10px] leading-snug text-muted">
            {indexPolicy === "manual" ? "上传后不调用模型，费用和时机完全由你控制。" : indexPolicy === "images" ? "只对新图片生成视觉描述和向量，文本文件仍需手动索引。" : "新文件上传完成后自动抽取并向量化，失败会保留在操作活动中心。"}
          </span>
        </div>
      </section>
      </>)}

      {isNative && (
        <section className="border-b border-border py-5">
          <h3 className="flex items-center gap-2 text-sm font-bold"><Bot className="size-4 text-muted" /> AI 配置</h3>
          <p className="text-muted text-xs">AI 模型与向量化在网页端管理，App 内不提供设置入口。</p>
        </section>
      )}

      <div id="settings-status" data-settings-section="status" className="mb-4 overflow-hidden border border-border">
        <SystemStatusCenter
          onOpenSettings={() => document.getElementById("settings-model")?.scrollIntoView({ behavior: "smooth", block: "start" })}
          onOpenSync={() => document.getElementById("settings-sync")?.scrollIntoView({ behavior: "smooth", block: "start" })}
          onOpenBackup={() => document.getElementById("settings-status")?.scrollIntoView({ behavior: "smooth", block: "start" })}
          onOpenDevices={() => document.getElementById("settings-security")?.scrollIntoView({ behavior: "smooth", block: "start" })}
        />
      </div>

      <SkillsManager />
      <ConnectAppCard />
      <div id="settings-security" data-settings-section="security"><DevicesCard /></div>
      <div id="settings-sync"><PhotoSyncCard /></div>

      <section className="border-b border-border py-5">
        <h3 className="flex items-center gap-2 text-sm font-bold"><SlidersHorizontal className="size-4 text-muted" /> 偏好与规则</h3>
        {!cfg?.preferences || Object.keys(cfg.preferences).length === 0 ? (
          <p className="text-muted text-xs">暂无偏好。在对话里说“以后用中文回复”即可添加。</p>
        ) : (
          <table className="w-full text-xs border-collapse">
            <thead><tr><th className="text-left p-1.5 border-b border-border">偏好</th><th className="text-left p-1.5 border-b border-border">值</th></tr></thead>
            <tbody>
              {Object.entries(cfg.preferences).map(([k, v]) => (
                <tr key={k}><td className="p-1.5 border-b border-border/50">{k}</td><td className="p-1.5 border-b border-border/50">{String(v)}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="py-5">
        <h3 className="flex items-center gap-2 text-sm font-bold"><LogOut className="size-4 text-muted" /> 会话</h3>
        <button className="mt-2 inline-flex min-h-9 items-center gap-2 border border-danger/30 px-3 text-sm text-danger transition-colors hover:bg-danger-soft" onClick={logout}><LogOut className="size-3.5" /> 退出登录</button>
        <p className="text-muted text-[10px] mt-1">退出后需重新输入密码；App 内退出会同时清除本地设备令牌（相册同步停止，需重新登录）。</p>
      </section>
      </div>
    </section>
  );
}
