"use client";
import { useEffect, useRef, useState } from "react";

/**
 * 全局下拉刷新：滚动容器在顶部时下拉，超过阈值松手触发 onRefresh。
 * 触屏手势实现（App 与 PWA 通用），视觉与主题一致。
 */
export default function PullToRefresh({ onRefresh }: { onRefresh: () => Promise<void> | void }) {
  const [pull, setPull] = useState(0); // 0..1
  const [refreshing, setRefreshing] = useState(false);
  const pullRef = useRef(0);
  const refreshingRef = useRef(false);
  const gesture = useRef<{ startY: number; pulling: boolean; scroller: HTMLElement | null }>({
    startY: 0, pulling: false, scroller: null,
  });

  useEffect(() => {
    function findScroller(el: HTMLElement | null): HTMLElement | null {
      let node: HTMLElement | null = el;
      while (node) {
        if (node.scrollHeight > node.clientHeight + 1) {
          const oy = window.getComputedStyle(node).overflowY;
          if (oy === "auto" || oy === "scroll" || oy === "overlay") return node;
        }
        node = node.parentElement;
      }
      return (document.scrollingElement as HTMLElement) || null;
    }

    const onStart = (e: TouchEvent) => {
      if (refreshingRef.current) return;
      const scroller = findScroller(e.target as HTMLElement);
      if (!scroller || scroller.scrollTop > 2) {
        gesture.current = { startY: 0, pulling: false, scroller: null };
        return;
      }
      gesture.current = { startY: e.touches[0].clientY, pulling: false, scroller };
    };

    const onMove = (e: TouchEvent) => {
      const g = gesture.current;
      if (!g.scroller || refreshingRef.current) return;
      const dy = e.touches[0].clientY - g.startY;
      if (dy > 6 && g.scroller.scrollTop <= 0) {
        if (!g.pulling) g.pulling = true;
        const v = Math.min(1, dy / 90);
        pullRef.current = v;
        setPull(v);
        if (e.cancelable) e.preventDefault(); // 阻止滚动链/系统手势
      }
    };

    const onEnd = () => {
      const g = gesture.current;
      if (g.pulling && pullRef.current >= 1 && !refreshingRef.current) {
        refreshingRef.current = true;
        setRefreshing(true);
        Promise.resolve(onRefresh())
          .catch(() => {})
          .finally(() => {
            setTimeout(() => {
              refreshingRef.current = false;
              setRefreshing(false);
            }, 400); // 最小展示时长，避免闪烁
          });
      }
      g.pulling = false;
      g.scroller = null;
      pullRef.current = 0;
      setPull(0);
    };

    document.addEventListener("touchstart", onStart, { passive: true });
    document.addEventListener("touchmove", onMove, { passive: false });
    document.addEventListener("touchend", onEnd, { passive: true });
    return () => {
      document.removeEventListener("touchstart", onStart);
      document.removeEventListener("touchmove", onMove);
      document.removeEventListener("touchend", onEnd);
    };
  }, [onRefresh]);

  if (pull <= 0 && !refreshing) return null;

  return (
    <div
      className="fixed top-0 left-1/2 -translate-x-1/2 z-50 pointer-events-none transition-transform"
      style={{ transform: `translate(-50%, ${refreshing ? 14 : Math.max(0, pull * 60 - 34)}px)` }}
    >
      <div className="bg-panel border border-border rounded-full px-3.5 py-1.5 shadow-lg flex items-center gap-1.5 text-xs text-muted">
        {refreshing ? (
          <>
            <span className="inline-block w-3 h-3 border-2 border-accent border-t-transparent rounded-full animate-spin" />
            刷新中…
          </>
        ) : (
          <>
            <span className={pull >= 1 ? "rotate-180 transition-transform" : "transition-transform"}>↓</span>
            {pull >= 1 ? "松开刷新" : "下拉刷新"}
          </>
        )}
      </div>
    </div>
  );
}
