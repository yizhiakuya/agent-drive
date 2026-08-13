import { useState } from "react";
import { deleteSession } from "../../api/sessions.js";

export default function SessionList({ sessions, currentId, onSelect, onCreated, onChanged }) {
  const [busy, setBusy] = useState(false);

  function newSession() {
    setBusy(true);
    // 让 ChatPanel 创建（通过 onCreated 回调把 sid 传回来）
    onSelect(null);
    onCreated(null); // 通知 ChatPanel 开启新会话
    setTimeout(() => setBusy(false), 50);
  }

  async function del(sid) {
    await deleteSession(sid);
    if (currentId === sid) onSelect(null);
    onChanged();
  }

  return (
    <aside className="session-list">
      <div className="sl-head">
        <b>💬 会话</b>
        <button className="btn small" onClick={newSession} disabled={busy}>＋ 新会话</button>
      </div>
      <div className="sl-items">
        {sessions.length === 0 && <div className="muted small" style={{ padding: 12 }}>（暂无会话）</div>}
        {sessions.map((s) => (
          <div
            key={s.id}
            className={`sl-item ${currentId === s.id ? "active" : ""}`}
            onClick={() => onSelect(s.id)}
          >
            <div className="sl-title">{s.title || "新会话"}</div>
            <div className="sl-sub">{s.summary?.slice(0, 30) || "…"}</div>
            <button className="sl-del" onClick={(e) => { e.stopPropagation(); del(s.id); }} title="删除">✕</button>
          </div>
        ))}
      </div>
    </aside>
  );
}
