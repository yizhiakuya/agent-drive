"use client";
import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api } from "@/lib/api/client";
import { getSession } from "@/lib/api/sessions";
import { EV } from "@/lib/events";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ToolStep } from "./ToolStep";
import { ContextBar } from "./ContextBar";
import { PlanCard, PlanStep } from "./PlanCard";
import { useAppStore } from "@/lib/store";
import { maskSecretsJson } from "@/lib/format";
import { ArrowUp, Brain, ChevronDown, FileSearch, FileText, FolderOpen, ListChecks, ShieldAlert, Square, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useChatStream, chatTextDelta } from "./useChatStream";
import type { Message, PendingConfirmation, ThinkingLevel } from "./useChatStream";

export { chatTextDelta };

const QUICK_ACTIONS: { icon: LucideIcon; label: string; msg: string }[] = [
  { icon: FolderOpen, label: "看看网盘里有什么", msg: "看看网盘里有什么文件" },
  { icon: FileSearch, label: "按内容找文件", msg: "帮我按内容搜索文件（用语义搜索）" },
  { icon: ListChecks, label: "整理文件", msg: "帮我整理一下网盘里的文件" },
  { icon: FileText, label: "写一份周报", msg: "根据最近的会话写一份周报" },
];

const THINKING_OPTIONS: { value: ThinkingLevel; label: string; description: string }[] = [
  { value: "auto", label: "自动", description: "由模型决定" },
  { value: "low", label: "快速", description: "更快响应" },
  { value: "medium", label: "标准", description: "速度与深度平衡" },
  { value: "high", label: "深度", description: "复杂任务优先" },
];

function isThinkingLevel(value: string): value is ThinkingLevel {
  return THINKING_OPTIONS.some((option) => option.value === value);
}

