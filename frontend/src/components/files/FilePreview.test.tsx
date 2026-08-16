import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import FilePreview from "./FilePreview";
import type { FileInfo } from "@/lib/api/files";

vi.mock("@/lib/api/files", () => ({
  fileRawUrl: (p: string) => `/raw?path=${encodeURIComponent(p)}`,
}));

function info(kind: FileInfo["preview_kind"], path = "a.txt"): FileInfo {
  return {
    path,
    name: path.split("/").pop() || path,
    size: 10,
    modified: 1_750_000_000,
    preview_kind: kind,
    snippet: "正文内容",
    indexed: null,
  };
}

describe("FilePreview 六分支渲染", () => {
  it("image 渲染 img", () => {
    render(<FilePreview info={info("image")} path="a.png" text="" isMarkdown={false} variant="page" />);
    const img = screen.getByRole("img") as HTMLImageElement;
    expect(img.src).toContain("/raw?path=a.png");
  });

  it("video 渲染带 controls 的 video", () => {
    render(<FilePreview info={info("video")} path="a.mp4" text="" isMarkdown={false} variant="page" />);
    const el = document.querySelector("video");
    expect(el).not.toBeNull();
    expect(el?.hasAttribute("controls")).toBe(true);
  });

  it("audio 渲染 audio", () => {
    render(<FilePreview info={info("audio")} path="a.mp3" text="" isMarkdown={false} variant="page" />);
    const el = document.querySelector("audio");
    expect(el).not.toBeNull();
    expect(el?.hasAttribute("controls")).toBe(true);
  });

  it("pdf 渲染 iframe", () => {
    render(<FilePreview info={info("pdf")} path="a.pdf" text="" isMarkdown={false} variant="page" />);
    const el = document.querySelector("iframe");
    expect(el).not.toBeNull();
    expect(el?.getAttribute("src")).toContain("/raw?path=a.pdf");
  });

  it("text + markdown 渲染 markdown 容器", () => {
    render(<FilePreview info={info("text", "note.md")} path="note.md" text="# 标题" isMarkdown variant="panel" />);
    const el = document.querySelector(".markdown-body");
    expect(el).not.toBeNull();
    expect(el?.textContent).toContain("标题");
  });

  it("text 非 markdown 渲染 pre", () => {
    render(<FilePreview info={info("text", "a.txt")} path="a.txt" text="纯文本" isMarkdown={false} variant="panel" />);
    const el = document.querySelector("pre");
    expect(el).not.toBeNull();
    expect(el?.textContent).toContain("纯文本");
  });

  it("binary 渲染下载提示", () => {
    render(<FilePreview info={info("binary")} path="a.bin" text="" isMarkdown={false} variant="page" />);
    expect(screen.getByText(/二进制文件/)).toBeInTheDocument();
  });
});
