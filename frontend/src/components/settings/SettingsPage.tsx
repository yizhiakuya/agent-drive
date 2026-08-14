"use client";
import { useEffect, useState } from "react";
import { getConfig, saveEmbeddings, configureLLM, testLLM } from "@/lib/api/config";
import ConnectAppCard from "./ConnectAppCard";
import DevicesCard from "./DevicesCard";
import PhotoSyncCard from "./PhotoSyncCard";
import { Capacitor } from "@capacitor/core";
import { V1, authHeaders, setDeviceToken } from "@/lib/api/client";
import { ServerConfig } from "@/lib/native/server-config";
import { useAppStore } from "@/lib/store";

export default function SettingsPage() {
  const [cfg, setCfg] = useState<Awaited<ReturnType<typeof getConfig>> | null>(null);
  const [msg, setMsg] = useState<{ kind: string; text: string } | null>(null);
  const [llmForm, setLlmForm] = useState({ type: "openai_compat", base_url: "", model: "", api_key: "", temperature: "0.3" });
  const [embForm, setEmbForm] = useState({ provider: "jina", base_url: "https://api.jina.ai/v1", model: "jina-embeddings-v3", api_key: "" });
  const [saving, setSaving] = useState<"llm" | "emb" | null>(null);
  const setAuthMode = useAppStore((s) => s.setAuthMode);

  async function logout() {
    try {
      await fetch(`${V1}/auth/logout`, { method: "POST", credentials: "include", headers: authHeaders() });
      if (Capacitor.isNativePlatform()) {
        await ServerConfig.clearDeviceToken(); // 原生：同时吊销本地设备令牌
      }
      setDeviceToken(null);
    } catch { /* 忽略 */ }
    setAuthMode(Capacitor.isNativePlatform() ? "rescan" : "login");
  }

  async function load() {
    try {
      const d = await getConfig();
      setCfg(d);
      if (d.llm) setLlmForm((f) => ({ ...f, type: d.llm!.type, base_url: d.llm!.base_url, model: d.llm!.model, temperature: String(d.llm!.temperature ?? 0.3) }));
      if (d.embeddings) setEmbForm((f) => ({ ...f, provider: d.embeddings!.provider, base_url: d.embeddings!.base_url, model: d.embeddings!.model }));
    } catch (e) {
      setMsg({ kind: "error", text: String(e) });
    }
  }
  useEffect(() => { load(); }, []);

  async function saveLlm() {
    setMsg(null);
    setSaving("llm");
    try {
      const r = await configureLLM({ ...llmForm, temperature: Number(llmForm.temperature) }) as { ok?: boolean; test?: unknown };
      if (r.ok === false) setMsg({ kind: "error", text: "连接测试失败: " + JSON.stringify(r.test) });
      else setMsg({ kind: "ok", text: "✅ LLM 配置已保存并测试通过" });
      load();
    } catch (e) { setMsg({ kind: "error", text: String(e) }); }
    finally { setSaving(null); }
  }

  async function saveEmb() {
    setMsg(null);
    setSaving("emb");
    try {
      const d = await saveEmbeddings(embForm) as { ok?: boolean; test?: { ok: boolean; dimensions?: number; error?: string } };
      if (d.ok) setMsg({ kind: "ok", text: `✅ 向量化已保存 · 连接: ${d.test?.ok ? `ok(${d.test.dimensions}维)` : d.test?.error}` });
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

      <div className="bg-panel border border-border rounded-xl p-4 mb-4">
        <h3 className="font-bold text-sm mb-1">🧠 LLM 模型</h3>
        <p className="text-muted text-xs mb-3">Agent 的大脑。支持 OpenAI 兼容 / OpenAI Responses / Anthropic 三协议。</p>
        {field("协议", llmForm.type, (v) => setLlmForm((f) => ({ ...f, type: v })), "openai_compat")}
        {field("接口地址", llmForm.base_url, (v) => setLlmForm((f) => ({ ...f, base_url: v })), "https://...")}
        {field("模型", llmForm.model, (v) => setLlmForm((f) => ({ ...f, model: v })), "如 deepseek-v4-flash")}
        {field("API Key", llmForm.api_key, (v) => setLlmForm((f) => ({ ...f, api_key: v })), cfg?.llm?.api_key_masked ? `当前: ${cfg.llm.api_key_masked}（留空不变）` : "sk-...", "password")}
        {field("温度", llmForm.temperature, (v) => setLlmForm((f) => ({ ...f, temperature: v })), "0.3", "number", "0.1")}
        <button className="bg-accent text-white px-4 py-2 rounded-lg text-sm font-semibold cursor-pointer disabled:opacity-60"
                onClick={saveLlm} disabled={saving !== null}>
          {saving === "llm" ? "测试中…" : "保存并测试连接"}
        </button>
      </div>

      <div className="bg-panel border border-border rounded-xl p-4 mb-4">
        <h3 className="font-bold text-sm mb-1">🧭 向量化（语义搜索）</h3>
        <p className="text-muted text-xs mb-3">文件语义搜索的 embedding 服务（云 API，如 Jina AI）。</p>
        {field("Provider", embForm.provider, (v) => setEmbForm((f) => ({ ...f, provider: v })), "jina")}
        {field("接口地址", embForm.base_url, (v) => setEmbForm((f) => ({ ...f, base_url: v })), "https://api.jina.ai/v1")}
        {field("模型", embForm.model, (v) => setEmbForm((f) => ({ ...f, model: v })), "jina-embeddings-v3")}
        {field("API Key", embForm.api_key, (v) => setEmbForm((f) => ({ ...f, api_key: v })), cfg?.embeddings?.api_key_masked ? `当前: ${cfg.embeddings.api_key_masked}（留空不变）` : "jina_...", "password")}
        <button className="bg-accent text-white px-4 py-2 rounded-lg text-sm font-semibold cursor-pointer disabled:opacity-60"
                onClick={saveEmb} disabled={saving !== null}>
          {saving === "emb" ? "测试中…" : "保存并测试"}
        </button>
      </div>

      <ConnectAppCard />
      <DevicesCard />
      <PhotoSyncCard />

      <div className="bg-panel border border-border rounded-xl p-4">
        <h3 className="font-bold text-sm mb-1">🎛️ 偏好与规则</h3>
        {!cfg?.preferences || Object.keys(cfg.preferences).length === 0 ? (
          <p className="text-muted text-xs">暂无偏好。在对话里说"以后用中文回复"即可添加。</p>
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
