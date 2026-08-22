"use client";

import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { FileText, FolderOpen } from "lucide-react";
import { isSafeFrontendPath } from "@/lib/frontend-actions";
import { useAppStore } from "@/lib/store";

function normalizeFileReferenceMarkdown(value: string) {
  return value.replace(/\[\[(file|folder):([^\]\n]+)\]\]/g, (_match, kind: string, path: string) => {
    const encoded = encodeURIComponent(path.trim());
    return `[${path.trim()}](https://agent-drive.local/file?kind=${kind}&path=${encoded})`;
  });
}

/** 将模型输出的文件引用渲染为 allowlist 内的前端动作。 */
export default function AssistantMarkdown({ content }: { content: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        a: ({ href, children }) => {
          let reference: { kind: "file" | "folder"; path: string } | null = null;
          if (href) {
            try {
              const url = new URL(href);
              const kind = url.origin === "https://agent-drive.local" && url.pathname === "/file"
                ? url.searchParams.get("kind")
                : null;
              const path = url.searchParams.get("path");
              if ((kind === "file" || kind === "folder") && path) {
                reference = { kind, path };
              }
            } catch {
              // 普通 Markdown 可能包含相对地址或格式错误的外链。
            }
          }

          if (reference) {
            const path = reference.path;
            if (!isSafeFrontendPath(path)) return <span>{children}</span>;
            const isFile = reference.kind === "file";
            return (
              <button
                type="button"
                aria-label={isFile ? `打开文件 ${path}` : `打开文件夹 ${path}`}
                className="mx-0.5 inline-flex max-w-full items-center gap-1 rounded-sm border border-border bg-card px-1.5 py-0.5 text-left text-accent underline-offset-2 hover:bg-accent-soft hover:underline"
                onClick={() => useAppStore.getState().enqueueFrontendAction({
                  operation: isFile ? "files.open" : "files.open_folder",
                  arguments: { path },
                  targetTab: "files",
                  summary: isFile ? `打开文件 ${path}` : `打开文件夹 ${path}`,
                })}
              >
                {isFile
                  ? <FileText className="size-3 shrink-0" aria-hidden="true" />
                  : <FolderOpen className="size-3 shrink-0" aria-hidden="true" />}
                <span className="truncate">{children}</span>
              </button>
            );
          }
          return <a href={href}>{children}</a>;
        },
      }}
    >
      {normalizeFileReferenceMarkdown(content)}
    </ReactMarkdown>
  );
}
