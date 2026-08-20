"use client";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Music2 } from "lucide-react";
import { FileInfo, fileRawUrl } from "@/lib/api/files";

/**
 * 六分支文件预览（image/video/audio/pdf/text+markdown/binary）。
 * FilePanel（侧栏紧凑态）与 FilePage（独立预览面板）共用；
 * variant 区分两处原有的 class 差异，渲染结果与抽取前完全一致。
 */
export default function FilePreview({
  info,
  path,
  text,
  isMarkdown,
  variant,
}: {
  info: FileInfo;
  path: string;
  text: string;
  isMarkdown?: boolean;
  variant: "panel" | "page";
}) {
  const kind = info.preview_kind;
  const name = path.split("/").pop() || "";
  const mp = isMarkdown && kind === "text";

  if (kind === "image") {
    return <img src={fileRawUrl(path)} referrerPolicy="no-referrer" alt={variant === "page" ? path : ""} className="max-w-full mx-auto" />;
  }
  if (kind === "video") {
    return <video src={fileRawUrl(path)} controls className={variant === "panel" ? "w-full max-h-56" : "w-full max-h-full"} />;
  }
  if (kind === "audio") {
    if (variant === "panel") {
      return (
        <div className="p-3">
          <audio src={fileRawUrl(path)} controls className="w-full" />
        </div>
      );
    }
    return (
      <div className="flex flex-col items-center gap-3 p-6">
        <div className="grid size-16 place-items-center rounded-lg border border-border bg-card text-text">
          <Music2 className="size-8" aria-hidden="true" />
        </div>
        <div className="text-sm">{name}</div>
        <audio src={fileRawUrl(path)} controls className="w-full" />
      </div>
    );
  }
  if (kind === "pdf") {
    return <iframe src={fileRawUrl(path)} referrerPolicy="no-referrer" title={variant === "panel" ? "pdf" : path} className={variant === "panel" ? "w-full h-56" : "w-full h-full min-h-96"} />;
  }
  if (kind === "text") {
    if (mp) {
      const cls = variant === "panel" ? "markdown-body px-3 py-2 text-xs" : "markdown-body px-3 py-2";
      return (
        <div className={cls}>
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{text}</ReactMarkdown>
        </div>
      );
    }
    return <pre className="px-3 py-2 text-xs whitespace-pre-wrap break-all">{text}</pre>;
  }
  // binary
  return variant === "panel"
    ? <div className="p-3 text-muted text-xs">二进制文件，下载查看</div>
    : <div className="p-4 text-muted text-sm">二进制文件不支持预览，可下载后查看</div>;
}
