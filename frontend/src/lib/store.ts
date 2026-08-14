"use client";
import { create } from "zustand";

type Tab = "chat" | "files" | "settings";

interface AppState {
  configured: boolean;
  loading: boolean;
  modelName: string;
  tab: Tab;
  sessionId: string | null;
  sessionsVersion: number; // 会话列表刷新信号
  setConfigured: (v: boolean) => void;
  setLoading: (v: boolean) => void;
  setModelName: (v: string) => void;
  setTab: (t: Tab) => void;
  setSessionId: (id: string | null) => void;
  bumpSessions: () => void;
}

export const useAppStore = create<AppState>((set) => ({
  configured: false,
  loading: true,
  modelName: "",
  tab: "chat",
  sessionId: null,
  sessionsVersion: 0,
  setConfigured: (v) => set({ configured: v }),
  setLoading: (v) => set({ loading: v }),
  setModelName: (v) => set({ modelName: v }),
  setTab: (t) => set({ tab: t }),
  setSessionId: (id) => set({ sessionId: id }),
  bumpSessions: () => set((s) => ({ sessionsVersion: s.sessionsVersion + 1 })),
}));
