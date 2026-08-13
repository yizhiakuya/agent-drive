import { useEffect, useRef, useState } from "react";
import { chatStream } from "../../api/chat.js";
import { summarizeSession } from "../../api/sessions.js";

function fmtSize(n) {
  if (n > 1e9) return (n / 1e9).toFixed(1) + " GB";
  if (n > 1e6) return (n / 1e6).toFixed(1) + " MB";
  if (n > 1e3) return (n / 1e3).toFixed(1) + " KB";
  return n + " B";
}

/** 工具调用卡片：按工具类型结构化渲染 */
function TraceCard({ t }) {
  const [open, setOpen] = useState(false);
  const parsed = t.parsed;

  let body;
  if (t.tool === "list_files" && Array.isArray(parsed)) {
    body = (
      <table className="trace-table">
        <thead><tr><th>名称</th><th>类型</th><th>大小</th></tr></thead>
        <tbody>
          {parsed.map((f, i) => (
            <tr key={i}>
              <td>{f.is_dir ? "📂" : "📄"} {f.name}</td>
              <td>{f.is_dir ? "文件夹" : "文件"}</td>
              <td>{f.is_dir ? "—" : fmtSize(f.size)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  } else if (t.tool === "search_files" && Array.isArray(parsed)) {
    body = (
      <div className="trace-cards">
        {parsed.map((f, i) => (
          <div key={i} className="trace-card">
            {f.is_dir ? "📂" : "📄"} <b>{f.name}</b> · {fmtSize(f.size || 0)}
          </div>
        ))}
      </div>
    );
  } else if (parsed && parsed.ok === false) {
    body = <div className="trace-error">❌ {parsed.error}</div>;
  } else {
    body = <code className="trace-raw">{t.output}</code>;
  }

  return (
    <div className="trace-item">
      <div className="trace-head" onClick={() => setOpen(!open)}>
        <span className="tool-name">{t.tool}</span>
        <code className="trace-args">{JSON.stringify(t.arguments)}</code>
        <span className="trace-toggle">{open ? "▲" : "▼"}</span>
      </div>
      {open && <div className="trace-body">{body}</div>}
    </div>
  );
}

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
  const sidRef = useRef(sessionId); // 已接受会话 id（区分"会话创建"与"用户切换"）

  // 切换会话时清空当前对话（M1 简化：历史从会话加载在 M2 完善）
  useEffect(() => {
    // 会话创建完成后 sessionId 会更新为当前会话 id（sidRef 已同步）→ 跳过，不清空
    // 用户点击会话列表切换 / 新建会话 → sessionId 变化 ≠ sidRef → 清空开始新会话
    if (sessionId !== sidRef.current) {
      sidRef.current = sessionId;
      setSid(sessionId);
      setMessages([]);
      setTrace([]);
      setPending(null);
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
    setTrace([]);

    // 占位：流式文本写入这条 assistant 消息
    let replyRef = "";
    setMessages((m) => [...m, { role: "assistant", content: "" }]);

    try {
      const r = await chatStream(msg, history, sid, confirmations, (event, data) => {
        if (event === "text") {
          replyRef += data;
          setMessages((m) => {
            const copy = [...m];
            copy[copy.length - 1] = { role: "assistant", content: replyRef };
            return copy;
          });
        } else if (event === "tool_trace") {
          setTrace((t) => [...t, data]);
        }
      });
      if (r?.session_id) {
        sidRef.current = r.session_id; // 关键：先标记本会话 id，防止 effect 误清空
        setSid(r.session_id);
        onSessionCreated?.(r.session_id);
      }
      if (r?.pending_confirmation) setPending(r.pending_confirmation);
      if (r?.needs_summary && r?.session_id) {
        summarizeSession(r.session_id).catch(() => {});
      }
      setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }), 50);
    } catch (e) {
      setMessages((m) => {
        const copy = [...m];
        copy[copy.length - 1] = { role: "assistant", content: `⚠️ 出错了：${e.message}` };
        return copy;
      });
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
        {messages.map((m, i) => {
          const isThinking = busy && m.role === "assistant" && m.content === "" && i === messages.length - 1;
          return (
            <div key={i} className={`msg-row ${m.role}`}>
              <div className={`bubble ${isThinking ? "typing" : ""}`}>
                {isThinking ? "Agent 思考中…" : m.content}
              </div>
            </div>
          );
        })}
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
          {trace.map((t, i) => <TraceCard key={i} t={t} />)}
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
