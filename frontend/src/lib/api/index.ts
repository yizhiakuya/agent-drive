import { api } from "./client";

export interface IndexResult {
  operation: string;
  status: string;
  items?: Record<string, unknown>[];
  [key: string]: unknown;
}

/** 直接执行文本索引业务，不创建任务记录。 */
export const indexFiles = (paths: string[], force = false) =>
  api<IndexResult>("/index/file", {
    method: "PUT",
    body: JSON.stringify({ paths, force }),
  });

/** 直接执行图片描述、视觉文档和视觉向量业务，不创建任务记录。 */
export const indexVision = (paths: string[], force = false) =>
  api<IndexResult>("/index/vision", {
    method: "PUT",
    body: JSON.stringify({ paths, force }),
  });
