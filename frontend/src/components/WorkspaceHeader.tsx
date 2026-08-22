"use client";

import { useEffect, useState } from "react";
import {
  Activity,
  Cpu,
  Folder,
  HardDrive,
  ListChecks,
  Menu,
  MessageSquare,
  Settings,
  ShieldCheck,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import TaskPeekDrawer from "@/components/tasks/TaskPeekDrawer";
import { listTasks } from "@/lib/api/tasks";
import { EV } from "@/lib/events";

type AppTab = "chat" | "files" | "tasks" | "settings";

interface WorkspaceHeaderProps {
  tab: AppTab;
  modelName: string;
  onTabChange: (tab: AppTab) => void;
  onSettingsSection?: (section: "models" | "security") => void;
}

const NAV_ITEMS: { key: AppTab; label: string; icon: typeof MessageSquare }[] = [
  { key: "chat", label: "对话", icon: MessageSquare },
  { key: "files", label: "文件", icon: Folder },
  { key: "tasks", label: "任务", icon: ListChecks },
  { key: "settings", label: "设置", icon: Settings },
];

export default function WorkspaceHeader({ tab, modelName, onTabChange, onSettingsSection }: WorkspaceHeaderProps) {
  const [navOpen, setNavOpen] = useState(false);
  const [tasksOpen, setTasksOpen] = useState(false);
  const [taskAttention, setTaskAttention] = useState(false);

  useEffect(() => {
    let disposed = false;
    const loadAttention = async () => {
      try {
        const response = await listTasks("queued,running,retry_wait,cancelling,failed", { limit: 1 });
        if (!disposed) setTaskAttention(response.items.length > 0 || Object.entries(response.overview?.counts || {}).some(([status, count]) =>
          ["queued", "running", "retry_wait", "cancelling", "failed"].includes(status) && Number(count) > 0));
      } catch {
        // 顶部提示只反映最近已知状态，任务抽屉仍会展示独立错误态。
      }
    };
    void loadAttention();
    window.addEventListener(EV.tasksChanged, loadAttention);
    window.addEventListener(EV.refresh, loadAttention);
    return () => {
      disposed = true;
      window.removeEventListener(EV.tasksChanged, loadAttention);
      window.removeEventListener(EV.refresh, loadAttention);
    };
  }, []);

  useEffect(() => {
    if (!navOpen && !tasksOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setNavOpen(false);
        setTasksOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [navOpen, tasksOpen]);

  function selectTab(nextTab: AppTab) {
    onTabChange(nextTab);
    setNavOpen(false);
  }

  return (
    <>
      <header className="flex h-14 shrink-0 items-center justify-between gap-2 border-b border-border bg-panel/95 px-3 shadow-sm backdrop-blur sm:px-5">
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
          <Button
            type="button"
            variant="outline"
            className="h-9 gap-1.5 border-border bg-panel px-2.5 text-xs font-medium text-text hover:bg-card sm:px-3"
            aria-label="打开后台任务"
            title="后台任务"
            onClick={() => setTasksOpen(true)}
          >
            <span className="relative grid size-3.5 place-items-center" aria-hidden="true">
              <span className={`size-2 rounded-full ${taskAttention ? "bg-warn" : "bg-muted/50"}`} />
              {taskAttention && <span className="absolute size-3.5 animate-ping rounded-full bg-warn/30" />}
            </span>
            <ListChecks className="size-4" aria-hidden="true" />
            <span className="hidden sm:inline">任务队列</span>
          </Button>
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
              <div className="mt-1 font-mono text-[10px] text-muted">数据与任务状态来自当前服务端</div>
            </div>
          </aside>
        </div>
      )}

      <TaskPeekDrawer
        open={tasksOpen}
        onClose={() => setTasksOpen(false)}
        onViewAll={() => {
          setTasksOpen(false);
          onTabChange("tasks");
        }}
      />
    </>
  );
}
