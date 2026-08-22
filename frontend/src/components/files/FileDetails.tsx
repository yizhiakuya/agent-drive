"use client";

import { useEffect, useState } from "react";
import type { FileIndexChunk, FileInfo, FileIndexStatus, FileVersion } from "@/lib/api/files";
import { listVersions, restoreVersion } from "@/lib/api/files";
import { fmtSize, fmtTime } from "@/lib/format";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { History, RotateCcw } from "lucide-react";

const STATUS_LABELS: Record<FileIndexStatus["vector_status"], string> = {
  not_indexed: "未抽取",
  indexed: "已抽取，暂无文本块",
  pending: "等待向量化",
  partial: "部分向量化",
  vectorized: "已向量化",
  stale: "旧模型向量",
  not_configured: "未配置向量模型",
};

function chunkLabel(chunk: FileIndexChunk) {
  return `${chunk.vector_type === "vision" ? "视觉描述" : "文本段"} ${chunk.index + 1}`;
}

function vectorTypeLabel(type: FileIndexStatus["vector_type"]) {
  if (type === "vision") return "视觉描述向量";
  if (type === "mixed") return "文本 + 视觉向量";
  if (type === "text") return "文本向量";
  return "未生成向量";
}

/**
 * 展示文件的完整元数据和索引状态。
 *
 * <p>组件刻意把“已抽取”和“已向量化”分开，避免把全文解析完成误认为语义检索
 * 已就绪；状态字段由后端按当前文件 revision 和 embedding fingerprint 计算。</p>
 *
 * @param info 文件详情响应。
 */
