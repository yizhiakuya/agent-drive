import { useEffect, useRef, useState } from "react";
import Onboarding from "./components/onboarding/Onboarding.jsx";
import ChatPanel from "./components/chat/ChatPanel.jsx";
import FilePanel from "./components/files/FilePanel.jsx";
import FilePage from "./components/files/FilePage.jsx";
import SettingsPage from "./components/settings/SettingsPage.jsx";
import SessionList from "./components/sessions/SessionList.jsx";
import { getStatus } from "./api/config.js";
import { listSessions } from "./api/sessions.js";

function ToastStack() {
  const [toasts, setToasts] = useState([]);
  const idRef = useRef(0);
  useEffect(() => {
    function onToast(e) {
      const id = ++idRef.current;
      setToasts((t) => [...t, { id, ...e.detail }]);
      setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3200);
    }
    window.addEventListener("agent-drive:toast", onToast);
    return () => window.removeEventListener("agent-drive:toast", onToast);
  }, []);
  if (toasts.length === 0) return null;
  return (
    <div className="toast-stack">
      {toasts.map((t) => <div key={t.id} className={`toast ${t.kind || ""}`}>{t.text}</div>)}
    </div>
  );
}

function SkeletonScreen() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="logo">🦋 Agent Drive</div>
        <div className="skeleton" style={{width:100,height:26,borderRadius:999}} />
      </header>
      <main className="app-main">
        <div className="session-list"><div className="skeleton" style={{margin:12,height:40}} /></div>
        <section className="chat-panel" style={{padding:20,display:"flex",flexDirection:"column",gap:14}}>
          <div className="skeleton" style={{width:"60%",height:40}} />
          <div className="skeleton" style={{width:"40%",height:40,alignSelf:"flex-end"}} />
          <div className="skeleton" style={{width:"70%",height:40}} />
        </section>
        <div className="file-panel"><div className="skeleton" style={{margin:12,height:200}} /></div>
      </main>
    </div>
  );
}

export default function App() {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [modelName, setModelName] = useState("");
  const [sessions, setSessions] = useState([]);
  const [sessionId, setSessionId] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [tab, setTab] = useState("chat"); // chat | files | settings

  async function refresh() {
    try {
      setStatus(await getStatus());
      // 头部显示当前模型
      try {
        const r = await fetch("/api/v1/config");
        if (r.ok) {
          const d = await r.json();
          setModelName(d.llm?.model || "");
        }
      } catch (e) { /* 忽略 */ }
    } catch (e) {
      setStatus({ configured: false, error: String(e) });
    } finally {
      setLoading(false);
    }
  }

  async function loadSessions() {
    try {
      const r = await listSessions();
      setSessions(r.sessions);
    } catch (e) { /* 忽略 */ }
  }

  useEffect(() => { refresh(); }, []);
  useEffect(() => { if (status?.configured) loadSessions(); }, [status, refreshKey]);

  function onSessionCreated(sid) {
    setSessionId(sid);
    setRefreshKey((k) => k + 1);
  }

  if (loading) return <SkeletonScreen />;

  if (!status?.configured) {
    return <Onboarding onDone={refresh} />;
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="logo">🦋 Agent Drive</div>
        <nav className="app-nav">
          <button className={`nav-tab ${tab === "chat" ? "active" : ""}`} onClick={() => setTab("chat")}>💬 对话</button>
          <button className={`nav-tab ${tab === "files" ? "active" : ""}`} onClick={() => setTab("files")}>📁 文件</button>
          <button className={`nav-tab ${tab === "settings" ? "active" : ""}`} onClick={() => setTab("settings")}>⚙️ 设置</button>
        </nav>
        <div className="status-pill" title={modelName}>🟢 {modelName || "Agent 已就绪"}</div>
      </header>
      {tab === "chat" && (
        <main className="app-main">
          <SessionList
            sessions={sessions}
            currentId={sessionId}
            onSelect={(sid) => setSessionId(sid)}
            onCreated={onSessionCreated}
            onChanged={() => setRefreshKey((k) => k + 1)}
          />
          <ChatPanel sessionId={sessionId} onSessionCreated={onSessionCreated} />
          <FilePanel />
        </main>
      )}
      {tab === "files" && <FilePage />}
      {tab === "settings" && <SettingsPage />}
      <ToastStack />
    </div>
  );
}
