"use client";
import { useCallback, useEffect, useState } from "react";
import ChatPanel from "@/components/chat/ChatPanel";
import FilePanel from "@/components/files/FilePanel";
import FilePage from "@/components/files/FilePage";
import SessionList from "@/components/sessions/SessionList";
import SettingsPage from "@/components/settings/SettingsPage";
import TaskPage from "@/components/tasks/TaskPage";
import Onboarding from "@/components/onboarding/Onboarding";
import ToastStack from "@/components/ToastStack";
import PullToRefresh from "@/components/PullToRefresh";
import WorkspaceHeader from "@/components/WorkspaceHeader";
import { getStatus, getConfig } from "@/lib/api/config";
import LoginCard from "@/components/auth/LoginCard";
import RescanCard from "@/components/auth/RescanCard";
import ServerNotReadyCard from "@/components/auth/ServerNotReadyCard";
import { authenticatedFetch, ensureBase, getDeviceToken } from "@/lib/api/client";
import { Capacitor } from "@capacitor/core";
import { ServerConfig } from "@/lib/native/server-config";
import { useAppStore } from "@/lib/store";
import { authModeForBootFailure } from "@/lib/boot-errors";
import { EV, emitFilesChanged, emitRefresh } from "@/lib/events";
import { Skeleton } from "@/components/ui/skeleton";
import { HardDrive } from "lucide-react";
import {
  clampWorkspacePanelWidth,
  createDefaultWorkspaceLayout,
  loadWorkspaceLayout,
  saveWorkspaceLayout,
  type WorkspaceLayout,
  type WorkspacePanel,
} from "@/lib/workspace-layout";

function SkeletonScreen() {
  return (
    <div className="flex flex-col h-screen">
      <header className="flex items-center justify-between px-5 py-3 border-b border-border bg-panel">
        <div className="flex items-center gap-2 text-lg font-bold"><span className="grid size-7 place-items-center bg-text text-panel"><HardDrive className="size-4" /></span> Agent Drive</div>
        <Skeleton className="w-24 h-6 rounded-full" />
      </header>
      <main className="flex flex-1 overflow-hidden">
        <div className="w-60 border-r border-border bg-panel p-3"><Skeleton className="h-10" /></div>
        <section className="flex-1 p-5 flex flex-col gap-3.5">
          <Skeleton className="w-3/5 h-10" />
          <Skeleton className="w-2/5 h-10 self-end" />
          <Skeleton className="w-4/5 h-10" />
        </section>
        <div className="w-80 border-l border-border bg-panel p-3"><Skeleton className="h-52" /></div>
      </main>
    </div>
  );
}

