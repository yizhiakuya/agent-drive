"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { chatStream } from "@/lib/api/chat";
import { getSession, summarizeSession } from "@/lib/api/sessions";
import { emitFilesChanged } from "@/lib/events";
import { ToolStep } from "./ToolStep";
import { ContextBar } from "./ContextBar";
import { PlanCard, PlanStep } from "./PlanCard";
import { useAppStore } from "@/lib/store";

interface Message {
  type: "user" | "assistant" | "tool_step" | "system";
  content: string;
  tool?: string;
  arguments?: Record<string, unknown>;
  status?: "running" | "done" | "error";
  output?: string;
  parsed?: Record<string, unknown> | unknown[];
}

interface PendingConfirmation {
  tool: string;
  arguments: Record<string, unknown>;
  nonce: string;
  ts: number;
  signature: string;
  message?: string;
}

const FILES_TOOLS = ["list_files", "search_files", "read_file", "write_file", "append_file",
  "copy_file", "create_folder", "rename_file", "move_file", "delete_file", "get_storage_info",
  "read_document", "search_content", "semantic_search", "index_stats"];

const QUICK_ACTIONS = [
  { icon: "📂", label: "看看网盘里有什么", msg: "看看网盘里有什么文件" },
  { icon: "🔍", label: "按内容找文件", msg: "帮我按内容搜索文件（用语义搜索）" },
  { icon: "📁", label: "整理文件", msg: "帮我整理一下网盘里的文件" },
  { icon: "📝", label: "写一份周报", msg: "根据最近的会话写一份周报" },
];

function greet() {
  const h = new Date().getHours();
  if (h < 6) return "夜深了 🌙";
  if (h < 12) return "早上好 ☀️";
  if (h < 18) return "下午好 🌤️";
  return "晚上好 🌆";
}

