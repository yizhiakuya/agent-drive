"use client";
import { useCallback, useEffect } from "react";
import ChatPanel from "@/components/chat/ChatPanel";
import FilePanel from "@/components/files/FilePanel";
import FilePage from "@/components/files/FilePage";
import SessionList from "@/components/sessions/SessionList";
import SettingsPage from "@/components/settings/SettingsPage";
import TaskPage from "@/components/tasks/TaskPage";
import Onboarding from "@/components/onboarding/Onboarding";
import ToastStack from "@/components/ToastStack";
import PullToRefresh from "@/components/PullToRefresh";
import { getStatus, getConfig } from "@/lib/api/config";
import LoginCard from "@/components/auth/LoginCard";
import RescanCard from "@/components/auth/RescanCard";
import ServerNotReadyCard from "@/components/auth/ServerNotReadyCard";
import { ApiError, authenticatedFetch, ensureBase, getDeviceToken } from "@/lib/api/client";
import { Capacitor } from "@capacitor/core";
import { ServerConfig } from "@/lib/native/server-config";
import { useAppStore } from "@/lib/store";
import { EV, emitFilesChanged, emitRefresh } from "@/lib/events";
import { Folder, ListChecks, MessageSquare, Settings } from "lucide-react";

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
  const bumpSessions = useAppStore((s) => s.bumpSessions);

  const boot = useCallback(async () => {
    try {
      await ensureBase(); // 原生 App：从扫码配置解析服务器地址与设备令牌
      const native = Capacitor.isNativePlatform();
      if (native) {
        const { server } = await ServerConfig.getServer();
        if (!server) {
          window.dispatchEvent(new CustomEvent("agent-drive:toast", {
            detail: { kind: "error", text: "未连接服务器：请扫码连接" },
          }));
          setAuthMode("rescan");
          return;
        }
      }
      // 认证门：web=设密/登录页；App=扫码授权（无令牌即重扫码）
      const ares = await authenticatedFetch("/auth/status");
      if (!ares.ok) {
        setAuthMode(native ? "rescan" : "login");
        return;
      }
      const a = await ares.json() as { initialized: boolean };
      if (!a.initialized) {
        if (native) {
          window.dispatchEvent(new CustomEvent("agent-drive:toast", {
            detail: { kind: "error", text: "请先在网页端设置密码，再扫码连接" },
          }));
          setAuthMode("rescan");
        } else {
          setAuthMode("setup");
        }
        return;
      }
      if (native && !getDeviceToken()) {
        setAuthMode("rescan"); // 扫码即授权，无需密码
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
          setAuthMode(native ? "rescan" : "login");
          return;
        }
        setConfigured(false); // 网络/服务错误：进入 onboarding 便于重试
      }
    } catch (error) {
      if (Capacitor.isNativePlatform()) {
        window.dispatchEvent(new CustomEvent(EV.toast, {
          detail: { kind: "error", text: `安全配置读取失败：${String(error)}` },
        }));
      }
      setAuthMode(Capacitor.isNativePlatform() ? "rescan" : "login");
    } finally {
      setLoading(false);
    }
  }, [setAuthMode, setConfigured, setLoading, setModelName]);

  // 全局刷新：重新走认证+状态 → 广播给各面板（会话/文件/设置/设备/同步卡片）
  const refreshAll = useCallback(async () => {
    await boot();
    bumpSessions();
    emitFilesChanged();
    emitRefresh();
  }, [boot, bumpSessions]);

  useEffect(() => {
    // 会话过期（任意 API 返回 401）→ web 回登录页；原生 App 回重扫码页（令牌被吊销）
    const onUnauthorized = () =>
      setAuthMode(Capacitor.isNativePlatform() ? "rescan" : "login");
    window.addEventListener(EV.unauthorized, onUnauthorized);
    return () => window.removeEventListener(EV.unauthorized, onUnauthorized);
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
  }, [boot]);

  if (loading) return <SkeletonScreen />;
  if (authMode === "rescan")
    return <><RescanCard onPasswordFallback={() => setAuthMode("login")} /><ToastStack /></>;
  if (authMode === "setup") return <><LoginCard mode="setup" onDone={boot} /><ToastStack /></>;
  if (authMode === "login") return <><LoginCard mode="login" onDone={boot} /><ToastStack /></>;
  // AI 未配置：web 端走 Onboarding 向导；原生 App 只提示到网页配置（App 不含 AI 设置界面）
  if (!configured)
    return <>
      {Capacitor.isNativePlatform() ? <ServerNotReadyCard onRetry={boot} /> : <Onboarding />}
      <ToastStack />
    </>;

  const NAV = [
    { key: "chat", label: "对话", icon: MessageSquare },
    { key: "files", label: "文件", icon: Folder },
    { key: "tasks", label: "任务", icon: ListChecks },
    { key: "settings", label: "设置", icon: Settings },
  ] as const;

  return (
    <div className="flex flex-col h-screen">
      <PullToRefresh onRefresh={refreshAll} />
      <header className="flex items-center justify-between px-3 sm:px-5 py-2.5 sm:py-3 border-b border-border bg-panel gap-2">
        <div className="font-bold text-base sm:text-lg whitespace-nowrap">🦋 <span className="max-[359px]:sr-only">Agent Drive</span></div>
        <nav className="flex min-w-0 gap-0.5 sm:gap-1 flex-1 justify-center">
          {NAV.map((n) => (
            <button key={n.key}
                    title={n.label}
                    aria-label={n.label}
                    className={`h-10 min-w-10 px-2 sm:px-3 py-1.5 rounded-lg text-xs sm:text-sm cursor-pointer transition-all whitespace-nowrap inline-flex items-center justify-center gap-1.5 ${tab === n.key ? "bg-accent text-white font-semibold" : "text-text hover:bg-card"}`}
                    onClick={() => setTab(n.key)}>
              <n.icon className="size-4" />
              <span className="hidden min-[430px]:inline">{n.label}</span>
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
      {tab === "tasks" && <main className="flex flex-1 overflow-hidden"><TaskPage /></main>}
      {tab === "settings" && <main className="flex flex-1 overflow-hidden"><SettingsPage /></main>}
      <ToastStack />
    </div>
  );
}
