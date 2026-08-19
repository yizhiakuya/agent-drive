import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { FileInfo } from "@/lib/api/files";
import FileDetails from "./FileDetails";

function info(indexed: FileInfo["indexed"]): FileInfo {
  return {
    path: "docs/合同.txt",
    name: "合同.txt",
    size: 128,
    modified: 1_750_000_000,
    revision: 3,
    content_type: "text/plain",
    preview_kind: "text",
    snippet: "合同正文",
    indexed,
  };
}

describe("FileDetails 向量化详情", () => {
  it("显示可检索状态、模型和不含 key 的指纹", () => {
    render(<FileDetails info={info({
      text_indexed: true,
      vectorized: true,
      vector_status: "vectorized",
      chunk_count: 2,
      vector_chunks: 2,
      stored_vector_chunks: 2,
      embedding_configured: true,
      detail: {
        available: true,
        document_id: "doc-1",
        source_revision: 3,
        extractor_version: "tika-1",
        updated: "2026-08-19T03:00:00Z",
        embedding_provider: "jina",
        embedding_model: "jina-embeddings-v3",
        embedding_fingerprint: "abc123",
        truncated: false,
        chunks: [],
      },
    })} />);

    expect(screen.getByText("当前可检索")).toBeInTheDocument();
    expect(screen.getByText("jina-embeddings-v3")).toBeInTheDocument();
    expect(screen.getByText("abc123")).toBeInTheDocument();
    expect(screen.getByText("doc-1")).toBeInTheDocument();
    expect(screen.getByText("tika-1")).toBeInTheDocument();
    expect(screen.getByText("文本段 2 · 当前有效向量 2 · 已存向量 2")).toBeInTheDocument();
  });

  it("可展开文本段内容和新接口提供的元数据", () => {
    render(<FileDetails info={info({
      text_indexed: true,
      vectorized: false,
      vector_status: "partial",
      chunk_count: 1,
      vector_chunks: 0,
      stored_vector_chunks: 0,
      embedding_configured: true,
      detail: {
        available: false,
        document_id: 7,
        source_revision: 3,
        extractor_version: "tika-1",
        updated: 1_750_000_000,
        embedding_provider: "jina",
        embedding_model: "jina-embeddings-v3",
        embedding_fingerprint: "abc123",
        truncated: true,
        chunks: [{
        id: "chunk-1",
        index: 0,
        chunk_version: "v2",
        source_revision: 3,
        content: "第一段内容",
        content_length: 5,
        stored_vector: true,
        current_vector: false,
        embedding_fingerprint: "old-fingerprint",
        }],
      },
    })} />);

    const summary = screen.getByText("文本段 1");
    const details = summary.closest("details");
    expect(details).not.toBeNull();
    expect(details).not.toHaveAttribute("open");
    fireEvent.click(summary);
    expect(details).toHaveAttribute("open");
    expect(screen.getByText("第一段内容")).toBeInTheDocument();
    expect(screen.getByText("chunk-1")).toBeInTheDocument();
    expect(screen.getByText("old-fingerprint")).toBeInTheDocument();
    expect(screen.getByText("文本段详情已截断，当前列表不是完整文档内容。")).toBeInTheDocument();
  });

  it("兼容旧后端响应并明确提示缺少详情字段", () => {
    render(<FileDetails info={info({
      text_indexed: false,
      vectorized: false,
      vector_status: "not_indexed",
      chunk_count: 0,
      vector_chunks: 0,
      stored_vector_chunks: 0,
      embedding_configured: false,
    })} />);

    expect(screen.getByText("暂不可检索")).toBeInTheDocument();
    expect(screen.getByText("旧后端未提供索引详情（模型、指纹和文本段元数据）。")).toBeInTheDocument();
    expect(screen.getByText("旧后端未提供可展开的文本段内容和元数据。")).toBeInTheDocument();
  });
});
