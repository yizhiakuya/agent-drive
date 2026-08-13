import { useEffect, useRef, useState } from "react";
import { chat } from "../../api/chat.js";
import { summarizeSession } from "../../api/sessions.js";

export default function ChatPanel({ sessionId, onSessionCreated }) {
  const [messages, setMessages] = useState([
    { role: "assistant", content: "你好！我是你的文件管家 🦋 我可以让你搜索文件、整理资料、配置系统……例如：\n\n- \"看看网盘里有什么文件\"\n- \"帮我建一个叫 项目 的文件夹\"\n- \"把 LLM 换成 DeepSeek\"\n- \"以后下载的文件自动归档\"" },
  ]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [trace, setTrace] = useState([]);
  const [pending, setPending] = useState(null);
  const [sid, setSid] = useState(sessionId);
  const bottomRef = useRef(null);

  // 切换会话时清空当前对话（M1 简化：历史从会话加载在 M2 完善）
  useEffect(() => {
    setSid(sessionId);
    if (sessionId) {
      // 加载会话消息（简单版：仅清空重来）
      setMessages([]);
      setTrace([]);
    }
  }, [sessionId]);

  async function send(message, confirmations = []) {
    const msg = message ?? input.trim();
    if (!msg || busy) return;
    if (!message) setInput("");
    const history = messages.filter((m) => m.role !== "system" && m.role !== "tool");
    setMessages((m) => [...m, { role: "user", content: msg }]);
    setBusy(true);
    setPending(null);
    try {
      const r = await chat(msg, history, sid, confirmations);
      if (r.session_id) {
        setSid(r.session_id);
        onSessionCreated?.(r.session_id);
      }
      setMessages((m) => [...m, { role: "assistant", content: r.reply }]);
      if (r.tool_trace?.length) setTrace(r.tool_trace);
      if (r.pending_confirmation) setPending(r.pending_confirmation);
      // 多轮后自动生成会话摘要（跨会话记忆）
      if (r.needs_summary && r.session_id) {
        summarizeSession(r.session_id).catch(() => {});
      }
      setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }), 50);
    } catch (e) {
      setMessages((m) => [...m, { role: "assistant", content: `⚠️ 出错了：${e.message}` }]);
    } finally {
      setBusy(false);
    }
  }

  function confirmYes() {
    if (!pending) return;
    const confirmed = [{ tool: pending.tool, arguments: pending.arguments }];
    setMessages((m) => [...m, { role: "user", content: `✅ 我确认执行：${pending.tool}` }]);
    setPending(null);
    send(`请继续执行刚才确认的操作：${pending.tool} ${JSON.stringify(pending.arguments)}`, confirmed);
  }

  function confirmNo() {
    setMessages((m) => [...m, { role: "assistant", content: "好的，已取消该高风险操作 ✅" }]);
    setPending(null);
  }

  return (
    <section className="chat-panel">
      <div className="messages">
        {messages.map((m, i) => (
          <div key={i} className={`msg-row ${m.role}`}>
            <div className="bubble">{m.content}</div>
          </div>
        ))}
        {busy && <div className="msg-row assistant"><div className="bubble typing">Agent 思考中…</div></div>}

        {pending && !busy && (
          <div className="confirm-box">
            <div className="confirm-title">⚠️ 高风险操作确认</div>
            <div className="confirm-body">
              Agent 请求执行：<code>{pending.tool}</code>{" "}
              <code>{JSON.stringify(pending.arguments)}</code>
              <br />此操作<b>不可撤销</b>，是否继续？
            </div>
            <div className="confirm-actions">
              <button className="btn danger" onClick={confirmYes}>确认执行</button>
              <button className="btn ghost" onClick={confirmNo}>取消</button>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {trace.length > 0 && (
        <div className="trace">
          <b>🔧 本轮工具调用</b>
          {trace.map((t, i) => (
            <div key={i} className="trace-item">
              <span className="tool-name">{t.tool}</span>
              <code>{JSON.stringify(t.arguments)}</code>
            </div>
          ))}
        </div>
      )}

      <div className="input-bar">
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && send()}
          placeholder="和你的 Agent 对话，管理网盘…"
          disabled={busy}
        />
        <button onClick={() => send()} disabled={busy || !input.trim()}>发送</button>
      </div>
    </section>
  );
}
