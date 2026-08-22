"use client";
import { useEffect, useLayoutEffect, useRef, useState, type ClipboardEvent } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api } from "@/lib/api/client";
import { getConfig, listModels } from "@/lib/api/config";
import { listFiles, uploadFile, type FileItem } from "@/lib/api/files";
import { getSession } from "@/lib/api/sessions";
import { EV, emitToast } from "@/lib/events";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ToolStep } from "./ToolStep";
import { ContextInjection } from "./ContextInjection";
import { ContextBar } from "./ContextBar";
import { PermissionControl } from "./PermissionControl";
import { PlanCard, PlanStep } from "./PlanCard";
import { useAppStore } from "@/lib/store";
import { maskSecretsJson } from "@/lib/format";
import { isSafeFrontendPath } from "@/lib/frontend-actions";
import { supportsInlineImages } from "@/lib/model-capabilities";
import { ArrowDown, ArrowUp, AtSign, Brain, ChevronDown, ChevronRight, CornerUpLeft, Cpu, FileSearch, FileText, FolderOpen, FolderPlus, ListChecks, Loader2, PanelLeftOpen, Paperclip, Plus, RefreshCw, ShieldAlert, Square, X } from "lucide-react";
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
import type { ContextUsage, InlineImage, Message, PendingConfirmation, PermissionMode, ThinkingLevel } from "./useChatStream";

export { chatTextDelta };

function normalizeFileReferenceMarkdown(value: string) {
  return value.replace(/\[\[(file|folder):([^\]\n]+)\]\]/g, (_match, kind: string, path: string) => {
    const encoded = encodeURIComponent(path.trim());
    return `[${path.trim()}](https://agent-drive.local/file?kind=${kind}&path=${encoded})`;
  });
}

/** 将模型声明的文件引用转换为已经登记的前端打开动作。 */
function AssistantMarkdown({ content }: { content: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        a: ({ href, children }) => {
          let reference: { kind: "file" | "folder"; path: string } | null = null;
          if (href) {
            try {
              const url = new URL(href);
              const kind = url.origin === "https://agent-drive.local" && url.pathname === "/file"
                ? url.searchParams.get("kind")
                : null;
              const path = url.searchParams.get("path");
              if ((kind === "file" || kind === "folder") && path) {
                reference = { kind, path };
              }
            } catch {
              // Markdown may contain a relative or malformed external href.
            }
          }
          const isFileReference = reference?.kind === "file";
          const isFolderReference = reference?.kind === "folder";
          if (reference && (isFileReference || isFolderReference)) {
            const path = reference.path;
            const safe = isSafeFrontendPath(path);
            if (!safe) return <span>{children}</span>;
            const operation = isFileReference ? "files.open" : "files.open_folder";
            return (
              <button
                type="button"
                aria-label={isFileReference ? `打开文件 ${path}` : `打开文件夹 ${path}`}
                className="mx-0.5 inline-flex max-w-full items-center gap-1 rounded-sm border border-border bg-card px-1.5 py-0.5 text-left text-accent underline-offset-2 hover:bg-accent-soft hover:underline"
                onClick={() => useAppStore.getState().enqueueFrontendAction({
                  operation,
                  arguments: { path },
                  targetTab: "files",
                  summary: isFileReference ? `打开文件 ${path}` : `打开文件夹 ${path}`,
                })}
              >
                {isFileReference ? <FileText className="size-3 shrink-0" aria-hidden="true" /> : <FolderOpen className="size-3 shrink-0" aria-hidden="true" />}
                <span className="truncate">{children}</span>
              </button>
            );
          }
          return <a href={href}>{children}</a>;
        },
      }}
    >
      {normalizeFileReferenceMarkdown(content)}
    </ReactMarkdown>
  );
}

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

const DEFAULT_CONTEXT_USAGE: ContextUsage = { used: 0, total: 262144, percent: 0 };
const PERMISSION_STORAGE_KEY = "agent-drive-permission-mode";

function finiteNumber(value: unknown): number | null {
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? number : null;
}

