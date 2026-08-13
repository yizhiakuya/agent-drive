import { useEffect, useState } from "react";
import Onboarding from "./components/onboarding/Onboarding.jsx";
import ChatPanel from "./components/chat/ChatPanel.jsx";
import FilePanel from "./components/files/FilePanel.jsx";
import FilePage from "./components/files/FilePage.jsx";
import SettingsPage from "./components/settings/SettingsPage.jsx";
import SessionList from "./components/sessions/SessionList.jsx";
import { getStatus } from "./api/config.js";
import { listSessions } from "./api/sessions.js";

export default function App() {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sessions, setSessions] = useState([]);
  const [sessionId, setSessionId] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [tab, setTab] = useState("chat"); // chat | files | settings

  async function refresh() {
    try {
      setStatus(await getStatus());
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

  if (loading) return <div className="center-screen">正在启动 Agent Drive…</div>;

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
        <div className="status-pill">Agent 已就绪</div>
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
    </div>
  );
}