function greet() {
  const h = new Date().getHours();
  if (h < 6) return "夜深了";
  if (h < 12) return "早上好";
  if (h < 18) return "下午好";
  return "晚上好";
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
  const [autoReport, setAutoReport] = useState<{ date: string; text: string } | null>(null);
  const [reportDismissed, setReportDismissed] = useState(false);
  const [thinkingLevel, setThinkingLevel] = useState<ThinkingLevel>("auto");

  useEffect(() => {
    try {
      const saved = localStorage.getItem("agent-drive-thinking-level");
      if (saved && isThinkingLevel(saved)) setThinkingLevel(saved);
    } catch { /* 忽略 */ }
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem("agent-drive-thinking-level", thinkingLevel);
    } catch { /* 忽略 */ }
  }, [thinkingLevel]);

  function markReportRead(date: string) {
    try {
      localStorage.setItem("agent-drive-report-read", date);
    } catch { /* 忽略 */ }
    setReportDismissed(true);
  }

  function isReportRead(date: string): boolean {
    try {
      return localStorage.getItem("agent-drive-report-read") === date;
    } catch {
      return false;
    }
  }

  const bottomRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const sidRef = useRef<string | null>(sessionId);

  async function loadSession(sid: string) {
    try {
      const r = await getSession(sid) as { messages?: { role: string; content: string; reasoning?: string; tool?: string; arguments?: Record<string, unknown>; output?: string; parsed?: Record<string, unknown> }[] };
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
          return {
            type: m.role as "user" | "assistant",
            content: m.content || "",
            ...(m.reasoning ? { reasoning: m.reasoning } : {}),
          };
        });
      setMessages(msgs);
      setPlan([]);
      setContextUsage(null);
    } catch {
      setMessages([]);
    }
  }

  const { send, stop, abortStream } = useChatStream({
    messages,
    sessionIdRef: sidRef,
    setMessages,
    setBusy,
    setPending,
    setPlan,
    setContextUsage,
    setSessionId,
    bumpSessions,
    onFinish() {
      bottomRef.current?.scrollIntoView({ behavior: "smooth" });
    },
  });

  // 封装发送：无显式消息时清空输入框并复位高度（原 send() 的内联行为）。
  async function handleSend(
    message?: string,
    confirmations: Record<string, unknown>[] = [],
    selectedThinkingLevel: ThinkingLevel = thinkingLevel,
  ) {
    const msg = message ?? input.trim();
    if (!msg || busy) return;
    if (!message) {
      setInput("");
      if (taRef.current) taRef.current.style.height = "auto";
    }
    await send(msg, confirmations, selectedThinkingLevel);
  }

  // 主动汇报：空会话时拉取最近一次自动化报告
  useEffect(() => {
    (async () => {
      try {
        const d = await api<{ report?: { date: string; text: string } | null }>(
          "/automation/latest",
          { cache: "no-store" },
        );
        if (d.report && !isReportRead(d.report.date)) setAutoReport(d.report);
      } catch { /* 忽略 */ }
    })();
  }, [sessionId]);

  // 全局刷新（下拉刷新）：重载当前会话消息
  useEffect(() => {
    const h = () => {
      if (sessionId) loadSession(sessionId);
    };
    window.addEventListener(EV.refresh, h);
    return () => window.removeEventListener(EV.refresh, h);
  }, [sessionId]);

  useEffect(() => {
    if (sessionId !== sidRef.current) {
      abortStream();
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
  }, [sessionId, abortStream]);

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

  function confirmYes() {
    if (!pending) return;
    const confirmed = [{
      tool: pending.tool,
      arguments: pending.arguments,
      nonce: pending.nonce,
      ts: pending.ts,
      signature: pending.signature,
    }];
    setMessages((m) => [...m, { type: "user", content: `我确认执行：${pending.tool}` }]);
    setPending(null);
    handleSend(
      `请继续执行刚才确认的操作：${pending.tool} ${JSON.stringify(pending.arguments)}`,
      confirmed,
      thinkingLevel,
    );
  }

  function confirmNo() {
    setMessages((m) => [...m, { type: "assistant", content: "好的，已取消该高风险操作。" }]);
    setPending(null);
  }

  const hasRunningTool = messages.some((x) => x.type === "tool_step" && x.status === "running");

  return (
    <section className="relative flex min-w-0 flex-1 flex-col bg-panel">
      <div ref={listRef} onScroll={onScroll} className="flex-1 overflow-y-auto px-4 pb-8 pt-6 sm:px-6">
        <div className="mx-auto flex w-full max-w-4xl flex-col gap-4">
        {messages.length === 0 && !busy && autoReport && !reportDismissed && (
           <div className="animate-slide-in rounded-lg border border-border bg-card/60 p-4 text-sm">
            <div className="flex justify-between items-start gap-2">
              <b className="font-semibold">昨夜自动化报告 · {autoReport.date}</b>
              <button className="text-muted cursor-pointer hover:text-text" onClick={() => markReportRead(autoReport.date)} aria-label="关闭自动化报告"><X className="size-3.5" /></button>
            </div>
            <div className="markdown-body mt-1 max-h-56 overflow-auto">{autoReport.text}</div>
            <Button size="sm" variant="outline" className="mt-3"
                    onClick={() => { markReportRead(autoReport.date); handleSend("详细说说昨晚的自动化执行结果"); }}>
              让 Agent 总结一下
            </Button>
          </div>
        )}
        {messages.length === 0 && !busy && (
          <div className="flex flex-col items-center gap-3 py-16 text-center animate-fade-in">
            <div className="grid size-10 place-items-center rounded-lg bg-text text-panel font-mono text-sm">AD</div>
            <div className="text-xl font-semibold tracking-tight">{greet()}，Agent Drive 已就绪</div>
            <div className="max-w-md text-sm text-muted">从文件搜索、整理到后台索引，直接用自然语言开始。</div>
            <div className="mt-2 flex max-w-2xl flex-wrap justify-center gap-2">
              {QUICK_ACTIONS.map((a) => (
                <Button key={a.label} variant="outline" onClick={() => handleSend(a.msg)}
                        className="h-9 rounded-md border-border bg-panel px-3 text-xs shadow-none hover:border-text hover:bg-card hover:text-text">
                  <a.icon className="size-3.5 text-muted" aria-hidden="true" />
                  <span>{a.label}</span>
                </Button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m, i) => {
          if (m.type === "tool_step") return <ToolStep key={i} step={{ tool: m.tool || "", arguments: m.arguments, status: m.status || "done", output: m.output, parsed: m.parsed }} />;
          const isThinking = busy && m.type === "assistant" && m.content === "" && !m.reasoning && i === messages.length - 1;
          const isLatestReasoning = busy && m.type === "assistant" && i === messages.length - 1;
          return (
            <div key={i} className={`flex ${m.type === "user" ? "justify-end" : "justify-start"} animate-slide-in`}>
              <div className={`whitespace-pre-wrap text-sm leading-relaxed ${
                m.type === "user"
                  ? "max-w-[min(85%,42rem)] rounded-md rounded-tr-sm bg-text px-4 py-3 text-panel shadow-sm"
                  : m.type === "system"
                    ? "max-w-3xl border-l-2 border-warn pl-3 text-warn"
                    : "w-full max-w-3xl border-l-2 border-border pl-3 text-text"
              } ${isThinking ? "text-muted animate-pulse-soft" : ""}`}>
                {isThinking ? (
                  <div className="flex items-center gap-2 text-muted" aria-live="polite">
                    <Skeleton className="h-2 w-2 rounded-full" />
                    <Skeleton className="h-2 w-28" />
                    <span className="text-xs whitespace-nowrap">{hasRunningTool ? "正在执行操作…" : "Agent 思考中…"}</span>
                  </div>
                ) : m.type === "assistant" ? (
                  <>
                    {m.reasoning && (
                      <details
                        data-testid="reasoning-block"
                         className="mb-2 max-w-2xl overflow-hidden rounded-md border border-border bg-card/50 text-xs"
                      >
                        <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-3 py-2 text-muted transition-colors hover:bg-card hover:text-text [&::-webkit-details-marker]:hidden">
                          <span className="flex items-center gap-1.5">
                            <Brain className="size-3.5" aria-hidden="true" />
                            <span>思考过程{isLatestReasoning ? " · 进行中" : ""}</span>
                          </span>
                          <ChevronDown className="size-3.5 shrink-0" aria-hidden="true" />
                        </summary>
                        <div className="markdown-body border-t border-border px-3 py-2 text-muted">
                          <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.reasoning}</ReactMarkdown>
                        </div>
                      </details>
                    )}
                    {m.content && (
                      <div className="markdown-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{m.content}</ReactMarkdown></div>
                    )}
                  </>
                ) : (
                  m.content
                )}
              </div>
            </div>
          );
        })}
        {pending && !busy && (
           <div className="max-w-xl overflow-hidden rounded-lg border border-danger/50 bg-panel shadow-sm animate-slide-in">
            <div className="flex items-center gap-2 bg-text px-4 py-2.5 text-xs font-semibold tracking-wide text-panel">
              <span className="grid size-5 place-items-center rounded-full bg-danger text-panel">!</span>
              <span className="flex items-center gap-1.5"><ShieldAlert className="size-3.5" /> 高风险操作确认</span>
            </div>
            <div className="space-y-3 p-4">
              <div className="text-sm leading-relaxed text-text">
                Agent 请求执行：<code className="bg-danger-soft px-1.5 py-0.5 font-mono text-xs text-danger">{pending.tool}</code>{" "}
                <code className="break-all bg-danger-soft px-1.5 py-0.5 font-mono text-xs text-danger">{maskSecretsJson(pending.arguments)}</code>
                <div className="mt-2 text-xs text-muted">此操作不可撤销，请确认目标和参数后继续。</div>
              </div>
              <div className="flex gap-2 border-t border-border pt-3">
                <Button variant="default" onClick={confirmYes}>确认执行</Button>
                <Button variant="outline" onClick={confirmNo}>取消</Button>
              </div>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
        </div>
      </div>

      {plan.length > 0 && <div className="px-4 pb-2 sm:px-6"><div className="mx-auto max-w-4xl"><PlanCard plan={plan} /></div></div>}
      {contextUsage && <ContextBar usage={contextUsage} />}

      <div className="input-bar-safe shrink-0 border-t border-border bg-panel px-4 py-3 sm:px-6">
        <div className="mx-auto max-w-4xl">
           <div className="overflow-hidden rounded-md border border-border bg-panel shadow-sm focus-within:border-text">
            <div className="flex items-center justify-between gap-3 border-b border-border bg-card/60 px-3 py-2">
              <div className="flex min-w-0 items-center gap-2">
                <Brain className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
                <span className="shrink-0 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted">推理层级</span>
          <Select
            value={thinkingLevel}
            onValueChange={(value) => { if (isThinkingLevel(value)) setThinkingLevel(value); }}
            disabled={busy}
          >
            <SelectTrigger size="sm" aria-label="思考等级" className="h-7 min-w-[104px] rounded-md border-transparent bg-panel text-xs shadow-none">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {THINKING_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  <span>{option.label}</span>
                  <span className="ml-1.5 text-xs text-muted">{option.description}</span>
              </SelectItem>
              ))}
            </SelectContent>
          </Select>
              </div>
              <span className="hidden shrink-0 font-mono text-[10px] text-muted sm:inline">{busy ? "STREAMING" : "READY"}</span>
            </div>
            <div className="flex items-end gap-2 px-3 py-2.5">
              <textarea
                ref={taRef}
                value={input}
                rows={1}
                onChange={(e) => { setInput(e.target.value); autoGrow(); }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); handleSend(); }
                }}
                placeholder="和你的 Agent 对话…"
                className="max-h-40 flex-1 resize-none bg-transparent px-1 py-1 text-sm leading-relaxed text-text outline-none placeholder:text-muted focus:ring-0"
              />
              {busy ? (
                <Button variant="destructive" onClick={stop}><Square className="size-3.5" /> 停止</Button>
              ) : (
                <Button className="h-9 min-w-16" onClick={() => handleSend()} disabled={!input.trim()}>
                  {input.trim() ? "发送" : <ArrowUp className="size-4" />}
                </Button>
              )}
            </div>
          </div>
          <div className="mt-2 flex flex-wrap justify-center gap-1.5">
            {QUICK_ACTIONS.slice(0, 3).map((a) => (
              <button
                key={a.label}
                type="button"
                className="h-7 rounded-md border border-border bg-card/50 px-2.5 text-[11px] text-muted transition-colors hover:border-text hover:bg-panel hover:text-text"
                onClick={() => setInput(a.msg)}
              >
                {a.label}
              </button>
            ))}
          </div>
        </div>
      </div>
      {showJump && (
          <Button onClick={jumpBottom} title="回到底部" variant="outline"
                className="absolute bottom-28 right-4 rounded-full px-3 py-1.5 text-xs shadow-md hover:border-text hover:text-text sm:right-8 animate-slide-in z-10">
          <ArrowUp className="size-3.5 rotate-180" /> 最新
        </Button>
      )}
    </section>
  );
}