export default function FileDetails({ info, onRestored }: { info: FileInfo; onRestored?: () => void | Promise<void> }) {
  const index = info.indexed;
  const detail = index?.detail;
  const [versions, setVersions] = useState<FileVersion[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [restoring, setRestoring] = useState<string | null>(null);
  const [versionsError, setVersionsError] = useState("");

  useEffect(() => {
    let active = true;
    setVersionsLoading(true);
    setVersionsError("");
    void listVersions(info.path)
      .then((result) => { if (active) setVersions(result.items); })
      .catch((error) => { if (active) setVersionsError(error instanceof Error ? error.message : String(error)); })
      .finally(() => { if (active) setVersionsLoading(false); });
    return () => { active = false; };
  }, [info.path, info.revision]);

  async function restore(version: FileVersion) {
    if (restoring) return;
    setRestoring(version.version_id);
    try {
      await restoreVersion(info.path, version.version_id);
      await onRestored?.();
    } catch (error) {
      setVersionsError(error instanceof Error ? error.message : String(error));
    } finally {
      setRestoring(null);
    }
  }

  return (
    <div className="space-y-4 p-4 text-xs">
      <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-muted">文件元数据</div>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 border-y border-border py-3">
        <dt className="text-muted">完整路径</dt>
        <dd className="min-w-0 break-all" title={info.path}>{info.path}</dd>
        <dt className="text-muted">类型</dt>
        <dd>{info.content_type}</dd>
        <dt className="text-muted">大小</dt>
        <dd>{fmtSize(info.size)}</dd>
        <dt className="text-muted">修改时间</dt>
        <dd>{fmtTime(info.modified)}</dd>
        <dt className="text-muted">版本</dt>
        <dd>{info.revision ?? "未知"}</dd>
      </dl>

      <div className="border-t border-border pt-4">
        <div className="mb-2 flex items-center justify-between gap-2 font-semibold">
          <span className="flex items-center gap-1.5"><History className="size-3.5 text-muted" />版本历史</span>
          {versions.length > 0 && <span className="text-[10px] font-normal text-muted">{versions.length} 个快照</span>}
        </div>
        {versionsLoading ? <p className="text-muted">正在读取版本历史…</p> : versionsError ? (
          <p className="text-danger">版本历史加载失败：{versionsError}</p>
        ) : versions.length === 0 ? (
          <p className="text-muted">暂无可恢复的历史版本。</p>
        ) : (
          <div className="space-y-1.5">
            {versions.map((version) => (
              <div key={version.version_id} className="flex items-center gap-2 border border-border px-2 py-1.5">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 text-[11px]">
                    <span className="font-medium">版本 {version.source_revision}</span>
                    <span className="text-muted">{fmtTime(typeof version.created_at === "number" ? version.created_at : Number(version.created_at))}</span>
                  </div>
                  <div className="text-[10px] text-muted">{fmtSize(version.size)}</div>
                </div>
                <Button type="button" variant="outline" size="sm" disabled={restoring !== null}
                        onClick={() => void restore(version)} aria-label={`恢复版本 ${version.source_revision}`}>
                  <RotateCcw className={`size-3.5 ${restoring === version.version_id ? "animate-spin" : ""}`} />
                  恢复
                </Button>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="border-t border-border pt-4">
        <div className="mb-2 flex items-center justify-between gap-2 font-semibold">
          <span>索引状态</span>
          <span className="font-mono text-[10px] font-normal text-muted">revision-aware</span>
        </div>
        {!index ? (
          <p className="text-muted">当前文件还没有索引记录。</p>
        ) : (
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-1.5">
              <Badge variant={detail?.available ?? index.vectorized ? "default" : "outline"}>
                {detail ? (detail.available ? "当前可检索" : "暂不可检索") : STATUS_LABELS[index.vector_status]}
              </Badge>
              {!detail && (
                <Badge variant={index.vectorized ? "default" : "outline"}>
                  {index.vectorized ? "当前可检索" : "暂不可检索"}
                </Badge>
              )}
              {index.text_indexed && <Badge variant="outline">文本已抽取</Badge>}
              {index.vision_indexed && <Badge variant="outline">视觉描述已生成</Badge>}
              <Badge variant="outline">{vectorTypeLabel(index.vector_type)}</Badge>
            </div>
            <p className="text-muted">
              {index.vision_chunk_count === undefined
                ? `文本段 ${index.chunk_count} · 当前有效向量 ${index.vector_chunks} · 已存向量 ${index.stored_vector_chunks}`
                : `文本 ${index.text_chunk_count ?? 0} 段 · 视觉描述 ${index.vision_chunk_count} 段 · 当前有效向量 ${index.vector_chunks} · 已存向量 ${index.stored_vector_chunks}`}
            </p>
            {detail ? (
              <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1">
                <dt className="text-muted">文档 ID</dt>
                <dd className="min-w-0 break-all">{detail.document_id ?? "未返回"}</dd>
                <dt className="text-muted">源文件版本</dt>
                <dd className="min-w-0 break-all">{detail.source_revision ?? "未返回"}</dd>
                <dt className="text-muted">抽取器版本</dt>
                <dd className="min-w-0 break-all">{detail.extractor_version ?? "未返回"}</dd>
                <dt className="text-muted">向量类型</dt>
                <dd className="min-w-0 break-all">{vectorTypeLabel(detail.vector_type)}</dd>
                <dt className="text-muted">索引更新时间</dt>
                <dd className="min-w-0 break-all">{detail.updated ?? "未返回"}</dd>
                <dt className="text-muted">Embedding Provider</dt>
                <dd className="min-w-0 break-all">{detail.embedding_provider ?? "未返回"}</dd>
                <dt className="text-muted">Embedding 模型</dt>
                <dd className="min-w-0 break-all">{detail.embedding_model ?? "未返回"}</dd>
                <dt className="text-muted">Embedding 指纹</dt>
                <dd className="min-w-0 break-all font-mono text-[11px]">{detail.embedding_fingerprint ?? "未返回"}</dd>
              </dl>
            ) : (
              <p className="text-muted">旧后端未提供索引详情（模型、指纹和文本段元数据）。</p>
            )}
            {detail?.truncated && <p className="text-warn">文本段详情已截断，当前列表不是完整文档内容。</p>}
            {!index.embedding_configured && index.stored_vector_chunks > 0 && (
              <p className="text-warn">当前未配置向量模型，已有向量不会作为当前模型结果使用。</p>
            )}
            {detail ? (
              <div className="space-y-1.5">
                <div className="font-medium">文本段详情</div>
                {detail.chunks.length ? detail.chunks.map((chunk) => (
                  <details key={chunk.id} className="border border-border px-2 py-1.5">
                    <summary className="cursor-pointer select-none">
                      <span>{chunkLabel(chunk)}</span>
                      <span className="ml-2 text-muted">{chunk.current_vector ? "当前向量有效" : "当前无有效向量"}</span>
                    </summary>
                    <div className="mt-2 space-y-2">
                      <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words border border-border bg-card/60 p-2 font-sans text-[11px]">{chunk.content}</pre>
                      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-[11px]">
                        <dt className="text-muted">Chunk ID</dt><dd className="break-all">{chunk.id}</dd>
                        <dt className="text-muted">文本长度</dt><dd>{chunk.content_length}</dd>
                        <dt className="text-muted">Chunk 版本</dt><dd className="break-all">{chunk.chunk_version ?? "未返回"}</dd>
                        <dt className="text-muted">源文件版本</dt><dd className="break-all">{chunk.source_revision ?? "未返回"}</dd>
                        <dt className="text-muted">已存向量</dt><dd>{chunk.stored_vector ? "是" : "否"}</dd>
                        <dt className="text-muted">当前向量</dt><dd>{chunk.current_vector ? "有效" : "无"}</dd>
                        <dt className="text-muted">向量指纹</dt><dd className="break-all font-mono">{chunk.embedding_fingerprint ?? "未返回"}</dd>
                      </dl>
                    </div>
                  </details>
                )) : <p className="text-muted">当前文档没有返回文本段。</p>}
              </div>
            ) : (
              <p className="text-muted">旧后端未提供可展开的文本段内容和元数据。</p>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * 返回列表行使用的短索引状态文案。
 *
 * @param index 文件列表中的索引状态，可为空。
 * @returns 适合表格单元格的短文本。
 */
export function indexStatusLabel(index?: FileIndexStatus) {
  return index ? STATUS_LABELS[index.vector_status] : "未检查";
}
