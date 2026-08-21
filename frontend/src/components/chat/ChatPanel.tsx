"use client";
import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api } from "@/lib/api/client";
import { getConfig, listModels } from "@/lib/api/config";
import { getSession } from "@/lib/api/sessions";
import { EV, emitToast } from "@/lib/events";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ToolStep } from "./ToolStep";
import { ContextInjection } from "./ContextInjection";
import { ContextBar } from "./ContextBar";
import { PlanCard, PlanStep } from "./PlanCard";
import { useAppStore } from "@/lib/store";
import { maskSecretsJson } from "@/lib/format";
import { ArrowUp, Brain, ChevronDown, Cpu, FileSearch, FileText, FolderOpen, ListChecks, RefreshCw, ShieldAlert, Square, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
} from "@/components/ui/combobox";
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
  const configuredModel = useAppStore((s) => s.modelName);
  const setSessionId = useAppStore((s) => s.setSessionId);
  const bumpSessions = useAppStore((s) => s.bumpSessions);

  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [pending, setPending] = useState<PendingConfirmation | null>(null);
  const [plan, setPlan] = useState<PlanStep[]>([]);
  const [contextUsage, setContextUsage] = useState<{ used: number; total: number; percent: number } | null>(null);
  const [showJump, setShowJump] = useState(false);
  const [autoReport, setAutoReport] = useState<{ date: string; text: string } | null>(null);
  const [reportDismissed, setReportDismissed] = useState(false);
  const [thinkingLevel, setThinkingLevel] = useState<ThinkingLevel>("auto");
  const [selectedModel, setSelectedModel] = useState(configuredModel);
  const [modelOptions, setModelOptions] = useState<string[]>(configuredModel ? [configuredModel] : []);
  const [modelsOpen, setModelsOpen] = useState(false);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelsLoaded, setModelsLoaded] = useState(false);
  const [modelLoadError, setModelLoadError] = useState("");
  const configuredModelRef = useRef(configuredModel);
  const modelLoadRequestRef = useRef(0);

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

  useEffect(() => {
    if (configuredModelRef.current === configuredModel) return;
    configuredModelRef.current = configuredModel;
    modelLoadRequestRef.current += 1;
    setModelsLoading(false);
    setModelsOpen(false);
    setSelectedModel(configuredModel);
    setModelOptions(configuredModel ? [configuredModel] : []);
    setModelsLoaded(false);
    setModelLoadError("");
  }, [configuredModel]);

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
  const sessionLoadRequestRef = useRef(0);

  useEffect(() => () => {
    modelLoadRequestRef.current += 1;
    sessionLoadRequestRef.current += 1;
  }, []);

  /**
   * 读取当前 owner 的 Provider 模型目录；地址、协议和 key 仍由服务端从已保存配置解析。
   * 模型列表只用于本地选择，不会修改设置页的默认模型。
   */
  async function loadModels() {
    if (modelsLoading) return;
    const request = ++modelLoadRequestRef.current;
    const requestedModel = selectedModel;
    setModelsLoading(true);
    setModelLoadError("");
    try {
      const cfg = await getConfig();
      const llm = cfg.llm;
      if (!llm) throw new Error("尚未配置聊天模型");
      const result = await listModels({ type: llm.type, base_url: llm.base_url, api_key: "" });
      if (request !== modelLoadRequestRef.current) return;
      if (!result.ok || !result.models?.length) {
        throw new Error(result.error || "当前服务商没有返回可用模型");
      }
      setModelOptions((current) => Array.from(new Set([
        requestedModel || llm.model,
        ...current,
        ...result.models!,
      ].filter(Boolean))));
      setModelsLoaded(true);
    } catch (error) {
      if (request === modelLoadRequestRef.current) {
        setModelLoadError(error instanceof Error ? error.message : String(error));
      }
    } finally {
      if (request === modelLoadRequestRef.current) setModelsLoading(false);
    }
  }

  /** 切换会话时读取目标历史；其他 session 的流继续在后台运行。 */
  async function loadSession(sid: string) {
    const request = ++sessionLoadRequestRef.current;
    try {
      const r = await getSession(sid) as {
        meta?: { pending_confirmation?: PendingConfirmation | null };
        messages?: { role: string; content: string; reasoning?: string; tool?: string; context_source?: string; context_kind?: string; arguments?: Record<string, unknown>; output?: string; parsed?: Record<string, unknown> }[];
      };
      if (request !== sessionLoadRequestRef.current || sidRef.current !== sid) return;
      const msgs: Message[] = (r.messages || [])
        .filter((m) => ["user", "assistant", "tool_call", "context"].includes(m.role))
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
          if (m.role === "context") {
            return {
              type: "context" as const,
              source: m.context_source || "context",
              contextKind: m.context_kind || "context",
              content: m.content || "",
            };
          }
          return {
            type: m.role as "user" | "assistant",
            content: m.content || "",
            ...(m.reasoning ? { reasoning: m.reasoning } : {}),
          };
        });
      setMessages(msgs);
      setPending(r.meta?.pending_confirmation || null);
      setPlan([]);
      setContextUsage(null);
    } catch (error) {
      if (request === sessionLoadRequestRef.current && sidRef.current === sid) {
        emitToast({ kind: "error", text: `会话加载失败：${String(error)}` });
      }
    }
  }

  const { send, stop, busy } = useChatStream({
    messages,
    sessionId,
    sessionIdRef: sidRef,
    setMessages,
    setPending,
    setPlan,
    setContextUsage,
    setSessionId,
    bumpSessions,
    onReconcile(sid) {
      void loadSession(sid);
    },
    onFinish() {
      bottomRef.current?.scrollIntoView({ behavior: "smooth" });
    },
  });

  // 封装发送：无显式消息时清空输入框并复位高度（原 send() 的内联行为）。
  async function handleSend(
    message?: string,
    confirmations: Record<string, unknown>[] = [],
    selectedThinkingLevel: ThinkingLevel = thinkingLevel,
    selectedModelId: string = selectedModel,
  ) {
    const msg = message ?? input.trim();
    if (!msg || busy) return;
    if (!message) {
      setInput("");
      if (taRef.current) taRef.current.style.height = "auto";
    }
    await send(msg, confirmations, selectedThinkingLevel, selectedModelId);
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
    // 会话区用 CSS hidden 保持 ChatPanel 挂载，避免切换到文件/任务页时丢失正在进行的流和工具步骤。
    const h = () => {
      if (sessionId) loadSession(sessionId);
    };
    window.addEventListener(EV.refresh, h);
    return () => window.removeEventListener(EV.refresh, h);
  }, [sessionId]);

  useEffect(() => {
    if (sessionId !== sidRef.current) {
      sidRef.current = sessionId;
      setPending(null);
      if (sessionId) {
        setMessages([]);
        setPlan([]);
        setContextUsage(null);
        void loadSession(sessionId);
      } else {
        setMessages([]);
        setPlan([]);
        setContextUsage(null);
      }
    }
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
      selectedModel,
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
          if (m.type === "context") return <ContextInjection key={i} source={m.source || "context"} content={m.content} />;
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

      <div data-testid="chat-input-bar" className="input-bar-safe shrink-0 bg-panel px-4 py-2 sm:px-6 sm:py-2">
        <div className="mx-auto max-w-4xl">
           <div
             data-testid="chat-composer"
             className="overflow-hidden rounded-md border border-border bg-panel shadow-sm transition-[border-color,box-shadow] duration-150 has-[[data-slot=chat-input]:focus]:border-accent has-[[data-slot=chat-input]:focus]:ring-2 has-[[data-slot=chat-input]:focus]:ring-accent/10"
           >
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-card/60 px-3 py-1.5">
              <div className="flex min-w-0 flex-wrap items-center gap-2">
                <div className="flex min-w-0 items-center gap-1.5">
                  <Cpu className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
                  <span className="shrink-0 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted">模型</span>
                  <Combobox
                    value={selectedModel}
                    onValueChange={(value) => setSelectedModel(value == null ? "" : String(value))}
                    inputValue={selectedModel}
                    onInputValueChange={setSelectedModel}
                    open={modelsOpen}
                    onOpenChange={(open) => {
                      setModelsOpen(open);
                      if (open && !modelsLoaded && !modelsLoading) void loadModels();
                    }}
                    items={modelOptions.map((model) => ({ value: model, label: model }))}
                  >
                    <ComboboxInput
                      aria-label="聊天模型"
                      placeholder="选择模型"
                      className="h-6 min-w-[9rem] max-w-[min(42vw,15rem)] border-transparent bg-panel text-xs shadow-none [&>input]:h-6 [&>input]:text-xs"
                      showClear
                      disabled={busy}
                      onFocus={() => {
                        if (!modelsLoaded && !modelsLoading) void loadModels();
                      }}
                    />
                    <ComboboxContent side="top" className="min-w-[min(16rem,calc(100vw-2rem))]">
                      <ComboboxList>
                        {(item) => <ComboboxItem key={String(item.value)} value={String(item.value)}>{String(item.label)}</ComboboxItem>}
                      </ComboboxList>
                      <ComboboxEmpty>{modelsLoading ? "获取中…" : modelLoadError || "暂无可用模型"}</ComboboxEmpty>
                    </ComboboxContent>
                  </Combobox>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    className="size-6 shrink-0 text-muted hover:bg-panel hover:text-text"
                    aria-label="刷新聊天模型"
                    title="刷新模型列表"
                    onClick={() => void loadModels()}
                    disabled={busy || modelsLoading}
                  >
                    <RefreshCw className={modelsLoading ? "animate-spin" : undefined} />
                  </Button>
                </div>
                <div className="flex items-center gap-2">
                  <Brain className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
                  <span className="shrink-0 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted">推理层级</span>
                  <Select
                    value={thinkingLevel}
                    onValueChange={(value) => { if (isThinkingLevel(value)) setThinkingLevel(value); }}
                    disabled={busy}
                  >
                    <SelectTrigger size="sm" aria-label="思考等级" className="h-6 min-w-[104px] rounded-md border-transparent bg-panel text-xs shadow-none">
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
              </div>
              <span className="hidden shrink-0 font-mono text-[10px] text-muted sm:inline">{busy ? "STREAMING" : "READY"}</span>
            </div>
            <div className="flex items-end gap-1.5 px-3 py-2">
              <textarea
                ref={taRef}
                data-slot="chat-input"
                value={input}
                rows={1}
                onChange={(e) => { setInput(e.target.value); autoGrow(); }}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); handleSend(); }
                }}
                placeholder="和你的 Agent 对话…"
                className="max-h-40 flex-1 resize-none bg-transparent px-1 py-0.5 text-sm leading-relaxed text-text outline-none placeholder:text-muted focus:ring-0"
              />
              {busy ? (
                <Button variant="destructive" onClick={stop}><Square className="size-3.5" /> 停止</Button>
              ) : (
                <Button className="h-8 min-w-14" onClick={() => handleSend()} disabled={!input.trim()}>
                  {input.trim() ? "发送" : <ArrowUp className="size-4" />}
                </Button>
              )}
            </div>
          </div>
          <div className="mt-1.5 flex flex-wrap justify-center gap-1.5">
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