function normalizeContextUsage(value: unknown): ContextUsage | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as Record<string, unknown>;
  const used = finiteNumber(raw.used);
  const total = finiteNumber(raw.total);
  if (used === null || total === null || total <= 0) return null;
  const percent = finiteNumber(raw.percent) ?? (used * 100) / total;
  const input = finiteNumber(raw.input);
  const output = finiteNumber(raw.output);
  return {
    used: Math.max(0, used),
    total,
    percent,
    ...(input !== null && input > 0 ? { input } : {}),
    ...(output !== null && output > 0 ? { output } : {}),
    ...(raw.estimated === true ? { estimated: true } : {}),
  };
}

function estimateContextUsage(messages: Message[]): ContextUsage | null {
  if (messages.length === 0) return null;
  const characters = messages.reduce((total, message) => total
    + message.content.length
    + (message.reasoning?.length || 0)
    + JSON.stringify(message.arguments || {}).length
    + (message.output?.length || 0)
    + JSON.stringify(message.parsed || {}).length, 0);
  if (characters <= 0) return null;
  const used = Math.max(1, Math.ceil((characters + messages.length * 4) / 4));
  const total = DEFAULT_CONTEXT_USAGE.total;
  return { used, total, percent: (used * 100) / total, estimated: true };
}

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

interface ChatPanelProps {
  /** 在手机端打开会话抽屉；桌面端不显示该入口。 */
  onOpenSessions?: () => void;
  /** 清空当前会话并创建下一轮新会话。 */
  onNewSession?: () => void;
}

