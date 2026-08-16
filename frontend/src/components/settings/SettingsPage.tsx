"use client";
import { useEffect, useState } from "react";
import { getConfig, saveEmbeddings, configureLLM, listModels } from "@/lib/api/config";
import { PROTOCOLS, protocolOf, EMBEDDING_PROVIDERS } from "@/lib/llm-options";
import ConnectAppCard from "./ConnectAppCard";
import DevicesCard from "./DevicesCard";
import PhotoSyncCard from "./PhotoSyncCard";
import { Capacitor } from "@capacitor/core";
import { apiErrorMessage, authenticatedFetch, setDeviceToken } from "@/lib/api/client";
import { ServerConfig } from "@/lib/native/server-config";
import { useAppStore } from "@/lib/store";
import { EV, emitTasksChanged, emitToast } from "@/lib/events";

export default function SettingsPage() {
  const [cfg, setCfg] = useState<Awaited<ReturnType<typeof getConfig>> | null>(null);
  const [msg, setMsg] = useState<{ kind: string; text: string } | null>(null);
  const [llmForm, setLlmForm] = useState({ type: "openai_compat", base_url: "", model: "", api_key: "" });
  const [embForm, setEmbForm] = useState({ provider: "jina", base_url: "https://api.jina.ai/v1", model: "jina-embeddings-v3", api_key: "" });
  const [saving, setSaving] = useState<"llm" | "emb" | null>(null);
  const [modelList, setModelList] = useState<string[] | null>(null);
  const [modelsLoading, setModelsLoading] = useState(false);
  const setAuthMode = useAppStore((s) => s.setAuthMode);
  const isNative = Capacitor.isNativePlatform();

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

  async function load() {
    try {
      const d = await getConfig();
      setCfg(d);
      if (d.llm) setLlmForm((f) => ({ ...f, type: d.llm!.type, base_url: d.llm!.base_url, model: d.llm!.model }));
      if (d.embeddings) setEmbForm((f) => ({ ...f, provider: d.embeddings!.provider, base_url: d.embeddings!.base_url, model: d.embeddings!.model }));
    } catch (e) {
      setMsg({ kind: "error", text: String(e) });
    }
  }
  useEffect(() => { load(); }, []);
  useEffect(() => {
    const h = () => load(); // 全局刷新
    window.addEventListener(EV.refresh, h);
    return () => window.removeEventListener(EV.refresh, h);
  }, []);

  async function saveLlm() {
    setMsg(null);
    if (!llmForm.base_url || !/^https?:\/\//i.test(llmForm.base_url)) {
      setMsg({ kind: "error", text: "接口地址需以 http(s):// 开头" });
      return;
    }
    if (!llmForm.model.trim()) {
      setMsg({ kind: "error", text: "请填写或选择模型" });
      return;
    }
    setSaving("llm");
    try {
      const r = await configureLLM({ ...llmForm }) as { ok?: boolean; error?: string; test?: { error?: string } };
      if (r.ok === false) {
        setMsg({ kind: "error", text: `保存失败：${r.error || r.test?.error || "连接测试失败"}` });
      } else setMsg({ kind: "ok", text: "✅ LLM 配置已保存并测试通过" });
      load();
    } catch (e) { setMsg({ kind: "error", text: String(e) }); }
    finally { setSaving(null); }
  }

  function changeProtocol(type: string) {
    const proto = protocolOf(type);
    setLlmForm((f) => {
      const prev = protocolOf(f.type);
      const baseIsDefault = !f.base_url || (prev ? f.base_url === prev.defaultBaseUrl : false);
      return { ...f, type, base_url: baseIsDefault && proto ? proto.defaultBaseUrl : f.base_url };
    });
    setModelList(null);
  }

  async function fetchModels() {
    setMsg(null);
    setModelsLoading(true);
    try {
      const r = await listModels({ type: llmForm.type, base_url: llmForm.base_url, api_key: llmForm.api_key });
      if (r.ok && r.models && r.models.length > 0) {
        setModelList(r.models);
        setMsg({ kind: "ok", text: `✅ 已获取 ${r.models.length} 个可用模型` });
      } else {
        setModelList(null);
        setMsg({ kind: "error", text: r.error || "获取模型列表失败" });
      }
    } catch (e) {
      setMsg({ kind: "error", text: String(e) });
    } finally {
      setModelsLoading(false);
    }
  }

  async function saveEmb() {
    setMsg(null);
    setSaving("emb");
    try {
      const d = await saveEmbeddings(embForm) as {
        ok?: boolean;
        test?: { ok: boolean; dimensions?: number; error?: string };
        rebuild_task?: { id: string } | null;
      };
      if (d.ok) {
        const suffix = d.rebuild_task ? " · 索引重建已进入后台" : "";
        setMsg({ kind: "ok", text: `✅ 向量化已保存 · 连接: ${d.test?.ok ? `ok(${d.test.dimensions}维)` : d.test?.error}${suffix}` });
        if (d.rebuild_task) emitTasksChanged();
      }
      else setMsg({ kind: "error", text: JSON.stringify(d) });
      load();
    } catch (e) { setMsg({ kind: "error", text: String(e) }); }
    finally { setSaving(null); }
  }

  const field = (label: string, value: string, onChange: (v: string) => void, placeholder: string, type = "text", step?: string) => (
    <label className="flex flex-col gap-1 mb-2.5 text-xs">
      <span className="text-muted">{label}</span>
      <input type={type} value={value} placeholder={placeholder} step={step}
             onChange={(e) => onChange(e.target.value)}
             className="px-2.5 py-2 border border-border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-accent-soft focus:border-accent" />
    </label>
  );

  return (
    <section className="flex-1 overflow-auto p-5 max-w-3xl mx-auto">
      <h2 className="text-lg font-bold mb-3">⚙️ 设置</h2>
      {msg && <div className={`px-3 py-2.5 rounded-lg mb-3 text-sm ${msg.kind === "ok" ? "bg-success-soft text-success border border-success/30" : "bg-danger-soft text-danger border border-danger/30"}`}>{msg.text}</div>}

      {!isNative && (<>
      <div className="bg-panel border border-border rounded-xl p-4 mb-4">
        <h3 className="font-bold text-sm mb-1">🧠 LLM 模型</h3>
        <p className="text-muted text-xs mb-3">Agent 的大脑。选择协议后可一键获取该服务商的可用模型。</p>
        <div className="grid grid-cols-3 gap-2 mb-3">
          {PROTOCOLS.map((p) => (
            <button key={p.type} onClick={() => changeProtocol(p.type)}
                    className={`text-left bg-card border rounded-lg px-2.5 py-2 cursor-pointer text-xs ${llmForm.type === p.type ? "border-accent bg-accent-soft" : "border-border"}`}>
              <div className="font-semibold">{p.label}</div>
              <small className="text-muted text-[10px]">{p.desc}</small>
            </button>
          ))}
        </div>
        <label className="flex flex-col gap-1 mb-2.5 text-xs">
          <span className="text-muted">接口地址</span>
          <input
            type="text"
            value={llmForm.base_url}
            placeholder={protocolOf(llmForm.type)?.defaultBaseUrl || "https://..."}
            list="base-url-presets"
            onChange={(e) => { setLlmForm((f) => ({ ...f, base_url: e.target.value })); setModelList(null); }}
            className="px-2.5 py-2 border border-border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-accent-soft focus:border-accent"
          />
          <datalist id="base-url-presets">
            {(protocolOf(llmForm.type)?.baseUrlPresets || []).map((u) => (
              <option key={u} value={u} />
            ))}
          </datalist>
        </label>
        <label className="flex flex-col gap-1.5 mb-2.5 text-xs">
          <span className="text-muted">模型</span>
          {modelList && modelList.length > 0 && (
            <select
              value={modelList.includes(llmForm.model) ? llmForm.model : ""}
              onChange={(e) => e.target.value && setLlmForm((f) => ({ ...f, model: e.target.value }))}
              className="px-2.5 py-2 border border-border rounded-lg text-sm bg-panel focus:outline-none focus:ring-2 focus:ring-accent-soft focus:border-accent cursor-pointer"
            >
              <option value="">— 从可用模型选择（共 {modelList.length} 个）—</option>
              {modelList.map((m) => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
          )}
          <input
            type="text"
            value={llmForm.model}
            placeholder={protocolOf(llmForm.type)?.placeholderModel || "模型名"}
            onChange={(e) => setLlmForm((f) => ({ ...f, model: e.target.value }))}
            className="px-2.5 py-2 border border-border rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-accent-soft focus:border-accent"
          />
          <div className="flex items-center gap-2 mt-0.5">
            <button
              className="border border-border text-muted px-2.5 py-1 rounded-md text-xs cursor-pointer disabled:opacity-60"
              onClick={fetchModels}
              disabled={modelsLoading || saving !== null}
            >
              {modelsLoading ? "获取中…" : "获取可用模型"}
            </button>
            <span className="text-muted text-[10px]">API Key 留空时沿用已保存的 Key</span>
          </div>
        </label>
        {field("API Key", llmForm.api_key, (v) => setLlmForm((f) => ({ ...f, api_key: v })), cfg?.llm?.api_key_masked ? `当前: ${cfg.llm.api_key_masked}（留空不变）` : "sk-...", "password")}
        <button className="bg-accent text-white px-4 py-2 rounded-lg text-sm font-semibold cursor-pointer disabled:opacity-60"
                onClick={saveLlm} disabled={saving !== null}>
          {saving === "llm" ? "测试中…" : "保存并测试连接"}
        </button>
      </div>

      <div className="bg-panel border border-border rounded-xl p-4 mb-4">
        <h3 className="font-bold text-sm mb-1">🧭 向量化（语义搜索）</h3>
        <p className="text-muted text-xs mb-3">文件语义搜索的 embedding 服务（云 API，如 Jina AI）。</p>
        <label className="flex flex-col gap-1 mb-2.5 text-xs">
          <span className="text-muted">Provider</span>
          <select value={embForm.provider} onChange={(e) => setEmbForm((f) => ({ ...f, provider: e.target.value }))}
                  className="px-2.5 py-2 border border-border rounded-lg text-sm bg-panel focus:outline-none focus:ring-2 focus:ring-accent-soft focus:border-accent">
            {EMBEDDING_PROVIDERS.map((p) => (
              <option key={p.value} value={p.value}>{p.label}</option>
            ))}
          </select>
        </label>
        {field("接口地址", embForm.base_url, (v) => setEmbForm((f) => ({ ...f, base_url: v })), EMBEDDING_PROVIDERS[0].defaultBaseUrl)}
        {field("模型", embForm.model, (v) => setEmbForm((f) => ({ ...f, model: v })), EMBEDDING_PROVIDERS[0].placeholderModel)}
        {field("API Key", embForm.api_key, (v) => setEmbForm((f) => ({ ...f, api_key: v })), cfg?.embeddings?.api_key_masked ? `当前: ${cfg.embeddings.api_key_masked}（留空不变）` : "jina_...", "password")}
        <button className="bg-accent text-white px-4 py-2 rounded-lg text-sm font-semibold cursor-pointer disabled:opacity-60"
                onClick={saveEmb} disabled={saving !== null}>
          {saving === "emb" ? "测试中…" : "保存并测试"}
        </button>
      </div>
      </>)}

      {isNative && (
        <div className="bg-panel border border-border rounded-xl p-4 mb-4">
          <h3 className="font-bold text-sm mb-1">🧠 AI 配置</h3>
          <p className="text-muted text-xs">AI 模型与向量化在网页端管理，App 内不提供设置入口。</p>
        </div>
      )}

      <ConnectAppCard />
      <DevicesCard />
      <PhotoSyncCard />

      <div className="bg-panel border border-border rounded-xl p-4">
        <h3 className="font-bold text-sm mb-1">🎛️ 偏好与规则</h3>
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
      </div>

      <div className="bg-panel border border-border rounded-xl p-4">
        <h3 className="font-bold text-sm mb-1">🔒 会话</h3>
        <button className="text-danger text-sm cursor-pointer" onClick={logout}>退出登录</button>
        <p className="text-muted text-[10px] mt-1">退出后需重新输入密码；App 内退出会同时清除本地设备令牌（相册同步停止，需重新登录）。</p>
      </div>
    </section>
  );
}
