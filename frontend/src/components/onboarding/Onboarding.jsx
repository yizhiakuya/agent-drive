import { useState } from "react";
import { configureLLM, testLLM } from "../../api/config.js";

const PROVIDERS = [
  {
    value: "openai_compat",
    label: "OpenAI 兼容 (chat/completions)",
    desc: "DeepSeek / Ollama / vLLM / Groq / LM Studio…",
    placeholder: "https://api.deepseek.com/v1",
    model: "deepseek-chat",
  },
  {
    value: "openai_responses",
    label: "OpenAI Responses",
    desc: "OpenAI 官方新协议",
    placeholder: "https://api.openai.com/v1",
    model: "gpt-4o",
  },
  {
    value: "anthropic",
    label: "Anthropic (Claude)",
    desc: "Claude 及兼容服务",
    placeholder: "https://api.anthropic.com",
    model: "claude-sonnet-4-5",
  },
];

export default function Onboarding({ onDone }) {
  const [type, setType] = useState("openai_compat");
  const [baseUrl, setBaseUrl] = useState("https://api.deepseek.com/v1");
  const [apiKey, setApiKey] = useState("");
  const [model, setModel] = useState("deepseek-chat");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);
  const [diag, setDiag] = useState(null);

  const current = PROVIDERS.find((p) => p.value === type);

  function switchType(v) {
    setType(v);
    const p = PROVIDERS.find((x) => x.value === v);
    setBaseUrl(p.placeholder);
    setModel(p.model);
  }

  async function handleTest() {
    setBusy(true); setMsg(null); setDiag(null);
    try {
      const r = await testLLM({ type, base_url: baseUrl, api_key: apiKey, model });
      setDiag(r);
      setMsg(r.ok ? { kind: "ok", text: `✅ 连接成功！模型回复: ${r.reply}` } : { kind: "err", text: `❌ 连接失败: ${r.error}` });
    } catch (e) {
      setMsg({ kind: "err", text: `❌ ${e.message}` });
    } finally { setBusy(false); }
  }

  async function handleSave() {
    setBusy(true); setMsg(null);
    try {
      const r = await configureLLM({ type, base_url: baseUrl, api_key: apiKey, model });
      if (r.ok) onDone();
      else setMsg({ kind: "err", text: `❌ ${r.message}${r.test?.error ? "：" + r.test.error : ""}` });
    } catch (e) {
      setMsg({ kind: "err", text: `❌ ${e.message}` });
    } finally { setBusy(false); }
  }

  return (
    <div className="onboarding">
      <div className="card">
        <div className="ob-head">
          <div className="logo-lg">🦋</div>
          <h1>欢迎来到 Agent Drive</h1>
          <p className="muted">第一步：为你的网盘配置 AI 大脑。这是唯一需要手动的一步，<b>之后所有事情都可以交给 Agent 自己完成</b>——包括修改这里的配置。</p>
        </div>

        <div className="field">
          <label>协议类型</label>
          <div className="provider-grid">
            {PROVIDERS.map((p) => (
              <button key={p.value} className={`provider-card ${type === p.value ? "active" : ""}`} onClick={() => switchType(p.value)}>
                <b>{p.label}</b>
                <small>{p.desc}</small>
              </button>
            ))}
          </div>
        </div>

        <div className="field">
          <label>Base URL</label>
          <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder={current.placeholder} />
        </div>

        <div className="field">
          <label>API Key</label>
          <input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-… / jina_…" />
        </div>

        <div className="field">
          <label>模型</label>
          <input value={model} onChange={(e) => setModel(e.target.value)} placeholder={current.model} />
        </div>

        {msg && <div className={`msg ${msg.kind}`}>{msg.text}</div>}
        {diag && diag.ok && (
          <div className="diag">
            <b>模型诊断</b>
            <div>模型: {diag.model} · 延迟: {diag.latency_ms}ms · 支持工具: {diag.supports_tools ? "✅" : "❌"}</div>
            <div>上下文窗口: {diag.context_window}</div>
          </div>
        )}

        <div className="ob-actions">
          <button className="btn ghost" onClick={handleTest} disabled={busy}>{busy ? "测试中…" : "🔌 测试连接"}</button>
          <button className="btn primary" onClick={handleSave} disabled={busy}>{busy ? "配置中…" : "🚀 完成配置，启动 Agent"}</button>
        </div>
      </div>
    </div>
  );
}
