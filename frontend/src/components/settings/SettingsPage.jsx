import { useEffect, useState } from "react";

/** 设置页：LLM 配置 / 向量化配置 / 偏好 */
export default function SettingsPage() {
  const [cfg, setCfg] = useState(null);
  const [msg, setMsg] = useState(null);
  const [llmForm, setLlmForm] = useState({ type: "openai_compat", base_url: "", model: "", api_key: "", temperature: 0.3 });
  const [embForm, setEmbForm] = useState({ provider: "jina", base_url: "https://api.jina.ai/v1", model: "jina-embeddings-v3", api_key: "" });

  async function load() {
    try {
      const r = await fetch("/api/v1/config");
      if (r.ok) {
        const d = await r.json();
        setCfg(d);
        if (d.llm) setLlmForm((f) => ({ ...f, type: d.llm.type, base_url: d.llm.base_url, model: d.llm.model, temperature: d.llm.temperature ?? 0.3 }));
        if (d.embeddings) setEmbForm((f) => ({ ...f, provider: d.embeddings.provider, base_url: d.embeddings.base_url, model: d.embeddings.model }));
      }
    } catch (e) {
      setMsg({ kind: "error", text: String(e) });
    }
  }
  useEffect(() => { load(); }, []);

  async function saveLlm() {
    setMsg(null);
    try {
      const r = await fetch("/api/v1/config", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(llmForm),
      });
      const d = await r.json();
      if (d.ok === false) setMsg({ kind: "error", text: "连接测试失败: " + JSON.stringify(d.test) });
      else setMsg({ kind: "ok", text: "✅ LLM 配置已保存并测试通过" });
      load();
    } catch (e) { setMsg({ kind: "error", text: String(e) }); }
  }

  async function saveEmb() {
    setMsg(null);
    try {
      const r = await fetch("/api/v1/config/embeddings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(embForm),
      });
      const d = await r.json();
      if (d.ok) setMsg({ kind: "ok", text: `✅ 向量化已保存 · 连接: ${d.test.ok ? `ok(${d.test.dimensions}维)` : d.test.error}` });
      else setMsg({ kind: "error", text: JSON.stringify(d) });
      load();
    } catch (e) { setMsg({ kind: "error", text: String(e) }); }
  }

  const field = (label, value, onChange, placeholder, type = "text", step = undefined) => (
    <label className="set-field">
      <span>{label}</span>
      <input type={type} value={value} placeholder={placeholder} step={step} onChange={(e) => onChange(e.target.value)} />
    </label>
  );

  return (
    <section className="settings-page">
      <h2>⚙️ 设置</h2>
      {msg && <div className={`set-msg ${msg.kind}`}>{msg.text}</div>}

      <div className="set-card">
        <h3>🧠 LLM 模型</h3>
        <p className="muted small">Agent 的大脑。支持 OpenAI 兼容 / OpenAI Responses / Anthropic 三协议。</p>
        {field("协议", llmForm.type, (v) => setLlmForm((f) => ({ ...f, type: v })), "openai_compat")}
        {field("接口地址", llmForm.base_url, (v) => setLlmForm((f) => ({ ...f, base_url: v })), "https://...")}
        {field("模型", llmForm.model, (v) => setLlmForm((f) => ({ ...f, model: v })), "如 deepseek-v4-flash")}
        {field("API Key", llmForm.api_key, (v) => setLlmForm((f) => ({ ...f, api_key: v })), cfg?.llm?.api_key_masked ? `当前: ${cfg.llm.api_key_masked}（留空不变）` : "sk-...", "password")}
        {field("温度", llmForm.temperature, (v) => setLlmForm((f) => ({ ...f, temperature: Number(v) })), "0.3", "number", "0.1")}
        <button className="btn" onClick={saveLlm}>保存并测试连接</button>
      </div>

      <div className="set-card">
        <h3>🧭 向量化（语义搜索）</h3>
        <p className="muted small">文件语义搜索的 embedding 服务（云 API，如 Jina AI）。</p>
        {field("Provider", embForm.provider, (v) => setEmbForm((f) => ({ ...f, provider: v })), "jina")}
        {field("接口地址", embForm.base_url, (v) => setEmbForm((f) => ({ ...f, base_url: v })), "https://api.jina.ai/v1")}
        {field("模型", embForm.model, (v) => setEmbForm((f) => ({ ...f, model: v })), "jina-embeddings-v3")}
        {field("API Key", embForm.api_key, (v) => setEmbForm((f) => ({ ...f, api_key: v })), cfg?.embeddings?.api_key_masked ? `当前: ${cfg.embeddings.api_key_masked}（留空不变）` : "jina_...", "password")}
        <button className="btn" onClick={saveEmb}>保存并测试</button>
      </div>

      <div className="set-card">
        <h3>🎛️ 偏好与规则</h3>
        {!cfg?.preferences || Object.keys(cfg.preferences).length === 0 ? (
          <p className="muted small">暂无偏好。在对话里说"以后用中文回复"即可添加。</p>
        ) : (
          <table className="fp-table">
            <thead><tr><th>偏好</th><th>值</th></tr></thead>
            <tbody>
              {Object.entries(cfg.preferences).map(([k, v]) => (
                <tr key={k}><td>{k}</td><td>{String(v)}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