export default function ChatPanel() {
  const sessionId = useAppStore((s) => s.sessionId);
  const setSessionId = useAppStore((s) => s.setSessionId);
  const bumpSessions = useAppStore((s) => s.bumpSessions);

  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState<PendingConfirmation | null>(null);
  const [plan, setPlan] = useState<PlanStep[]>([]);
  const [contextUsage, setContextUsage] = useState<{ used: number; total: number; percent: number } | null>(null);
  const [showJump, setShowJump] = useState(false);

  const bottomRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const sidRef = useRef<string | null>(sessionId);
  const abortRef = useRef<AbortController | null>(null);

  async function loadSession(sid: string) {
    try {
      const r = await getSession(sid) as { messages?: { role: string; content: string; tool?: string; arguments?: Record<string, unknown>; output?: string; parsed?: Record<string, unknown> }[] };
      const msgs: Message[] = (r.messages || [])
        .filter((m) => ["user", "assistant", "tool_call"].includes(m.role))
        .map((m) => {
          if (m.role === "tool_call") {
            const failed = m.parsed && (m.parsed as { ok?: boolean }).ok === false;
            return {
              type: "tool_step" as const,
              status: failed ? "error" : "done",
              tool: m.tool || "",
              arguments: m.arguments || {},
              output: m.output || "",
              parsed: m.parsed,
              content: "",
            };
          }
          return { type: m.role as "user" | "assistant", content: m.content || "" };
        });
      setMessages(msgs);
      setPlan([]);
      setContextUsage(null);
    } catch {
      setMessages([]);
    }
  }

  useEffect(() => {
    if (sessionId !== sidRef.current) {
      if (abortRef.current) {
        abortRef.current.abort();
        abortRef.current = null;
      }
      setBusy(false);
      sidRef.current = sessionId;
      setPending(null);
      if (sessionId) {
        loadSession(sessionId);
      } else {
        setMessages([]);
        setPlan([]);
        setContextUsage(null);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  function onScroll() {
    const el = listRef.current;
    if (!el) return;
    setShowJump(el.scrollHeight - el.scrollTop - el.clientHeight > 200);
  }

  function jumpBottom() {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }

  function autoGrow() {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = Math.min(ta.scrollHeight, 160) + "px";
  }

  async function send(message?: string, confirmations: Record<string, unknown>[] = []) {
    const msg = message ?? input.trim();
    if (!msg || busy) return;
    if (!message) {
      setInput("");
      if (taRef.current) taRef.current.style.height = "auto";
    }
    const history = messages
      .filter((m) => m.type === "user" || m.type === "assistant")
      .map((m) => ({ role: m.type, content: m.content }))
      .slice(-80);
    setMessages((m) => [...m, { type: "user", content: msg }]);
    setBusy(true);
    setPending(null);
    setPlan([]);
    setContextUsage(null);
    const controller = new AbortController();
    abortRef.current = controller;
    const sendSid = sidRef.current;

    let replyRef = "";
    setMessages((m) => [...m, { type: "assistant", content: "" }]);

    try {
      const r = await chatStream(msg, history, sendSid, confirmations, (event, data) => {
        if (event === "text") {
          replyRef += data as unknown as string;
          setMessages((m) => {
            const copy = [...m];
            if (copy.length && copy[copy.length - 1].type === "tool_step") {
              copy.push({ type: "assistant", content: "" });
            }
            copy[copy.length - 1] = { type: "assistant", content: replyRef };
            return copy;
          });
        } else if (event === "tool_start") {
          setMessages((m) => [...m, { type: "tool_step", status: "running", content: "", ...(data as object) } as Message]);
        } else if (event === "tool_trace") {
          const d = data as { tool: string; output: string; parsed?: Record<string, unknown> };
          if (FILES_TOOLS.includes(d.tool)) emitFilesChanged();
          setMessages((m) => {
            const copy = [...m];
            const failed = d.parsed && d.parsed.ok === false;
            for (let i = copy.length - 1; i >= 0; i--) {
              const node = copy[i];
              if (node.type === "tool_step" && node.tool === d.tool && node.status === "running") {
                copy[i] = { ...node, status: failed ? "error" : "done", output: d.output, parsed: d.parsed };
                break;
              }
            }
            return copy;
          });
          if ((d.tool === "set_plan" || d.tool === "update_plan") && d.parsed?.plan) {
            setPlan(d.parsed.plan as PlanStep[]);
          }
        }
      }, controller.signal);

      if (sendSid !== sidRef.current) return;
      const rPlan = (r?.plan ?? []) as PlanStep[];
      if (rPlan.length) setPlan(rPlan);
      if (r?.context_usage) setContextUsage(r.context_usage as { used: number; total: number; percent: number });
      if (r?.session_id) {
        const sid = r.session_id as string;
        sidRef.current = sid;
        setSessionId(sid);
        bumpSessions();
      }
      if (r?.truncated) {
        setMessages((m) => [...m, { type: "system", content: "⚠️ 任务达到最大步数，可能未完成，回复「继续」可接着做" }]);
      }
      if (r?.pending_confirmation) setPending(r.pending_confirmation as PendingConfirmation);
      if (r?.needs_summary && r?.session_id) {
        summarizeSession(r.session_id as string).catch(() => {});
      }
      setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }), 50);
    } catch (e) {
      if ((e as Error).name === "AbortError") return;
      setMessages((m) => {
        const copy = [...m];
        copy[copy.length - 1] = { type: "assistant", content: `⚠️ 出错了：${(e as Error).message}` };
        return copy;
      });
    } finally {
      setBusy(false);
    }
  }

  function confirmYes() {
    if (!pending) return;
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

  function stop() {
    if (abortRef.current) {
      abortRef.current.abort();
      abortRef.current = null;
    }
    setBusy(false);
    setMessages((m) => {
      const copy = [...m];
      if (copy.length && copy[copy.length - 1].type === "assistant" && copy[copy.length - 1].content === "") {
        copy[copy.length - 1] = { type: "system", content: "⏹️ 已停止本次任务" };
      } else {
        copy.push({ type: "system", content: "⏹️ 已停止本次任务" });
      }
      return copy;
    });
  }

  const hasRunningTool = messages.some((x) => x.type === "tool_step" && x.status === "running");

  return (
    <section className="flex-1 flex flex-col min-w-0 relative">
      <div ref={listRef} onScroll={onScroll} className="flex-1 overflow-y-auto p-5 pb-8 flex flex-col gap-3">
        {messages.length === 0 && !busy && (
          <div className="flex flex-col items-center gap-3 py-14 text-center animate-fade-in">
            <div className="text-5xl animate-float">🦋</div>
            <div className="text-xl font-bold">{greet()}，我是你的文件管家</div>
            <div className="text-muted text-sm mb-3">用对话管理你的网盘：搜索、整理、理解、自动化</div>
            <div className="flex flex-wrap gap-2.5 justify-center max-w-md">
              {QUICK_ACTIONS.map((a) => (
                <button key={a.label} onClick={() => send(a.msg)}
                        className="flex items-center gap-2 bg-panel border border-border rounded-xl px-4 py-2.5 text-sm cursor-pointer shadow-sm hover:border-accent hover:text-accent hover:-translate-y-0.5 transition-all">
                  <span className="text-base">{a.icon}</span>
                  <span>{a.label}</span>
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m, i) => {
          if (m.type === "tool_step") return <ToolStep key={i} step={{ tool: m.tool || "", arguments: m.arguments, status: m.status || "done", output: m.output, parsed: m.parsed }} />;
          const isThinking = busy && m.type === "assistant" && m.content === "" && i === messages.length - 1;
          return (
            <div key={i} className={`flex ${m.type === "user" ? "justify-end" : "justify-start"} animate-slide-in`}>
              <div className={`max-w-[75%] px-3.5 py-2.5 rounded-2xl whitespace-pre-wrap leading-relaxed text-sm shadow-sm ${
                m.type === "user" ? "bg-accent text-white" : m.type === "system"
                  ? "bg-warn-soft text-warn border border-warn/30"
                  : "bg-card border border-border"
              } ${isThinking ? "text-muted animate-pulse-soft" : ""}`}>
                {isThinking ? (hasRunningTool ? "正在执行操作…" : "Agent 思考中…") : m.type === "assistant" ? (
                  <div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{m.content}</ReactMarkdown></div>
                ) : (
                  m.content
                )}
              </div>
            </div>
          );
        })}
        {pending && !busy && (
          <div className="border border-danger bg-danger-soft rounded-xl p-4 max-w-[75%] animate-slide-in">
            <div className="text-danger font-bold mb-2">⚠️ 高风险操作确认</div>
            <div className="text-sm leading-relaxed mb-3">
              Agent 请求执行：<code className="bg-danger/15 px-2 py-0.5 rounded text-danger">{pending.tool}</code>{" "}
              <code className="bg-danger/15 px-2 py-0.5 rounded text-danger">{JSON.stringify(pending.arguments)}</code>
              <br />此操作<b>不可撤销</b>，是否继续？
            </div>
            <div className="flex gap-2.5">
              <button className="bg-danger text-white px-4 py-2 rounded-lg font-semibold cursor-pointer hover:opacity-85" onClick={confirmYes}>确认执行</button>
              <button className="border border-border text-text px-4 py-2 rounded-lg cursor-pointer hover:bg-card" onClick={confirmNo}>取消</button>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {plan.length > 0 && <div className="px-4 pb-2"><PlanCard plan={plan} /></div>}
      {contextUsage && <ContextBar usage={contextUsage} />}

      <div className="flex gap-2.5 px-5 py-3.5 border-t border-border bg-panel">
        <textarea
          ref={taRef}
          value={input}
          rows={1}
          onChange={(e) => { setInput(e.target.value); autoGrow(); }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
          }}
          placeholder="和你的 Agent 对话…"
          className="flex-1 bg-card border border-border text-text px-3.5 py-2.5 rounded-lg outline-none resize-none text-sm leading-relaxed max-h-40 focus:border-accent focus:bg-panel focus:ring-2 focus:ring-accent-soft"
        />
        {busy ? (
          <button className="bg-danger text-white px-4 rounded-lg font-semibold cursor-pointer hover:opacity-85" onClick={stop}>⏹ 停止</button>
        ) : (
          <button className="bg-accent text-white px-5 rounded-lg font-semibold cursor-pointer disabled:opacity-50"
                  onClick={() => send()} disabled={!input.trim()}>
            {input.trim() ? "发送" : "✈"}
          </button>
        )}
      </div>
      {showJump && (
        <button onClick={jumpBottom} title="回到底部"
                className="absolute bottom-24 right-[45%] bg-panel border border-border rounded-full px-4 py-1.5 text-xs shadow-md cursor-pointer hover:border-accent hover:text-accent animate-slide-in z-10">
          ↓ 最新
        </button>
      )}
    </section>
  );
}
