"use client";

import { BookOpen, ChevronRight } from "lucide-react";

/** 可折叠展示一条模型实际读取的上下文快照。 */
export function ContextInjection({ source, content }: { source: string; content: string }) {
  return (
    <details
      data-testid="context-injection"
      className="group w-full max-w-3xl text-xs text-muted"
    >
      <summary className="flex min-h-7 cursor-pointer list-none items-center gap-1.5 py-1 transition-colors hover:text-text [&::-webkit-details-marker]:hidden">
        <BookOpen className="size-3.5 shrink-0" aria-hidden="true" />
        <span>上下文注入</span>
        <span aria-hidden="true">·</span>
        <span className="min-w-0 truncate">{source}</span>
        <ChevronRight className="ml-1 size-3.5 shrink-0 transition-transform group-open:rotate-90" aria-hidden="true" />
      </summary>
      <pre className="ml-5 mt-1 max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-md bg-card px-3 py-2 font-mono text-[11px] leading-relaxed text-muted">
        {content}
      </pre>
    </details>
  );
}
