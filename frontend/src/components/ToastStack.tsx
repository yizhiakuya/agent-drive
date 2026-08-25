"use client";
import { useEffect, useRef, useState } from "react";
import { EV, ToastDetail } from "@/lib/events";

export default function ToastStack() {
  const [toasts, setToasts] = useState<({ id: number } & ToastDetail)[]>([]);
  const idRef = useRef(0);

  useEffect(() => {
    function onToast(e: Event) {
      const detail = (e as CustomEvent<ToastDetail>).detail;
      const id = ++idRef.current;
      setToasts((t) => [...t, { id, ...detail }]);
      setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3200);
    }
    window.addEventListener(EV.toast, onToast);
    return () => window.removeEventListener(EV.toast, onToast);
  }, []);

  if (toasts.length === 0) return null;
  return (
    <div className="fixed bottom-6 left-1/2 -translate-x-1/2 flex flex-col gap-2 z-50">
      {toasts.map((t) => (
        <div key={t.id} className={`flex items-center gap-3 px-4 py-2.5 rounded-lg text-xs text-white shadow-md animate-slide-in max-w-md ${t.kind === "ok" ? "bg-success" : t.kind === "error" ? "bg-danger" : "bg-warn"}`}>
          <span className="min-w-0 flex-1">{t.text}</span>
          {t.action && <button type="button" className="shrink-0 font-semibold underline underline-offset-2 hover:no-underline" onClick={() => { t.action?.onClick(); setToasts((current) => current.filter((item) => item.id !== t.id)); }}>{t.action.label}</button>}
        </div>
      ))}
    </div>
  );
}
