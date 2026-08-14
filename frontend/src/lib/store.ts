"use client";
import { create } from "zustand";

type Tab = "chat" | "files" | "settings";
export type AuthMode = "loading" | "setup" | "login" | "ready" | "rescan"; // rescan=原生 App 待扫码授权

interface AppState {
  configured: boolean;
  loading: boolean;
  modelName: string;
  tab: Tab;
  sessionId: string | null;
  sessionsVersion: number; // 会话列表刷新信号
  authMode: AuthMode; // loading=启动中 / setup=首次设密 / login=登录 / ready=已认证
  setConfigured: (v: boolean) => void;
  setLoading: (v: boolean) => void;
  setModelName: (v: string) => void;
  setTab: (t: Tab) => void;
  setSessionId: (id: string | null) => void;
  bumpSessions: () => void;
  setAuthMode: (m: AuthMode) => void;
}

export const useAppStore = create<AppState>((set) => ({
  configured: false,
  loading: true,
  modelName: "",
  tab: "chat",
  sessionId: null,
  sessionsVersion: 0,
  authMode: "loading",
  setConfigured: (v) => set({ configured: v }),
  setLoading: (v) => set({ loading: v }),
  setModelName: (v) => set({ modelName: v }),
  setTab: (t) => set({ tab: t }),
  setSessionId: (id) => set({ sessionId: id }),
  bumpSessions: () => set((s) => ({ sessionsVersion: s.sessionsVersion + 1 })),
  setAuthMode: (m) => set({ authMode: m }),
}));
