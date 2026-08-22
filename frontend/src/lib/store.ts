"use client";
import { create } from "zustand";
import {
  createPendingFrontendAction,
  FrontendActionPayload,
  PendingFrontendAction,
} from "./frontend-actions";

type Tab = "chat" | "files" | "settings";
export type AuthMode = "loading" | "setup" | "login" | "ready" | "rescan" | "server-error"; // rescan=原生 App 待扫码授权

interface AppState {
  configured: boolean;
  loading: boolean;
  modelName: string;
  tab: Tab;
  sessionId: string | null;
  sessionsVersion: number; // 会话列表刷新信号
  frontendActions: PendingFrontendAction[]; // Agent 请求浏览器执行的待处理动作
  authMode: AuthMode; // server-error 保留凭据并允许重试，只有 401/403 进入 login/rescan
  setConfigured: (v: boolean) => void;
  setLoading: (v: boolean) => void;
  setModelName: (v: string) => void;
  setTab: (t: Tab) => void;
  setSessionId: (id: string | null) => void;
  bumpSessions: () => void;
  enqueueFrontendAction: (action: FrontendActionPayload) => void;
  consumeFrontendAction: (id: string) => void;
  setAuthMode: (m: AuthMode) => void;
}

export const useAppStore = create<AppState>((set) => ({
  configured: false,
  loading: true,
  modelName: "",
  tab: "chat",
  sessionId: null,
  sessionsVersion: 0,
  frontendActions: [],
  authMode: "loading",
  setConfigured: (v) => set({ configured: v }),
  setLoading: (v) => set({ loading: v }),
  setModelName: (v) => set({ modelName: v }),
  setTab: (t) => set({ tab: t }),
  setSessionId: (id) => set({ sessionId: id }),
  bumpSessions: () => set((s) => ({ sessionsVersion: s.sessionsVersion + 1 })),
  enqueueFrontendAction: (action) => set((s) => ({
    frontendActions: [...s.frontendActions, createPendingFrontendAction(action)],
  })),
  consumeFrontendAction: (id) => set((s) => ({
    frontendActions: s.frontendActions.filter((action) => action.id !== id),
  })),
  setAuthMode: (m) => set({ authMode: m }),
}));
