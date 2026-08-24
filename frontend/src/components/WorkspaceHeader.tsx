"use client";

import { useEffect, useState } from "react";
import {
  Activity,
  Cpu,
  Folder,
  HardDrive,
  Menu,
  MessageSquare,
  Settings,
  ShieldCheck,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import OperationActivityCenter from "@/components/OperationActivityCenter";

type AppTab = "chat" | "files" | "settings";

interface WorkspaceHeaderProps {
  tab: AppTab;
  modelName: string;
  onTabChange: (tab: AppTab) => void;
  onSettingsSection?: (section: "models" | "security") => void;
}

const NAV_ITEMS: { key: AppTab; label: string; icon: typeof MessageSquare }[] = [
  { key: "chat", label: "对话", icon: MessageSquare },
  { key: "files", label: "文件", icon: Folder },
  { key: "settings", label: "设置", icon: Settings },
];

export default function WorkspaceHeader({ tab, modelName, onTabChange, onSettingsSection }: WorkspaceHeaderProps) {
  const [navOpen, setNavOpen] = useState(false);

  useEffect(() => {
    if (!navOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setNavOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [navOpen]);

  function selectTab(nextTab: AppTab) {
    onTabChange(nextTab);
    setNavOpen(false);
  }

  return (
    <>
      <header className="relative flex h-14 shrink-0 items-center justify-between gap-2 border-b border-border bg-panel/95 px-3 shadow-sm backdrop-blur sm:px-5">
        <div className="flex min-w-0 items-center gap-2.5">
          <Button
            type="button"
            variant="ghost"
            size="icon-lg"
            className="text-muted hover:bg-card hover:text-text"
            aria-label="打开工作区导航"
            title="工作区导航"
            onClick={() => setNavOpen(true)}
          >
            <Menu />
          </Button>
          <div className="flex min-w-0 items-center gap-2">
            <span className="grid size-7 shrink-0 place-items-center rounded-md bg-text text-panel">
              <HardDrive className="size-4" aria-hidden="true" />
            </span>
            <div className="flex min-w-0 items-center gap-2">
              <div className="truncate text-sm font-semibold tracking-tight text-text sm:text-[15px]">Agent Drive</div>
              <div className="hidden rounded border border-border bg-card px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.12em] text-muted sm:inline-block">私有部署</div>
            </div>
          </div>
        </div>

        <div className="flex min-w-0 items-center gap-2">
          <div className="hidden max-w-48 items-center gap-1.5 truncate rounded-md border border-border bg-card px-2.5 py-1.5 text-[11px] text-muted md:flex" title={modelName || "Agent 已就绪"}>
            <Activity className="size-3.5 shrink-0 text-success" aria-hidden="true" />
            <span className="truncate">{modelName || "Agent 已就绪"}</span>
          </div>
          <OperationActivityCenter />
        </div>
      </header>

      {navOpen && (
        <div className="fixed inset-0 z-50 flex" role="dialog" aria-modal="true" aria-label="工作区导航">
          <button
            type="button"
            className="absolute inset-0 cursor-default bg-text/20 backdrop-blur-[1px]"
            aria-label="关闭工作区导航"
            onClick={() => setNavOpen(false)}
          />
          <aside className="relative flex h-full w-[min(19rem,calc(100vw-1rem))] flex-col border-r border-border bg-panel shadow-2xl animate-slide-in">
            <div className="flex h-14 items-center justify-between border-b border-border px-4">
              <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.12em] text-muted">
                <HardDrive className="size-4" aria-hidden="true" />
                工作区导航
              </div>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                className="text-muted hover:bg-card hover:text-text"
                aria-label="关闭工作区导航"
                title="关闭"
                onClick={() => setNavOpen(false)}
              >
                <X />
              </Button>
            </div>

            <nav className="flex-1 overflow-y-auto p-3" aria-label="主导航">
              <div className="mb-2 px-2 text-[10px] font-semibold uppercase tracking-[0.16em] text-muted">工作区</div>
              <div className="space-y-1">
                {NAV_ITEMS.map(({ key, label, icon: Icon }) => (
                  <button
                    key={key}
                    type="button"
                    className={`flex min-h-10 w-full items-center gap-2.5 rounded-md px-3 text-left text-sm transition-colors ${tab === key ? "bg-text font-semibold text-panel" : "text-text hover:bg-card"}`}
                    aria-current={tab === key ? "page" : undefined}
                    onClick={() => selectTab(key)}
                  >
                    <Icon className="size-4 shrink-0" aria-hidden="true" />
                    <span>{label}</span>
                  </button>
                ))}
              </div>

              <div className="mb-2 mt-7 px-2 text-[10px] font-semibold uppercase tracking-[0.16em] text-muted">系统</div>
              <div className="space-y-1">
                <button
                  type="button"
                  className="flex min-h-10 w-full items-center gap-2.5 rounded-md px-3 text-left text-sm text-text transition-colors hover:bg-card"
                  onClick={() => { selectTab("settings"); onSettingsSection?.("models"); }}
                >
                  <Cpu className="size-4 shrink-0" aria-hidden="true" />
                  <span>模型与同步</span>
                </button>
                <button
                  type="button"
                  className="flex min-h-10 w-full items-center gap-2.5 rounded-md px-3 text-left text-sm text-text transition-colors hover:bg-card"
                  onClick={() => { selectTab("settings"); onSettingsSection?.("security"); }}
                >
                  <ShieldCheck className="size-4 shrink-0" aria-hidden="true" />
                  <span>设备与安全</span>
                </button>
              </div>
            </nav>

            <div className="border-t border-border bg-card/60 px-4 py-3 text-xs text-muted">
              <div className="flex items-center gap-2 text-text">
                <Activity className="size-3.5 text-success" aria-hidden="true" />
                <span className="truncate">{modelName || "Agent 已就绪"}</span>
              </div>
              <div className="mt-1 font-mono text-[10px] text-muted">数据来自当前服务端</div>
            </div>
          </aside>
        </div>
      )}

    </>
  );
}
