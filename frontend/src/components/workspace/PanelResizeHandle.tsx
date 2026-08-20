"use client";

import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import type { WorkspacePanel } from "@/lib/workspace-layout";

interface PanelResizeHandleProps {
  panel: WorkspacePanel;
  width: number;
  minWidth: number;
  maxWidth: number;
  collapsed: boolean;
  onResize: (width: number) => void;
  onToggle: () => void;
}

function clamp(width: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, Math.round(width)));
}

/**
 * 统一处理会话栏和文件栏的拖拽、键盘调宽和收缩操作。
 * 左栏与右栏的 pointer delta 方向相反，必须在这里集中换算，避免两个面板出现不同的交互口径。
 */
export default function PanelResizeHandle({
  panel,
  width,
  minWidth,
  maxWidth,
  collapsed,
  onResize,
  onToggle,
}: PanelResizeHandleProps) {
  const [dragging, setDragging] = useState(false);
  const startXRef = useRef(0);
  const startWidthRef = useRef(width);
  const isLeftPanel = panel === "sessions";

  useEffect(() => {
    if (!dragging) return;
    // 监听 document 而不是手柄本身，保证指针拖出窄分隔轨道后仍能持续调整宽度。
    const onPointerMove = (event: PointerEvent) => {
      const delta = event.clientX - startXRef.current;
      const direction = isLeftPanel ? 1 : -1;
      onResize(clamp(startWidthRef.current + delta * direction, minWidth, maxWidth));
    };
    const onPointerUp = () => setDragging(false);
    const previousCursor = document.body.style.cursor;
    const previousUserSelect = document.body.style.userSelect;
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
    window.addEventListener("pointermove", onPointerMove);
    window.addEventListener("pointerup", onPointerUp);
    return () => {
      document.body.style.cursor = previousCursor;
      document.body.style.userSelect = previousUserSelect;
      window.removeEventListener("pointermove", onPointerMove);
      window.removeEventListener("pointerup", onPointerUp);
    };
  }, [dragging, isLeftPanel, maxWidth, minWidth, onResize]);

  function onPointerDown(event: React.PointerEvent<HTMLDivElement>) {
    if (event.button !== 0 || collapsed) return;
    event.preventDefault();
    startXRef.current = event.clientX;
    startWidthRef.current = width;
    setDragging(true);
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 32 : 16;
    const increase = isLeftPanel ? event.key === "ArrowRight" : event.key === "ArrowLeft";
    const decrease = isLeftPanel ? event.key === "ArrowLeft" : event.key === "ArrowRight";
    if (increase || decrease) {
      event.preventDefault();
      onResize(clamp(width + (increase ? step : -step), minWidth, maxWidth));
      return;
    }
    if (event.key === "Home") {
      event.preventDefault();
      onResize(minWidth);
      return;
    }
    if (event.key === "End") {
      event.preventDefault();
      onResize(maxWidth);
      return;
    }
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onToggle();
    }
  }

  if (collapsed) return null;

  return (
    <div
      data-testid={`${panel}-panel-resize-handle`}
      role="separator"
      aria-label={panel === "sessions" ? "调整会话列表宽度" : "调整文件栏宽度"}
      aria-orientation="vertical"
      aria-valuemin={minWidth}
      aria-valuemax={maxWidth}
      aria-valuenow={Math.round(width)}
      aria-valuetext={`${Math.round(width)} 像素`}
      tabIndex={0}
      title="拖动调整宽度，按 Enter 收起"
      className={cn(
        "group absolute inset-y-0 z-20 hidden w-3 -translate-x-1/2 cursor-col-resize touch-none outline-none md:block",
        // Keep the hit area centered on the panel's outer edge. The right-anchored
        // session handle needs a full-handle offset because it is translated left.
        isLeftPanel ? "-right-3" : "left-0",
        dragging ? "cursor-col-resize" : "",
      )}
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
    >
      <span
        aria-hidden="true"
        className="absolute inset-y-0 left-1/2 w-px bg-border transition-colors group-hover:bg-text group-focus-visible:bg-text"
      />
      <span
        aria-hidden="true"
        className="absolute left-1/2 top-1/2 h-10 w-1 -translate-x-1/2 -translate-y-1/2 rounded-full bg-border opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"
      />
    </div>
  );
}
