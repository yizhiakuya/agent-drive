"use client";
import { useEffect, useState } from "react";
import { currentServer } from "@/lib/native/server-config";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { BrainCircuit, RefreshCw, ServerOff } from "lucide-react";

type ServerNotReadyReason = "configuration" | "unavailable";

/** 展示可重试的启动阻塞状态；服务故障与 AI 未配置使用不同语义。 */
export default function ServerNotReadyCard({
  onRetry,
  reason = "configuration",
}: {
  onRetry: () => void;
  reason?: ServerNotReadyReason;
}) {
  const [server, setServer] = useState("");
  useEffect(() => { currentServer().then(setServer); }, []);
  const unavailable = reason === "unavailable";
  const StatusIcon = unavailable ? ServerOff : BrainCircuit;

  return (
    <div className="flex h-screen items-center justify-center bg-bg p-4">
      <Card className="w-full max-w-sm">
        <CardHeader className="justify-items-center text-center">
          <span className="mb-2 grid size-10 place-items-center bg-foreground text-background">
            <StatusIcon className="size-5" />
          </span>
          <CardTitle><h1>{unavailable ? "暂时无法连接服务器" : "服务器尚未配置 AI"}</h1></CardTitle>
          <CardDescription>
            {unavailable
              ? "启动检查未完成。请确认服务器和网络可用后重试。"
              : "AI 配置属于网页端。请在电脑浏览器完成配置后重试。"}
          </CardDescription>
        </CardHeader>
        {server && (
          <CardContent className="text-center">
            <code className="break-all text-xs text-foreground">{server}</code>
          </CardContent>
        )}
        <CardFooter>
          <Button onClick={onRetry} size="lg" className="w-full">
            <RefreshCw data-icon="inline-start" /> 重新检查
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}
