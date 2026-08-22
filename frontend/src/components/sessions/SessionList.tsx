"use client";
import { useEffect, useRef, useState } from "react";
import { MessageSquare, PanelLeftClose, PanelLeftOpen, Plus, Trash2, X } from "lucide-react";
import { listSessions, deleteSession, summarizeSession } from "@/lib/api/sessions";
import { useAppStore } from "@/lib/store";
import { Button } from "@/components/ui/button";
import PanelResizeHandle from "@/components/workspace/PanelResizeHandle";
import { WORKSPACE_PANEL_LIMITS } from "@/lib/workspace-layout";

// 标记跨组件实例共享的标题生成，避免并发 load 为同一空标题会话重复发起写请求。
const summarizingSessions = new Set<string>();

interface SessionListProps {
  collapsed?: boolean;
  width?: number;
  onResize?: (width: number) => void;
  onToggle?: () => void;
  /** 小于 xl 的视口以抽屉形式显示；完整侧栏只在宽桌面承载。 */
  mobileOpen?: boolean;
  onMobileClose?: () => void;
}

export default function SessionList({ collapsed, width = WORKSPACE_PANEL_LIMITS.sessions.defaultWidth, onResize, onToggle, mobileOpen = false, onMobileClose }: SessionListProps) {
  const sessionId = useAppStore((s) => s.sessionId);
  const setSessionId = useAppStore((s) => s.setSessionId);
  const sessionsVersion = useAppStore((s) => s.sessionsVersion);
  const [sessions, setSessions] = useState<{ id: string; title: string; summary?: string }[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [localCollapsed, setLocalCollapsed] = useState(false);
  const loadSequenceRef = useRef(0);
  const mountedRef = useRef(true);
  const isCollapsed = collapsed ?? localCollapsed;
  const toggle = onToggle ?? (() => setLocalCollapsed((value) => !value));

  /**
   * 加载会话并在必要时补齐空标题。每次重拉都必须完成“列表写入 -> 总结 -> 再拉列表”的同一序列，
   * 否则总结请求落库后可能被旧 GET 的响应覆盖，用户会暂时看不到新标题。
   */
  async function load() {
    const sequence = ++loadSequenceRef.current;
    // 两次列表写入都必须留在同一请求序列内，防止旧请求的列表覆盖新列表。
    const isCurrent = () => mountedRef.current && sequence === loadSequenceRef.current;
    setLoading(true);
    setLoadError("");
    try {
      let list = await listSessions();
      if (!isCurrent()) return;
      // 本轮已经总结过但仍无标题的会话：不再重复总结（避免空转），等下一次会话变更再试。
      const attempted = new Set<string>();
      while (true) {
        setSessions(list.sessions);
        const untitled = list.sessions.filter((session) => !session.title?.trim());
        // 正在被其他 load 总结的空标题会话：等其落地后刷新，避免「标题已生成但列表不显示」。
        const pending = untitled.filter((session) => summarizingSessions.has(session.id));
        const toSummarize = untitled.filter(
          (session) => !summarizingSessions.has(session.id) && !attempted.has(session.id),
        );
        if (toSummarize.length === 0 && pending.length === 0) return;

        if (toSummarize.length > 0) {
          toSummarize.forEach((session) => summarizingSessions.add(session.id));
          await Promise.allSettled(toSummarize.map((session) => summarizeSession(session.id)));
          toSummarize.forEach((session) => {
            summarizingSessions.delete(session.id);
            attempted.add(session.id);
          });
        } else {
          await new Promise((resolve) => setTimeout(resolve, 300));
        }
        if (!isCurrent()) return; // 已被更新的 load 取代：让新 load 负责刷新
        // summarize 是写请求（会清 GET 缓存），这里拉到的必然是最新状态。
        list = await listSessions();
        if (!isCurrent()) return; // 响应可能晚于更新的 load 写入，不能覆盖
      }
    } catch (error) {
      if (isCurrent()) setLoadError(error instanceof Error ? error.message : String(error));
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }
  useEffect(() => {
    mountedRef.current = true;
    load();
    return () => { mountedRef.current = false; };
  }, [sessionsVersion]);

  function newSession() {
    setSessionId(null);
    onMobileClose?.();
  }

  async function remove(sid: string, e: React.MouseEvent) {
    e.stopPropagation();
    await deleteSession(sid);
    load();
    if (sessionId === sid) setSessionId(null);
    onMobileClose?.();
  }

  const panelWidth = isCollapsed ? WORKSPACE_PANEL_LIMITS.sessions.collapsedWidth : width;

  return (
    <aside
      data-testid="session-panel"
      aria-label="会话列表"
      style={{ width: panelWidth, minWidth: panelWidth }}
      className={`relative shrink-0 flex-col border-r border-border bg-bg ${mobileOpen ? "fixed inset-y-0 left-0 z-50 flex w-[min(22rem,calc(100vw-1rem))] shadow-2xl" : "hidden xl:flex"}`}
    >
      {mobileOpen && (
        <button
          type="button"
          className="fixed inset-0 -z-10 cursor-default bg-text/20 md:hidden"
          aria-label="关闭会话抽屉"
          onClick={onMobileClose}
        />
      )}
      <PanelResizeHandle
        panel="sessions"
        width={width}
        minWidth={WORKSPACE_PANEL_LIMITS.sessions.min}
        maxWidth={WORKSPACE_PANEL_LIMITS.sessions.max}
        collapsed={isCollapsed}
        onResize={onResize ?? (() => {})}
        onToggle={toggle}
      />
      {isCollapsed ? (
        <div data-testid="session-panel-collapsed" className="flex flex-1 flex-col items-center gap-2 bg-bg py-3">
          <Button variant="ghost" size="icon-sm" onClick={toggle} title="展开会话列表" aria-label="展开会话列表">
            <PanelLeftOpen className="size-4" aria-hidden="true" />
          </Button>
          <MessageSquare className="mt-1 size-4 text-muted" aria-hidden="true" />
        </div>
      ) : (
        <>
          <div className="flex items-center justify-between gap-2 border-b border-border bg-panel px-3 py-3 pr-4">
            <b className="flex min-w-0 items-center gap-2 text-xs font-semibold uppercase tracking-[0.1em] text-text">
              <MessageSquare className="size-3.5 shrink-0 text-muted" aria-hidden="true" />
              <span className="truncate">会话</span>
            </b>
            <div className="flex shrink-0 items-center gap-1">
              <Button variant="outline" size="sm" className="h-7 gap-1 px-2 text-xs" onClick={newSession}>
                <Plus className="size-3.5" aria-hidden="true" />
                新会话
              </Button>
              <Button variant="ghost" size="icon-sm" className="hidden xl:inline-flex" onClick={toggle} title="收起会话列表" aria-label="收起会话列表">
                <PanelLeftClose className="size-3.5" aria-hidden="true" />
              </Button>
              {mobileOpen && <Button variant="ghost" size="icon-sm" className="xl:hidden" onClick={onMobileClose} title="关闭会话抽屉" aria-label="关闭会话抽屉"><X className="size-4" /></Button>}
            </div>
          </div>
          <div className="min-w-0 flex-1 overflow-y-auto p-2">
            {loading && sessions.length === 0 && <div className="p-3 text-xs text-muted" role="status">正在读取会话…</div>}
            {!loading && loadError && (
              <div className="space-y-2 p-3 text-xs text-danger" role="alert">
                <p>会话加载失败：{loadError}</p>
                <Button type="button" variant="outline" size="sm" className="h-8 px-2 text-xs" onClick={() => void load()}>重试</Button>
              </div>
            )}
            {!loading && !loadError && sessions.length === 0 && <div className="p-3 text-xs text-muted">（暂无会话）</div>}
            {sessions.map((s) => (
              <div key={s.id}
                   className={`relative mb-1 cursor-pointer rounded-md border px-3 py-2.5 transition-colors ${sessionId === s.id ? "border-border bg-panel" : "border-transparent hover:bg-panel"}`}
                   onClick={() => { setSessionId(s.id); onMobileClose?.(); }}>
                <div className="truncate pr-5 text-sm font-semibold text-text">{s.title || "（无标题会话）"}</div>
                {s.summary && <div className="mt-0.5 truncate text-xs text-muted">{s.summary}</div>}
                <div className="mt-1 break-all pr-5 font-mono text-[10px] leading-4 text-muted" title={s.id}>
                  ID: {s.id}
                </div>
                <button className="absolute right-2 top-2 grid size-6 place-items-center text-muted transition-colors hover:text-danger"
                        onClick={(e) => remove(s.id, e)} title="删除会话" aria-label="删除会话">
                  <Trash2 className="size-3.5" aria-hidden="true" />
                </button>
              </div>
            ))}
          </div>
        </>
      )}
    </aside>
  );
}
