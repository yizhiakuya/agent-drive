"use client";
import { useEffect, useState } from "react";
import { currentServer } from "@/lib/native/server-config";
import { Button } from "@/components/ui/button";
import { BrainCircuit, RefreshCw } from "lucide-react";

/** 原生 App：服务器已连接但 AI 尚未配置——配置属于网页端，App 只展示提示。 */
export default function ServerNotReadyCard({ onRetry }: { onRetry: () => void }) {
  const [server, setServer] = useState("");
  useEffect(() => { currentServer().then(setServer); }, []);

  return (
    <div className="flex h-screen items-center justify-center bg-bg p-4">
      <div className="w-full max-w-sm border border-border bg-panel p-6 text-center">
        <span className="mx-auto mb-5 grid size-10 place-items-center bg-text text-panel"><BrainCircuit className="size-5" /></span>
        <h1 className="text-lg font-bold">服务器尚未配置 AI</h1>
        <p className="text-muted text-xs mb-4 mt-1">
          AI 配置属于网页端。请在电脑浏览器打开
          <span className="font-mono text-text break-all">{server}</span>
          完成配置，完成后回到这里重试。
        </p>
        <Button onClick={onRetry}
                className="w-full py-2.5 text-sm font-semibold">
          <RefreshCw className="size-4" /> 重新检查
        </Button>
      </div>
    </div>
  );
}