export default function ChatPanel({ onOpenSessions, onNewSession }: ChatPanelProps = {}) {
  const sessionId = useAppStore((s) => s.sessionId);
  const configuredModel = useAppStore((s) => s.modelName);
  const setSessionId = useAppStore((s) => s.setSessionId);
  const bumpSessions = useAppStore((s) => s.bumpSessions);

  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [pending, setPending] = useState<PendingConfirmation | null>(null);
  const [plan, setPlan] = useState<PlanStep[]>([]);
  const [contextUsage, setContextUsage] = useState<ContextUsage | null>(null);
  const [showJump, setShowJump] = useState(false);
  const [autoReport, setAutoReport] = useState<{ date: string; text: string } | null>(null);
  const [reportDismissed, setReportDismissed] = useState(false);
  const [thinkingLevel, setThinkingLevel] = useState<ThinkingLevel>("auto");
  const [permissionMode, setPermissionMode] = useState<PermissionMode>("auto");
  const [selectedModel, setSelectedModel] = useState(configuredModel);
  const [providerType, setProviderType] = useState("");
  const [configuredSupportsImages, setConfiguredSupportsImages] = useState<boolean | undefined>(undefined);
  const [modelCapabilities, setModelCapabilities] = useState<Record<string, boolean>>({});
  const [modelOptions, setModelOptions] = useState<string[]>(configuredModel ? [configuredModel] : []);
  const [modelsOpen, setModelsOpen] = useState(false);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [modelsLoaded, setModelsLoaded] = useState(false);
  const [modelLoadError, setModelLoadError] = useState("");
  const [fileContext, setFileContext] = useState<FileItem[]>([]);
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [mentionItems, setMentionItems] = useState<FileItem[]>([]);
  const [mentionLoading, setMentionLoading] = useState(false);
  const [mentionBrowsePath, setMentionBrowsePath] = useState<string | null>(null);
  const [mentionBrowseStack, setMentionBrowseStack] = useState<Array<{ path: string | null; query: string }>>([]);
  const [attachmentBusy, setAttachmentBusy] = useState(false);
  const [inlineImages, setInlineImages] = useState<InlineImage[]>([]);
  const configuredModelRef = useRef(configuredModel);
  const modelLoadRequestRef = useRef(0);
  const mentionRequestRef = useRef(0);
  const attachmentInputRef = useRef<HTMLInputElement>(null);
  const modelSupportsImages = modelCapabilities[selectedModel]
    ?? supportsInlineImages(providerType, selectedModel,
      selectedModel === configuredModel ? configuredSupportsImages : undefined);

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

  useEffect(() => {
    let active = true;
    void getConfig().then((cfg) => {
      if (!active) return;
      const llm = cfg.llm;
      setProviderType(llm?.type || "");
      setConfiguredSupportsImages(llm?.supports_images);
    }).catch(() => {
      // The model-name fallback still prevents sending images to unknown models.
    });
    return () => { active = false; };
  }, [configuredModel]);

  // 只在输入末尾形成 @token 时查询文件目录，避免普通聊天输入触发递归搜索。
  useEffect(() => {
    if (mentionQuery === null) {
      setMentionItems([]);
      setMentionLoading(false);
      return;
    }
    const request = ++mentionRequestRef.current;
    let active = true;
    const timer = window.setTimeout(async () => {
      setMentionLoading(true);
      try {
        const result = mentionBrowsePath === null
          ? await listFiles("", mentionQuery, "name")
          : await listFiles(mentionBrowsePath, "", "name");
        if (active && request === mentionRequestRef.current) setMentionItems(result.items.slice(0, 16));
      } catch {
        if (active && request === mentionRequestRef.current) setMentionItems([]);
      } finally {
        if (active && request === mentionRequestRef.current) setMentionLoading(false);
      }
    }, 180);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [mentionQuery, mentionBrowsePath]);

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
  const sidRef = useRef<string | null>(null);
  const sessionLoadRequestRef = useRef(0);
  const autoScrollRef = useRef(true);

  useEffect(() => () => {
    modelLoadRequestRef.current += 1;
    sessionLoadRequestRef.current += 1;
  }, []);

  useEffect(() => {
    try {
      const saved = localStorage.getItem(PERMISSION_STORAGE_KEY);
      if (saved === "ask" || saved === "auto" || saved === "full") setPermissionMode(saved);
    } catch { /* 忽略 */ }
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem(PERMISSION_STORAGE_KEY, permissionMode);
    } catch { /* 忽略 */ }
  }, [permissionMode]);

  // 切换到完全访问时，收起此前 ask/auto 轮次遗留的确认卡；下一轮按 full 直接执行。
  useEffect(() => {
    if (permissionMode === "full") setPending(null);
  }, [permissionMode]);

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
      setProviderType(llm.type);
      setConfiguredSupportsImages(llm.supports_images);
      setModelCapabilities({});
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
      setModelCapabilities(result.model_capabilities || {});
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
        meta?: { pending_confirmation?: PendingConfirmation | null; context_usage?: unknown };
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
      autoScrollRef.current = true;
      setMessages(msgs);
      setPending(r.meta?.pending_confirmation || null);
      setPlan([]);
      setContextUsage(normalizeContextUsage(r.meta?.context_usage) ?? estimateContextUsage(msgs));
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

  function scrollToLatest(behavior: ScrollBehavior = "auto") {
    const list = listRef.current;
    if (!list) return;
    list.scrollTop = list.scrollHeight;
    bottomRef.current?.scrollIntoView({ behavior, block: "end" });
    setShowJump((current) => current ? false : current);
  }

  // 历史加载和流式事件都会改变消息高度；运行中持续跟随底部，避免用户手动追流。
  useLayoutEffect(() => {
    if (busy) autoScrollRef.current = true;
    if ((busy || autoScrollRef.current) && (messages.length > 0 || busy)) {
      scrollToLatest("auto");
    }
  }, [busy, messages, plan, sessionId]);

  // 封装发送：无显式消息时清空输入框并复位高度（原 send() 的内联行为）。
  async function handleSend(
    message?: string,
    confirmations: Record<string, unknown>[] = [],
    selectedThinkingLevel: ThinkingLevel = thinkingLevel,
    selectedModelId: string = selectedModel,
    selectedPermissionMode: PermissionMode = permissionMode,
  ) {
    const typedMessage = message ?? input.trim();
    const msg = typedMessage || (inlineImages.length > 0 ? "请分析这张图片" : "");
    if (!msg || busy) return;
    if (inlineImages.length > 0 && !modelSupportsImages) {
      emitToast({ kind: "error", text: `当前模型「${selectedModel || "未选择"}」不支持图片输入，请切换支持视觉的模型后再发送。` });
      return;
    }
    autoScrollRef.current = true;
    const attachedPaths = fileContext.map((entry) => entry.path);
    const attachedInlineImages = inlineImages.map(({ name, mediaType, data }) => ({ name, mediaType, data }));
    if (!message) {
      setInput("");
      if (taRef.current) taRef.current.style.height = "auto";
    }
    closeMentionPicker();
    setFileContext([]);
    setInlineImages([]);
    await send(msg, confirmations, selectedThinkingLevel, selectedModelId, attachedPaths, selectedPermissionMode, attachedInlineImages);
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
      const preserveOptimisticRun = sidRef.current === null
        && messages.some((message) => message.type === "user");
      sidRef.current = sessionId;
      autoScrollRef.current = true;
      setPending(null);
      setFileContext([]);
      closeMentionPicker();
      if (sessionId && preserveOptimisticRun) return;
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
  }, [sessionId, messages]);

  function onScroll() {
    const el = listRef.current;
    if (!el) return;
    const awayFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight > 200;
    if (!busy) autoScrollRef.current = !awayFromBottom;
    setShowJump(awayFromBottom && !busy);
  }

  function jumpBottom() {
    autoScrollRef.current = true;
    scrollToLatest("smooth");
  }

  function autoGrow() {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = Math.min(ta.scrollHeight, 160) + "px";
  }

  function updateMentionQuery(value: string) {
    const match = value.match(/(?:^|\s)@([^\s@]*)$/);
    if (!match) {
      closeMentionPicker();
      return;
    }
    if (mentionBrowsePath !== null) {
      const at = value.lastIndexOf("@");
      const folderPrefix = `@${mentionBrowsePath}/`;
      if (at < 0 || !value.slice(at).startsWith(folderPrefix)) {
        setMentionBrowsePath(null);
        setMentionBrowseStack([]);
      }
    }
    setMentionQuery(match[1]);
  }

  function closeMentionPicker() {
    mentionRequestRef.current += 1;
    setMentionQuery(null);
    setMentionBrowsePath(null);
    setMentionBrowseStack([]);
  }

  function replaceMentionToken(path: string, suffix: string) {
    const at = input.lastIndexOf("@");
    if (at < 0) return false;
    setInput(`${input.slice(0, at)}@${path}${suffix}`);
    return true;
  }

  function chooseMention(item: FileItem) {
    if (!replaceMentionToken(item.path, " ")) return;
    closeMentionPicker();
    setFileContext((current) => current.some((entry) => entry.path === item.path)
      ? current
      : [...current, item].slice(-16));
    window.requestAnimationFrame(() => taRef.current?.focus());
  }

  function enterMentionFolder(item: FileItem) {
    const folderPath = item.path.replace(/\/+$/, "");
    if (!replaceMentionToken(folderPath, "/")) return;
    setMentionBrowseStack((current) => [...current, { path: mentionBrowsePath, query: mentionQuery || "" }]);
    setMentionBrowsePath(folderPath);
    setMentionQuery("");
    window.requestAnimationFrame(() => taRef.current?.focus());
  }

  function goBackMentionFolder() {
    if (mentionBrowseStack.length === 0) {
      closeMentionPicker();
      return;
    }
    const previous = mentionBrowseStack[mentionBrowseStack.length - 1];
    setMentionBrowseStack((current) => current.slice(0, -1));
    setMentionBrowsePath(previous.path);
    setMentionQuery(previous.query);
    const at = input.lastIndexOf("@");
    if (at >= 0) setInput(`${input.slice(0, at)}@${previous.path ? `${previous.path}/` : ""}${previous.query}`);
    window.requestAnimationFrame(() => taRef.current?.focus());
  }

  function quoteCurrentMentionFolder() {
    if (!mentionBrowsePath) return;
    chooseMention({ name: mentionBrowsePath.split("/").pop() || mentionBrowsePath, path: mentionBrowsePath, is_dir: true, size: 0 });
  }

  function normalizePastedImage(file: File, index: number) {
    if (file.name) return file;
    const extension = file.type.split("/")[1]?.split(";")[0] || "png";
    return new File([file], `粘贴图片-${Date.now()}-${index + 1}.${extension}`, {
      type: file.type || "image/png",
      lastModified: file.lastModified || Date.now(),
    });
  }

  function insertPastedText(text: string) {
    if (!text) return;
    const textarea = taRef.current;
    const start = textarea?.selectionStart ?? input.length;
    const end = textarea?.selectionEnd ?? input.length;
    const next = `${input.slice(0, start)}${text}${input.slice(end)}`;
    setInput(next);
    updateMentionQuery(next);
    window.requestAnimationFrame(() => {
      if (!textarea) return;
      const cursor = start + text.length;
      textarea.setSelectionRange(cursor, cursor);
      autoGrow();
    });
  }

  function handlePaste(event: ClipboardEvent<HTMLTextAreaElement>) {
    const clipboard = event.clipboardData;
    if (!clipboard || busy) return;
    const fromItems = Array.from(clipboard.items || [])
      .filter((item) => item.kind === "file" && item.type.startsWith("image/"))
      .map((item) => item.getAsFile())
      .filter((file): file is File => Boolean(file));
    const fromFiles = Array.from(clipboard.files || []).filter((file) => file.type.startsWith("image/"));
    const images = Array.from(new Map([...fromItems, ...fromFiles].map((file, index) => [
      `${file.name}|${file.size}|${file.type}|${file.lastModified}`,
      normalizePastedImage(file, index),
    ])).values());
    if (images.length === 0) return;
    event.preventDefault();
    insertPastedText(clipboard.getData("text/plain"));
    if (!modelSupportsImages) {
      emitToast({ kind: "error", text: `当前模型「${selectedModel || "未选择"}」不支持图片输入，请切换支持视觉的模型后再粘贴。` });
      return;
    }
    void addInlineImages(images);
  }

  /** 将剪贴板图片读入当前页面内存；不调用文件上传接口，也不创建聊天附件路径。 */
  async function addInlineImages(files: File[]) {
    const remaining = Math.max(0, 4 - inlineImages.length);
    if (remaining === 0) {
      emitToast({ kind: "error", text: "一轮最多附加 4 张图片" });
      return;
    }
    const prepared: InlineImage[] = [];
    for (const file of files.slice(0, remaining)) {
      if (file.size > 4 * 1024 * 1024) {
        emitToast({ kind: "error", text: `图片过大：${file.name}（单张上限 4 MiB）` });
        continue;
      }
      try {
        const dataUrl = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => typeof reader.result === "string" ? resolve(reader.result) : reject(new Error("图片读取失败"));
          reader.onerror = () => reject(reader.error || new Error("图片读取失败"));
          reader.readAsDataURL(file);
        });
        const comma = dataUrl.indexOf(",");
        if (comma < 0) throw new Error("图片编码格式无效");
        prepared.push({
          id: `${Date.now()}-${prepared.length}-${Math.random().toString(36).slice(2, 8)}`,
          name: file.name || "粘贴图片.png",
          mediaType: file.type || "image/png",
          data: dataUrl.slice(comma + 1),
          previewUrl: dataUrl,
          size: file.size,
        });
      } catch (error) {
        emitToast({ kind: "error", text: `图片读取失败：${file.name || "粘贴图片"}：${String(error)}` });
      }
    }
    if (prepared.length > 0) {
      setInlineImages((current) => [...current, ...prepared].slice(-4));
      emitToast({ kind: "ok", text: `已附加 ${prepared.length} 张图片（仅本轮发送，不会上传到网盘）` });
    }
  }

  async function uploadAttachments(files: File[]) {
    if (files.length === 0) return;
    setAttachmentBusy(true);
    for (const file of files) {
      try {
        const result = await uploadFile(file, "聊天附件");
        const path = result.uploaded.path;
        setFileContext((current) => current.some((entry) => entry.path === path)
          ? current
          : [...current, { name: file.name, path, is_dir: false, size: file.size }].slice(-16));
        emitToast({ kind: "ok", text: `已添加附件：${file.name}` });
      } catch (error) {
        emitToast({ kind: "error", text: `附件上传失败：${file.name}：${String(error)}` });
      }
    }
    setAttachmentBusy(false);
  }

  function removeFileContext(path: string) {
    setFileContext((current) => current.filter((entry) => entry.path !== path));
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
    setMessages((m) => [...m, { type: "user", content: `我批准执行：${pending.tool}` }]);
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
      <div className="flex h-11 shrink-0 items-center justify-between border-b border-border bg-panel px-3 xl:hidden">
        <Button type="button" variant="ghost" size="sm" className="gap-1.5 px-2 text-xs" onClick={onOpenSessions} aria-label="打开会话列表">
          <PanelLeftOpen className="size-4" aria-hidden="true" />
          <span>会话</span>
        </Button>
        <Button type="button" variant="outline" size="sm" className="h-8 gap-1.5 px-2 text-xs" onClick={onNewSession ?? (() => setSessionId(null))} aria-label="新会话">
          <Plus className="size-3.5" aria-hidden="true" />
          <span>新会话</span>
        </Button>
      </div>
      <div data-testid="chat-message-list" ref={listRef} onScroll={onScroll} className="flex-1 overflow-y-auto px-4 pb-8 pt-6 sm:px-6">
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
                      <div className="markdown-body"><AssistantMarkdown content={m.content} /></div>
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
              <span className="flex items-center gap-1.5"><ShieldAlert className="size-3.5" /> 需要你的批准</span>
            </div>
            <div className="space-y-3 p-4">
              <div className="text-sm leading-relaxed text-text">
                Agent 请求执行：<code className="bg-danger-soft px-1.5 py-0.5 font-mono text-xs text-danger">{pending.tool}</code>{" "}
                <code className="break-all bg-danger-soft px-1.5 py-0.5 font-mono text-xs text-danger">{maskSecretsJson(pending.arguments)}</code>
                <div className="mt-2 text-xs text-muted">此操作会改变数据或调用外部服务，请确认目标和参数后继续。</div>
              </div>
              <div className="flex gap-2 border-t border-border pt-3">
                <Button variant="default" onClick={confirmYes}>批准并执行</Button>
                <Button variant="outline" onClick={confirmNo}>取消</Button>
              </div>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
        </div>
      </div>

      {plan.length > 0 && <div className="px-4 pb-2 sm:px-6"><div className="mx-auto max-w-4xl"><PlanCard plan={plan} /></div></div>}

      <div data-testid="chat-input-bar" className="input-bar-safe shrink-0 bg-panel px-4 py-2 sm:px-6 sm:py-2">
        <div className="mx-auto max-w-4xl">
          <div className="relative">
          {mentionQuery !== null && (
            <div role="listbox" aria-label="文件引用候选" className="absolute bottom-full left-0 z-20 mb-2 max-h-64 w-full overflow-auto rounded-md border border-border bg-panel p-1 shadow-lg">
              {mentionBrowsePath !== null ? (
                <div className="flex items-center gap-1 border-b border-border px-1 pb-1.5">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    className="size-7 shrink-0 text-muted"
                    aria-label={mentionBrowseStack.length > 0 ? "返回上一级" : "返回文件引用搜索"}
                    title={mentionBrowseStack.length > 0 ? "返回上一级" : "返回搜索结果"}
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={goBackMentionFolder}
                  >
                    <CornerUpLeft className="size-3.5" aria-hidden="true" />
                  </Button>
                  <div className="min-w-0 flex-1 px-1">
                    <div className="text-[10px] font-semibold text-muted">选择文件</div>
                    <div className="truncate text-xs text-text" title={mentionBrowsePath}>{mentionBrowsePath}</div>
                  </div>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="h-7 shrink-0 gap-1 px-2 text-[11px] text-muted hover:text-text"
                    aria-label={`引用文件夹 ${mentionBrowsePath}`}
                    title="引用整个文件夹"
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={quoteCurrentMentionFolder}
                  >
                    <FolderPlus className="size-3.5" aria-hidden="true" />
                    <span>引用文件夹</span>
                  </Button>
                </div>
              ) : (
                <div className="flex items-center gap-2 border-b border-border px-2 pb-1.5 text-[10px] text-muted">
                  <AtSign className="size-3.5 shrink-0" aria-hidden="true" />
                  <span>选择文件，或进入文件夹继续浏览</span>
                </div>
              )}
              {mentionLoading && <div className="flex items-center gap-2 px-3 py-2 text-xs text-muted"><Loader2 className="size-3.5 animate-spin" /> 搜索文件…</div>}
              {!mentionLoading && mentionItems.length === 0 && <div className="px-3 py-2 text-xs text-muted">{mentionBrowsePath !== null ? "文件夹为空" : "没有匹配的文件或文件夹"}</div>}
              {!mentionLoading && mentionItems.map((item) => (
                item.is_dir ? (
                  <div key={item.path} role="option" aria-selected="false" aria-label={`文件夹 ${item.path}`} className="flex items-center gap-1 rounded-sm px-1 hover:bg-card">
                    <button
                      type="button"
                      className="flex min-w-0 flex-1 items-center gap-2 px-2 py-2 text-left text-xs"
                      aria-label={`进入文件夹 ${item.path}`}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => enterMentionFolder(item)}
                    >
                      <FolderOpen className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
                      <span className="min-w-0 flex-1 truncate">{item.path}</span>
                      <span className="shrink-0 text-[10px] text-muted">文件夹</span>
                      <ChevronRight className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      className="shrink-0 rounded-sm p-1.5 text-muted hover:bg-panel hover:text-text"
                      aria-label={`引用文件夹 ${item.path}`}
                      title="引用整个文件夹"
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => chooseMention(item)}
                    >
                      <FolderPlus className="size-3.5" aria-hidden="true" />
                    </button>
                  </div>
                ) : (
                  <button key={item.path} type="button" role="option" aria-selected="false" className="flex w-full items-center gap-2 rounded-sm px-3 py-2 text-left text-xs hover:bg-card" onMouseDown={(event) => event.preventDefault()} onClick={() => chooseMention(item)}>
                    <FileText className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
                    <span className="min-w-0 flex-1 truncate">{item.path}</span>
                    <span className="shrink-0 text-[10px] text-muted">文件</span>
                  </button>
                )
              ))}
            </div>
          )}
           <div
             data-testid="chat-composer"
             className="overflow-visible rounded-md border border-border bg-panel shadow-sm transition-[border-color,box-shadow] duration-150 has-[[data-slot=chat-input]:focus]:border-accent has-[[data-slot=chat-input]:focus]:ring-2 has-[[data-slot=chat-input]:focus]:ring-accent/10"
           >
            <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1 border-b border-border bg-card/60 px-3 py-1.5">
              <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2 gap-y-1 lg:flex-nowrap">
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
                <PermissionControl value={permissionMode} onChange={setPermissionMode} disabled={busy} />
                <ContextBar usage={contextUsage ?? DEFAULT_CONTEXT_USAGE} />
              </div>
              <span className="hidden shrink-0 font-mono text-[10px] text-muted lg:inline">{busy ? "STREAMING" : "READY"}</span>
            </div>
            {fileContext.length > 0 && (
              <div className="flex flex-wrap gap-1.5 border-b border-border px-3 py-2" aria-label="已附加文件">
                {fileContext.map((item) => (
                  <span key={item.path} className="inline-flex max-w-full items-center gap-1 rounded-sm border border-border bg-card px-2 py-1 text-[11px] text-muted">
                    {item.is_dir ? <FolderOpen className="size-3 shrink-0" aria-hidden="true" /> : <FileText className="size-3 shrink-0" aria-hidden="true" />}
                    <span className="max-w-[min(60vw,20rem)] truncate">{item.path}</span>
                    <button type="button" className="shrink-0 rounded-sm p-0.5 hover:bg-panel hover:text-text" aria-label={`移除附件 ${item.path}`} onClick={() => removeFileContext(item.path)}><X className="size-3" /></button>
                  </span>
                ))}
              </div>
            )}
            {inlineImages.length > 0 && (
              <div className="flex flex-wrap gap-2 border-b border-border px-3 py-2" aria-label="已附加图片">
                {inlineImages.map((image) => (
                  <span key={image.id} className="relative inline-flex items-center gap-1.5 rounded-md border border-border bg-card p-1.5 text-[11px] text-muted">
                    <img src={image.previewUrl} alt={image.name} className="size-10 rounded object-cover" />
                    <span className="max-w-32 truncate">{image.name}</span>
                    <button type="button" className="rounded-sm p-0.5 hover:bg-panel hover:text-text" aria-label={`移除图片 ${image.name}`} onClick={() => setInlineImages((current) => current.filter((entry) => entry.id !== image.id))}>
                      <X className="size-3" />
                    </button>
                  </span>
                ))}
              </div>
            )}
            <div className="flex items-end gap-1.5 px-3 py-2">
              <input ref={attachmentInputRef} type="file" multiple className="hidden" onChange={async (event) => {
                const files = Array.from(event.target.files || []);
                event.target.value = "";
                await uploadAttachments(files);
              }} />
              <Button type="button" variant="ghost" size="icon-sm" className="size-8 shrink-0 text-muted" aria-label="添加附件" title="添加附件" disabled={busy || attachmentBusy} onClick={() => attachmentInputRef.current?.click()}>
                {attachmentBusy ? <Loader2 className="size-4 animate-spin" /> : <Paperclip className="size-4" />}
              </Button>
              <Button type="button" variant="ghost" size="icon-sm" className="size-8 shrink-0 text-muted" aria-label="引用文件" title="输入 @ 选择文件" disabled={busy} onClick={() => {
                const next = `${input}${input && !input.endsWith(" ") ? " " : ""}@`;
                setInput(next);
                setMentionBrowsePath(null);
                setMentionBrowseStack([]);
                setMentionQuery("");
                window.requestAnimationFrame(() => taRef.current?.focus());
              }}>
                <AtSign className="size-4" />
              </Button>
              <textarea
                ref={taRef}
                data-slot="chat-input"
                value={input}
                rows={1}
                onChange={(e) => { setInput(e.target.value); updateMentionQuery(e.target.value); autoGrow(); }}
                onPaste={handlePaste}
                onKeyDown={(e) => {
                  if (e.key === "Escape" && mentionQuery !== null) {
                    e.preventDefault();
                    if (mentionBrowsePath !== null) goBackMentionFolder();
                    else closeMentionPicker();
                    return;
                  }
                  if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing && mentionQuery !== null && mentionItems[0]) {
                    e.preventDefault();
                    if (mentionBrowsePath !== null && mentionItems[0].is_dir) enterMentionFolder(mentionItems[0]);
                    else chooseMention(mentionItems[0]);
                    return;
                  }
                  if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); handleSend(); }
                }}
                placeholder="和你的 Agent 对话…"
                className="max-h-40 flex-1 resize-none bg-transparent px-1 py-0.5 text-sm leading-relaxed text-text outline-none placeholder:text-muted focus:ring-0"
              />
              {busy ? (
                <Button variant="destructive" onClick={stop}><Square className="size-3.5" /> 停止</Button>
              ) : (
                <Button className="h-8 min-w-14" onClick={() => handleSend()} disabled={!input.trim() && inlineImages.length === 0}>
                  {input.trim() || inlineImages.length > 0 ? "发送" : <ArrowUp className="size-4" />}
                </Button>
              )}
            </div>
          </div>
          </div>
        </div>
      </div>
      {showJump && (
        <div className="pointer-events-none absolute inset-x-0 bottom-24 z-10 flex justify-center px-4 animate-slide-in sm:bottom-28">
          <Button
            type="button"
            onClick={jumpBottom}
            title="回到最新消息"
            aria-label="回到最新消息"
            variant="outline"
            size="icon-sm"
            className="pointer-events-auto size-8 rounded-full border-border/80 bg-panel/95 text-muted shadow-sm backdrop-blur-sm hover:border-accent hover:bg-panel hover:text-text"
          >
            <ArrowDown className="size-4" aria-hidden="true" />
          </Button>
        </div>
      )}
    </section>
  );
}
