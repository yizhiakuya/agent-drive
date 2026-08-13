import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { chatStream } from "../../api/chat.js";
import { getSession, summarizeSession } from "../../api/sessions.js";

function fmtSize(n) {
  if (n > 1e9) return (n / 1e9).toFixed(1) + " GB";
  if (n > 1e6) return (n / 1e6).toFixed(1) + " MB";
  if (n > 1e3) return (n / 1e3).toFixed(1) + " KB";
  return n + " B";
}

/** 上下文进度条：已用/总窗口（256K） */
function fmtTokens(n) {
  if (n >= 1e6) return (n / 1e6).toFixed(2) + "M";
  if (n >= 1e3) return (n / 1e3).toFixed(1) + "K";
  return n + "";
}

function ContextBar({ usage }) {
  const { used = 0, total = 262144, percent = 0 } = usage;
  const pct = Math.min(100, percent);
  const color = pct > 80 ? "var(--danger)" : pct > 50 ? "#d97706" : "var(--accent2)";
  return (
    <div className="context-bar" title={`上下文占用: 已用 ${fmtTokens(used)} / ${fmtTokens(total)}`}>
      <span className="context-label">上下文</span>
      <div className="context-track">
        <div className="context-fill" style={{ width: `${pct}%`, background: color }} />
      </div>
      <span className="context-text">{fmtTokens(used)} / {fmtTokens(total)}</span>
    </div>
  );
}

/** 内联工具步骤节点（OpenClaw 式：执行中/完成/失败 + 可展开结果） */
const TOOL_ICONS = {
  list_files: "📂", search_files: "🔍", read_file: "📖", write_file: "✍️",
  append_file: "➕", copy_file: "📄", create_folder: "📁", rename_file: "✏️",
  move_file: "🚚", delete_file: "🗑️", get_storage_info: "💾",
  get_system_status: "⚙️", set_llm_provider: "🔌", test_llm_connection: "📡",
  set_preference: "🎛️", add_rule: "📏", remove_rule: "➖", view_audit_log: "🧾",
  analyze_failures: "🔬", set_plan: "📋", update_plan: "📋",
  remember: "🧠", memory_search: "🔎", memory_get: "📇", read_skill: "📚",
};
const STEP_STATUS = { running: ["🔄", "执行中"], done: ["✅", "完成"], error: ["❌", "失败"] };

