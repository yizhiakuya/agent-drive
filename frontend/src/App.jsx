import { useEffect, useState } from "react";
import Onboarding from "./components/onboarding/Onboarding.jsx";
import ChatPanel from "./components/chat/ChatPanel.jsx";
import FilePanel from "./components/files/FilePanel.jsx";
import SessionList from "./components/sessions/SessionList.jsx";
import { getStatus } from "./api/config.js";
import { listSessions } from "./api/sessions.js";

export default function App() {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sessions, setSessions] = useState([]);
  const [sessionId, setSessionId] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);

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
        <div className="status-pill">Agent 已就绪</div>
      </header>
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
    </div>
  );
}
