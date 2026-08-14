"use client";
import { useEffect } from "react";
import ChatPanel from "@/components/chat/ChatPanel";
import FilePanel from "@/components/files/FilePanel";
import FilePage from "@/components/files/FilePage";
import SessionList from "@/components/sessions/SessionList";
import SettingsPage from "@/components/settings/SettingsPage";
import Onboarding from "@/components/onboarding/Onboarding";
import ToastStack from "@/components/ToastStack";
import { getStatus, getConfig } from "@/lib/api/config";
import LoginCard from "@/components/auth/LoginCard";
import { V1, ApiError, authHeaders, ensureBase } from "@/lib/api/client";
import { Capacitor } from "@capacitor/core";
import { ServerConfig } from "@/lib/native/server-config";
import { useAppStore } from "@/lib/store";

function SkeletonScreen() {
  return (
    <div className="flex flex-col h-screen">
      <header className="flex items-center justify-between px-5 py-3 border-b border-border bg-panel">
        <div className="font-bold text-lg">🦋 Agent Drive</div>
        <div className="skeleton w-24 h-6 rounded-full" />
      </header>
      <main className="flex flex-1 overflow-hidden">
        <div className="w-60 border-r border-border bg-panel"><div className="skeleton m-3 h-10" /></div>
        <section className="flex-1 p-5 flex flex-col gap-3.5">
          <div className="skeleton w-3/5 h-10" />
          <div className="skeleton w-2/5 h-10 self-end" />
          <div className="skeleton w-4/5 h-10" />
        </section>
        <div className="w-80 border-l border-border bg-panel"><div className="skeleton m-3 h-52" /></div>
      </main>
    </div>
  );
}

export default function Home() {
  const loading = useAppStore((s) => s.loading);
  const authMode = useAppStore((s) => s.authMode);
  const configured = useAppStore((s) => s.configured);
  const tab = useAppStore((s) => s.tab);
  const setTab = useAppStore((s) => s.setTab);
  const modelName = useAppStore((s) => s.modelName);
  const setLoading = useAppStore((s) => s.setLoading);
  const setAuthMode = useAppStore((s) => s.setAuthMode);
  const setConfigured = useAppStore((s) => s.setConfigured);
  const setModelName = useAppStore((s) => s.setModelName);

  useEffect(() => {
    // 会话过期（任意 API 返回 401）→ 回登录页
    const onUnauthorized = () => setAuthMode("login");
    window.addEventListener("agent-drive:unauthorized", onUnauthorized);
    return () => window.removeEventListener("agent-drive:unauthorized", onUnauthorized);
  }, [setAuthMode]);

  useEffect(() => {
    // 原生 App：回前台/窗口聚焦时心跳，刷新服务器设备列表活跃时间
    if (Capacitor.isNativePlatform()) {
      const beat = () => { ServerConfig.heartbeat().catch(() => {}); };
      window.addEventListener("visibilitychange", beat);
      window.addEventListener("focus", beat);
      return () => {
        window.removeEventListener("visibilitychange", beat);
        window.removeEventListener("focus", beat);
      };
    }
  }, []);

  useEffect(() => {
    // 分享上传回跳提示
    const shared = new URLSearchParams(window.location.search).get("shared");
    if (shared) {
      window.dispatchEvent(new CustomEvent("agent-drive:toast", { detail: { kind: "ok", text: `已接收分享的文件：${shared}` } }));
      window.history.replaceState(null, "", "/");
    }
    boot();
  }, []);

  async function boot() {
    try {
      await ensureBase(); // 原生 App：从扫码配置解析服务器地址与设备令牌
      if (Capacitor.isNativePlatform()) {
        const { server } = await ServerConfig.getServer();
        if (!server) {
          window.dispatchEvent(new CustomEvent("agent-drive:toast", {
            detail: { kind: "error", text: "未连接服务器：请返回设置 → 连接手机 App 重新扫码" },
          }));
        }
      }
      // 认证门：未设密码 → 设密页；未登录 → 登录页；已认证 → 进入主流程
      const ares = await fetch(`${V1}/auth/status`, { credentials: "include", headers: authHeaders() });
      if (!ares.ok) {
        setAuthMode("login");
        return;
      }
      const a = await ares.json() as { initialized: boolean };
      if (!a.initialized) {
        setAuthMode("setup");
        return;
      }
      setAuthMode("ready");
      try {
        const status = await getStatus() as { configured: boolean };
        setConfigured(status.configured);
        if (status.configured) {
          try {
            const cfg = await getConfig();
            setModelName(cfg.llm?.model || "");
          } catch { /* 忽略 */ }
        }
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          setAuthMode("login");
          return;
        }
        setConfigured(false); // 网络/服务错误：进入 onboarding 便于重试
      }
    } catch {
      setAuthMode("login");
    } finally {
      setLoading(false);
    }
  }

  if (loading) return <SkeletonScreen />;
  if (authMode === "setup") return <><LoginCard mode="setup" onDone={boot} /><ToastStack /></>;
  if (authMode === "login") return <><LoginCard mode="login" onDone={boot} /><ToastStack /></>;
  if (!configured) return <><Onboarding /><ToastStack /></>;

  const NAV = [
    { key: "chat", label: "💬 对话" },
    { key: "files", label: "📁 文件" },
    { key: "settings", label: "⚙️ 设置" },
  ] as const;

  return (
    <div className="flex flex-col h-screen">
      <header className="flex items-center justify-between px-3 sm:px-5 py-2.5 sm:py-3 border-b border-border bg-panel gap-2">
        <div className="font-bold text-base sm:text-lg whitespace-nowrap">🦋 Agent Drive</div>
        <nav className="flex gap-0.5 sm:gap-1 flex-1 justify-center">
          {NAV.map((n) => (
            <button key={n.key}
                    className={`px-2.5 sm:px-3.5 py-1.5 rounded-lg text-xs sm:text-sm cursor-pointer transition-all whitespace-nowrap ${tab === n.key ? "bg-accent text-white font-semibold" : "text-text hover:bg-card"}`}
                    onClick={() => setTab(n.key)}>
              {n.label}
            </button>
          ))}
        </nav>
        <div className="hidden md:block bg-success-soft text-success px-3 py-1 rounded-full text-xs whitespace-nowrap" title={modelName}>🟢 {modelName || "Agent 已就绪"}</div>
      </header>

      {tab === "chat" && (
        <main className="flex flex-1 overflow-hidden">
          <SessionList />
          <ChatPanel />
          {/* 移动端隐藏：文件管理走"文件"tab；平板(768-1100)也隐藏防挤压 */}
          <div className="hidden xl:flex h-full"><FilePanel /></div>
        </main>
      )}
      {tab === "files" && <main className="flex flex-1 overflow-hidden"><FilePage /></main>}
      {tab === "settings" && <main className="flex flex-1 overflow-hidden"><SettingsPage /></main>}
      <ToastStack />
    </div>
  );
}