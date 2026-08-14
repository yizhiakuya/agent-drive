"use client";
import { useEffect, useState } from "react";
import { listSessions, deleteSession } from "@/lib/api/sessions";
import { useAppStore } from "@/lib/store";

export default function SessionList() {
  const sessionId = useAppStore((s) => s.sessionId);
  const setSessionId = useAppStore((s) => s.setSessionId);
  const sessionsVersion = useAppStore((s) => s.sessionsVersion);
  const [sessions, setSessions] = useState<{ id: string; title: string; summary?: string }[]>([]);

  async function load() {
    try {
      const r = await listSessions();
      setSessions(r.sessions);
    } catch { /* 忽略 */ }
  }
  useEffect(() => { load(); }, [sessionsVersion]);

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
    <aside className="hidden md:flex w-52 xl:w-60 border-r border-border bg-panel flex-col">
      <div className="flex justify-between items-center px-3 py-3.5 border-b border-border">
        <b className="text-sm">💬 会话</b>
        <button className="bg-accent text-white text-xs px-3 py-1.5 rounded-lg cursor-pointer"
                onClick={newSession}>＋ 新会话</button>
      </div>
      <div className="flex-1 overflow-y-auto p-2">
        {sessions.length === 0 && <div className="text-muted text-xs p-3">（暂无会话）</div>}
        {sessions.map((s) => (
          <div key={s.id}
               className={`relative px-3 py-2.5 rounded-lg cursor-pointer mb-1 hover:bg-card ${sessionId === s.id ? "bg-accent-soft border border-accent" : ""}`}
               onClick={() => setSessionId(s.id)}>
            <div className="text-sm font-semibold pr-5 truncate">{s.title || "（无标题会话）"}</div>
            {s.summary && <div className="text-xs text-muted mt-0.5 truncate">{s.summary}</div>}
            <button className="absolute top-2 right-2 text-muted text-xs cursor-pointer hover:text-danger"
                    onClick={(e) => remove(s.id, e)} title="删除会话">✕</button>
          </div>
        ))}
      </div>
    </aside>
  );
}
