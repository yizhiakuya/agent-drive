"use client";
import { useEffect, useState } from "react";
import { currentServer } from "@/lib/native/server-config";

/** 原生 App：服务器已连接但 AI 尚未配置——配置属于网页端，App 只展示提示。 */
export default function ServerNotReadyCard({ onRetry }: { onRetry: () => void }) {
  const [server, setServer] = useState("");
  useEffect(() => { currentServer().then(setServer); }, []);

  return (
    <div className="h-screen flex items-center justify-center bg-panel p-4">
      <div className="bg-card border border-border rounded-2xl p-6 w-full max-w-sm text-center">
        <div className="text-4xl mb-2">🧠</div>
        <h1 className="text-lg font-bold">服务器尚未配置 AI</h1>
        <p className="text-muted text-xs mb-4 mt-1">
          AI 配置属于网页端。请在电脑浏览器打开
          <span className="font-mono text-text break-all">{server}</span>
          完成配置，完成后回到这里重试。
        </p>
        <button onClick={onRetry}
                className="w-full bg-accent text-white px-4 py-2.5 rounded-lg text-sm font-semibold cursor-pointer">
          重新检查
        </button>
      </div>
    </div>
  );
}