export default function Home() {
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
  const frontendActions = useAppStore((s) => s.frontendActions);
  const activeSessionId = useAppStore((s) => s.sessionId);
  const [workspaceLayout, setWorkspaceLayout] = useState<WorkspaceLayout>(() => createDefaultWorkspaceLayout());
  const [workspaceLayoutReady, setWorkspaceLayoutReady] = useState(false);
  const [sessionHydrated, setSessionHydrated] = useState(false);

  useEffect(() => {
    try {
      const sid = window.localStorage.getItem("agent-drive-active-session");
      if (sid) useAppStore.getState().setSessionId(sid);
      setSessionHydrated(true);
    } catch {
      // 浏览器禁用存储时保持默认空会话。
    }
  }, []);
  useEffect(() => {
    if (!sessionHydrated) return;
    try {
      if (activeSessionId) window.localStorage.setItem("agent-drive-active-session", activeSessionId);
      else window.localStorage.removeItem("agent-drive-active-session");
    } catch {
      // 浏览器禁用存储时保持内存会话。
    }
  }, [activeSessionId, sessionHydrated]);

  useEffect(() => {
    let storage: Storage | undefined;
    try {
      storage = window.localStorage;
    } catch {
      // 浏览器禁用存储时使用内存布局，不影响工作区操作。
    }
    setWorkspaceLayout(loadWorkspaceLayout(storage));
    setWorkspaceLayoutReady(true);
  }, []);

  useEffect(() => {
    if (!workspaceLayoutReady) return;
    let storage: Storage | undefined;
    try {
      storage = window.localStorage;
    } catch {
      // 浏览器禁用存储时跳过持久化。
    }
    saveWorkspaceLayout(workspaceLayout, storage);
  }, [workspaceLayout, workspaceLayoutReady]);

  /** 持久化前先按面板边界收敛宽度，保证拖拽、键盘调整和恢复旧布局共享同一约束。 */
  const resizePanel = useCallback((panel: WorkspacePanel, width: number) => {
    setWorkspaceLayout((current) => ({
      ...current,
      [panel]: { ...current[panel], width: clampWorkspacePanelWidth(panel, width) },
    }));
  }, []);

  const togglePanel = useCallback((panel: WorkspacePanel) => {
    setWorkspaceLayout((current) => ({
      ...current,
      [panel]: { ...current[panel], collapsed: !current[panel].collapsed },
    }));
  }, []);
  const resizeSessions = useCallback((width: number) => resizePanel("sessions", width), [resizePanel]);
  const resizeFiles = useCallback((width: number) => resizePanel("files", width), [resizePanel]);
  const toggleSessions = useCallback(() => togglePanel("sessions"), [togglePanel]);
  const toggleFiles = useCallback(() => togglePanel("files"), [togglePanel]);

  const boot = useCallback(async () => {
    setLoading(true);
    const native = Capacitor.isNativePlatform();
    try {
      try {
        await ensureBase(); // 原生 App：从扫码配置解析服务器地址与设备令牌
      } catch (error) {
        if (native) {
          window.dispatchEvent(new CustomEvent(EV.toast, {
            detail: { kind: "error", text: `安全配置读取失败：${String(error)}` },
          }));
          setAuthMode("rescan");
        } else {
          setAuthMode("server-error");
        }
        return;
      }
      if (native) {
        let server: string | null;
        try {
          ({ server } = await ServerConfig.getServer());
        } catch (error) {
          window.dispatchEvent(new CustomEvent(EV.toast, {
            detail: { kind: "error", text: `安全配置读取失败：${String(error)}` },
          }));
          setAuthMode("rescan");
          return;
        }
        if (!server) {
          window.dispatchEvent(new CustomEvent(EV.toast, {
            detail: { kind: "error", text: "未连接服务器：请扫码连接" },
          }));
          setAuthMode("rescan");
          return;
        }
      }
      // 认证门：web=设密/登录页；App=扫码授权（无令牌即重扫码）
      let ares: Response;
      try {
        ares = await authenticatedFetch("/auth/status");
      } catch (error) {
        setAuthMode(authModeForBootFailure(native, error));
        return;
      }
      if (!ares.ok) {
        setAuthMode(authModeForBootFailure(native, ares.status));
        return;
      }
      let authStatus: unknown;
      try {
        authStatus = await ares.json();
      } catch (error) {
        setAuthMode(authModeForBootFailure(native, error));
        return;
      }
      if (typeof authStatus !== "object" || authStatus === null
          || typeof (authStatus as { initialized?: unknown }).initialized !== "boolean") {
        setAuthMode("server-error");
        return;
      }
      if (!(authStatus as { initialized: boolean }).initialized) {
        if (native) {
          window.dispatchEvent(new CustomEvent(EV.toast, {
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
        const status: unknown = await getStatus();
        if (typeof status !== "object" || status === null
            || typeof (status as { configured?: unknown }).configured !== "boolean") {
          throw new Error("invalid configuration status response");
        }
        const configuredNow = (status as { configured: boolean }).configured;
        setConfigured(configuredNow);
        if (configuredNow) {
          try {
            const cfg = await getConfig();
            setModelName(cfg.llm?.model || "");
          } catch { /* 忽略 */ }
        }
      } catch (e) {
        setAuthMode(authModeForBootFailure(native, e));
      }
    } catch (error) {
      if (native) {
        window.dispatchEvent(new CustomEvent(EV.toast, {
          detail: { kind: "error", text: `启动检查失败：${String(error)}` },
        }));
      }
      setAuthMode("server-error");
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
    const next = frontendActions[0];
    if (!next) return;
    if (next.targetTab === "files") setTab("files");
  }, [frontendActions, setTab]);

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
      window.dispatchEvent(new CustomEvent(EV.toast, { detail: { kind: "ok", text: `已接收分享的文件：${shared}` } }));
      window.history.replaceState(null, "", "/");
    }
    boot();
  }, [boot]);

  if (authMode === "loading") return <SkeletonScreen />;
  if (authMode === "rescan")
    return <><RescanCard onPasswordFallback={() => setAuthMode("login")} /><ToastStack /></>;
  if (authMode === "setup") return <><LoginCard mode="setup" onDone={boot} /><ToastStack /></>;
  if (authMode === "login") return <><LoginCard mode="login" onDone={boot} /><ToastStack /></>;
  if (authMode === "server-error")
    return <><ServerNotReadyCard reason="unavailable" onRetry={boot} /><ToastStack /></>;
  // AI 未配置：web 端走 Onboarding 向导；原生 App 只提示到网页配置（App 不含 AI 设置界面）
  if (!configured)
    return <>
      {Capacitor.isNativePlatform() ? <ServerNotReadyCard reason="configuration" onRetry={boot} /> : <Onboarding />}
      <ToastStack />
    </>;

  return (
    <div className="flex h-screen flex-col bg-panel text-text">
      <PullToRefresh onRefresh={refreshAll} />
      <WorkspaceHeader tab={tab} modelName={modelName} onTabChange={setTab} />

      {/* 对话面板常驻挂载（CSS 隐藏）——切 tab 再回来不丢消息流/工具步骤 */}
      <main className={`${tab === "chat" ? "flex" : "hidden"} min-w-0 flex-1 overflow-hidden`}>
        <SessionList
          collapsed={workspaceLayout.sessions.collapsed}
          width={workspaceLayout.sessions.width}
          onResize={resizeSessions}
          onToggle={toggleSessions}
        />
        <ChatPanel />
        {/* 移动端隐藏：文件管理走"文件"tab；平板(768-1100)也隐藏防挤压 */}
        <div className="hidden h-full min-w-0 xl:flex">
          <FilePanel
            collapsed={workspaceLayout.files.collapsed}
            width={workspaceLayout.files.width}
            onResize={resizeFiles}
            onToggle={toggleFiles}
          />
        </div>
      </main>
      {tab === "files" && <main className="flex flex-1 overflow-hidden"><FilePage /></main>}
      {tab === "tasks" && <main className="flex flex-1 overflow-hidden"><TaskPage /></main>}
      {tab === "settings" && <main className="flex flex-1 overflow-hidden"><SettingsPage /></main>}
      <ToastStack />
    </div>
  );
}