function ToolStep({ step }) {
  const [open, setOpen] = useState(false);
  const [statusIcon, statusText] = STEP_STATUS[step.status] || ["•", ""];
  const icon = TOOL_ICONS[step.tool] || "🔧";
  const argsBrief = JSON.stringify(step.arguments || {});
  return (
    <div className={`tool-step ${step.status}`}>
      <div className="tool-step-head" onClick={() => setOpen(!open)}>
        <span className="tool-step-icon">{icon}</span>
        <span className="tool-step-name">{step.tool}</span>
        <code className="tool-step-args">{argsBrief.length > 60 ? argsBrief.slice(0, 60) + "…" : argsBrief}</code>
        <span className={`tool-step-status ${step.status}`}>{statusIcon} {statusText}</span>
        <span className="tool-step-toggle">{open ? "▲" : "▼"}</span>
      </div>
      {open && step.output && (
        <div className="tool-step-body">
          {step.parsed && step.parsed.ok === false ? (
            <div className="trace-error">❌ {step.parsed.error}</div>
          ) : step.parsed && step.tool === "list_files" && Array.isArray(step.parsed) ? (
            <table className="trace-table">
              <thead><tr><th>名称</th><th>类型</th><th>大小</th></tr></thead>
              <tbody>
                {step.parsed.map((f, i) => (
                  <tr key={i}>
                    <td>{f.is_dir ? "📂" : "📄"} {f.name}</td>
                    <td>{f.is_dir ? "文件夹" : "文件"}</td>
                    <td>{f.is_dir ? "—" : fmtSize(f.size)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <code className="trace-raw">{step.output}</code>
          )}
        </div>
      )}
    </div>
  );
}

/** 任务计划卡片：逐步状态可视化 */
function PlanCard({ plan }) {
  const icons = { pending: "⏳", in_progress: "🔄", done: "✅", skipped: "⏭️", failed: "❌" };
  const doneCount = plan.filter((s) => s.status === "done").length;
  return (
    <div className="plan-card">
      <div className="plan-title">📋 执行计划（{doneCount}/{plan.length}）</div>
      {plan.map((s, i) => (
        <div key={i} className={`plan-step ${s.status}`}>
          <span className="plan-icon">{icons[s.status] || "⏳"}</span>
          <span className="plan-text">{s.text}</span>
        </div>
      ))}
    </div>
  );
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
    { type: "assistant", content: "你好！我是你的文件管家 🦋 我可以让你搜索文件、整理资料、配置系统……例如：\n\n- \"看看网盘里有什么文件\"\n- \"帮我建一个叫 项目 的文件夹\"\n- \"把 LLM 换成 DeepSeek\"\n- \"以后下载的文件自动归档\"" },
  ]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [trace, setTrace] = useState([]);
  const [pending, setPending] = useState(null);
  const [plan, setPlan] = useState([]);
  const [contextUsage, setContextUsage] = useState(null);
  const [sid, setSid] = useState(sessionId);
  const bottomRef = useRef(null);
  const sidRef = useRef(sessionId); // 已接受会话 id（区分"会话创建"与"用户切换"）
  const abortRef = useRef(null);    // 在途流请求的取消控制器（防串消息）

  // 切换会话时清空当前对话（M1 简化：历史从会话加载在 M2 完善）
  useEffect(() => {
    // 会话创建完成后 sessionId 会更新为当前会话 id（sidRef 已同步）→ 跳过，不清空
    // 用户点击会话列表切换 → 加载该会话历史消息；新建会话（null）→ 清空
    if (sessionId !== sidRef.current) {
      // 切换会话：中止在途流，防止旧回复串进新会话
      if (abortRef.current) {
        abortRef.current.abort();
        abortRef.current = null;
      }
      setBusy(false);
      sidRef.current = sessionId;
      setSid(sessionId);
      setPending(null);
      if (sessionId) {
        loadSession(sessionId);
      } else {
        setMessages([]);
        setTrace([]);
        setPlan([]);
        setContextUsage(null);
      }
    }
  }, [sessionId]);

  async function loadSession(sidToLoad) {
    try {
      const r = await getSession(sidToLoad);
      const msgs = (r.messages || [])
        .filter((m) => ["user", "assistant", "tool_call"].includes(m.role))
        .map((m) => {
          if (m.role === "tool_call") {
            const failed = m.parsed && m.parsed.ok === false;
            return {
              type: "tool_step",
              status: failed ? "error" : "done",
              tool: m.tool,
              arguments: m.arguments || {},
              output: m.output || "",
              parsed: m.parsed,
            };
          }
          return { type: m.role, content: m.content || "" };
        });
      setMessages(msgs.length ? msgs : []);
      setTrace([]);
      setPlan([]);
      setContextUsage(null);
      setContextUsage(null);
    } catch (e) {
      setMessages([]);
    }
  }

  async function send(message, confirmations = []) {
    const msg = message ?? input.trim();
    if (!msg || busy) return;
    if (!message) setInput("");
    // 只传窗口内历史（最近 30 条），后端按 token 预算再截断
    const history = messages
      .filter((m) => (m.type === "user" || m.type === "assistant"))
      .map((m) => ({ role: m.type, content: m.content }))
      .slice(-30);
    setMessages((m) => [...m, { type: "user", content: msg }]);
    setBusy(true);
    setPending(null);
    setTrace([]);
    setPlan([]);
    setContextUsage(null);
    // 在途流控制器（切换会话时 abort）
    const controller = new AbortController();
    abortRef.current = controller;
    const sendSid = sid; // 快照：校验回复是否属于当前会话

    // 占位：流式文本写入这条 assistant 消息
    let replyRef = "";
    setMessages((m) => [...m, { type: "assistant", content: "" }]);

    try {
      const r = await chatStream(msg, history, sid, confirmations, (event, data) => {
        // 已切换会话/被中止：丢弃迟到事件（防串消息）
        if (event === "text") {
          replyRef += data;
          setMessages((m) => {
            const copy = [...m];
            // 最后一条若是 tool_step，先把占位 assistant 补上
            if (copy.length && copy[copy.length - 1].type === "tool_step") {
              copy.push({ type: "assistant", content: "" });
            }
            copy[copy.length - 1] = { type: "assistant", content: replyRef };
            return copy;
          });
        } else if (event === "tool_start") {
          // 内联步骤节点：执行中状态
          setMessages((m) => [...m, { type: "tool_step", status: "running", ...data }]);
        } else if (event === "tool_trace") {
          setTrace((t) => [...t, data]);
          // 更新对应步骤节点为完成/失败
          setMessages((m) => {
            const copy = [...m];
            const failed = data.parsed && data.parsed.ok === false;
            for (let i = copy.length - 1; i >= 0; i--) {
              const node = copy[i];
              if (node.type === "tool_step" && node.tool === data.tool && node.status === "running") {
                copy[i] = { ...node, status: failed ? "error" : "done", output: data.output, parsed: data.parsed };
                break;
              }
            }
            return copy;
          });
          if ((data.tool === "set_plan" || data.tool === "update_plan") && data.parsed?.plan) {
            setPlan(data.parsed.plan);
          }
        }
      }, controller.signal);
      if (sendSid !== sidRef.current) {
        // 本会话已切换，丢弃结果
        return;
      }
      if (r?.plan?.length) setPlan(r.plan);
      if (r?.context_usage) setContextUsage(r.context_usage);
      if (r?.session_id) {
        sidRef.current = r.session_id; // 关键：先标记本会话 id，防止 effect 误清空
        setSid(r.session_id);
        onSessionCreated?.(r.session_id);
      }
      // 步数耗尽警告（truncated）
      if (r?.truncated) {
        setMessages((m) => [...m, { type: "system", content: "⚠️ 任务达到最大步数，可能未完成，回复「继续」可接着做" }]);
      }
      if (r?.pending_confirmation) setPending(r.pending_confirmation);
      if (r?.needs_summary && r?.session_id) {
        summarizeSession(r.session_id).catch(() => {});
      }
      setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }), 50);
    } catch (e) {
      if (e.name === "AbortError") return; // 主动中止：静默
      setMessages((m) => {
        const copy = [...m];
        copy[copy.length - 1] = { type: "assistant", content: `⚠️ 出错了：${e.message}` };
        return copy;
      });
    } finally {
      setBusy(false);
    }
  }

  function confirmYes() {
    if (!pending) return;
    // 提交服务端签发的完整确认对象（nonce+signature，防伪造）
    const confirmed = [{
      tool: pending.tool,
      arguments: pending.arguments,
      nonce: pending.nonce,
      ts: pending.ts,
      signature: pending.signature,
    }];
    setMessages((m) => [...m, { type: "user", content: `✅ 我确认执行：${pending.tool}` }]);
    setPending(null);
    send(`请继续执行刚才确认的操作：${pending.tool} ${JSON.stringify(pending.arguments)}`, confirmed);
  }

  function confirmNo() {
    setMessages((m) => [...m, { type: "assistant", content: "好的，已取消该高风险操作 ✅" }]);
    setPending(null);
  }

  return (
    <section className="chat-panel">
      <div className="messages">
        {messages.map((m, i) => {
          if (m.type === "tool_step") return <ToolStep key={i} step={m} />;
          const isThinking = busy && m.type === "assistant" && m.content === "" && i === messages.length - 1;
          return (
            <div key={i} className={`msg-row ${m.type}`}>
              <div className={`bubble ${isThinking ? "typing" : ""}`}>
                {isThinking ? "Agent 思考中…" : m.type === "assistant" ? (
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.content}</ReactMarkdown>
                ) : (
                  m.content
                )}
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

      {plan.length > 0 && <PlanCard plan={plan} />}

      {contextUsage && <ContextBar usage={contextUsage} />}

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
