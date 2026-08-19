"use client";
import { useEffect, useRef, useState } from "react";
import { MessageSquare, Plus, Trash2 } from "lucide-react";
import { listSessions, deleteSession, summarizeSession } from "@/lib/api/sessions";
import { useAppStore } from "@/lib/store";
import { Button } from "@/components/ui/button";

const summarizingSessions = new Set<string>();

export default function SessionList() {
  const sessionId = useAppStore((s) => s.sessionId);
  const setSessionId = useAppStore((s) => s.setSessionId);
  const sessionsVersion = useAppStore((s) => s.sessionsVersion);
  const [sessions, setSessions] = useState<{ id: string; title: string; summary?: string }[]>([]);
  const loadSequenceRef = useRef(0);
  const mountedRef = useRef(true);

  async function load() {
    const sequence = ++loadSequenceRef.current;
    // 两次列表写入都必须留在同一请求序列内，防止旧请求的列表覆盖新列表。
    const isCurrent = () => mountedRef.current && sequence === loadSequenceRef.current;
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
    } catch { /* 忽略：下一次会话变更再重试 */ }
  }
  useEffect(() => {
    mountedRef.current = true;
    load();
    return () => { mountedRef.current = false; };
  }, [sessionsVersion]);

  function newSession() {
    setSessionId(null);
  }

  async function remove(sid: string, e: React.MouseEvent) {
    e.stopPropagation();
    await deleteSession(sid);
    load();
    if (sessionId === sid) setSessionId(null);
  }

  return (
    <aside className="hidden w-52 shrink-0 flex-col border-r border-border bg-bg md:flex xl:w-60">
      <div className="flex items-center justify-between border-b border-border bg-panel px-3 py-3">
        <b className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.1em] text-text">
          <MessageSquare className="size-3.5 text-muted" aria-hidden="true" />
          会话
        </b>
        <Button variant="outline" size="sm" className="h-7 gap-1 px-2 text-xs" onClick={newSession}>
          <Plus className="size-3.5" aria-hidden="true" />
          新会话
        </Button>
      </div>
      <div className="flex-1 overflow-y-auto p-2">
        {sessions.length === 0 && <div className="text-muted text-xs p-3">（暂无会话）</div>}
        {sessions.map((s) => (
          <div key={s.id}
               className={`relative mb-1 cursor-pointer rounded-md border px-3 py-2.5 transition-colors ${sessionId === s.id ? "border-border bg-panel" : "border-transparent hover:bg-panel"}`}
               onClick={() => setSessionId(s.id)}>
            <div className="truncate pr-5 text-sm font-semibold text-text">{s.title || "（无标题会话）"}</div>
            {s.summary && <div className="text-xs text-muted mt-0.5 truncate">{s.summary}</div>}
            <div className="mt-1 pr-5 break-all font-mono text-[10px] leading-4 text-muted" title={s.id}>
              ID: {s.id}
            </div>
            <button className="absolute right-2 top-2 grid size-6 place-items-center text-muted transition-colors hover:text-danger"
                    onClick={(e) => remove(s.id, e)} title="删除会话" aria-label="删除会话">
              <Trash2 className="size-3.5" aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </aside>
  );
}
