import { indexFiles, indexVision, vectorize, type IndexResult } from "@/lib/api/index";
import { emitFilesChanged } from "@/lib/events";
import { finishOperationActivity, startOperationActivity, updateOperationActivity } from "@/lib/operation-activity";
import { getIndexPolicy } from "@/lib/index-policy";

function failure(result: IndexResult): string | null {
  if (result.vectorized === false) return String(result.reason || result.error || "向量化未完成");
  const failedItem = result.items?.find((item) => item.indexed === false
    || (item.embedding && typeof item.embedding === "object"
      && (item.embedding as { vectorized?: unknown }).vectorized === false)
    || item.status === "error");
  if (failedItem) return String(failedItem.error || failedItem.status || "索引项失败");
  if (result.ok === false || result.status === "failed" || result.status === "partial") {
    return String(result.error || result.reason || "索引未完整完成");
  }
  return null;
}

/**
 * 按当前智能摄入策略处理一个刚上传文件。
 * 手动策略不调用模型；图片策略只处理图片；自动策略处理文本和图片。
 */
export async function autoIndexUploadedFile(file: File, filePath: string): Promise<void> {
  const policy = getIndexPolicy();
  if (policy === "manual") return;
  const isImage = file.type.startsWith("image/") || /\.(png|jpe?g|gif|webp|bmp)$/i.test(filePath);
  if (policy === "images" && !isImage) return;

  const activityId = startOperationActivity({
    source: "ui",
    kind: isImage ? "index-vision" : "index-vector",
    title: isImage ? "自动理解图片" : "自动建立索引",
    operation: isImage ? "PUT /api/v1/index/vision" : "PUT /api/v1/index/file",
    target: filePath,
    phase: isImage ? "vision" : "extracting",
    message: isImage ? "正在生成图片内容描述" : "正在抽取文件正文",
  });
  try {
    let result: IndexResult;
    if (isImage) {
      result = await indexVision([filePath]);
    } else {
      const extracted = await indexFiles([filePath]);
      const extractionFailure = failure(extracted);
      if (extractionFailure) throw new Error(extractionFailure);
      updateOperationActivity(activityId, { phase: "embedding", message: "正在生成文本向量" });
      result = await vectorize([filePath]);
    }
    const error = failure(result);
    if (error) throw new Error(error);
    finishOperationActivity(activityId, "succeeded", {
      phase: "finished",
      message: isImage ? "图片已理解并可搜索" : "文件已建立索引并可搜索",
    });
    emitFilesChanged();
  } catch (error) {
    finishOperationActivity(activityId, "failed", {
      phase: "finished",
      message: "自动索引失败",
      error: error instanceof Error ? error.message : String(error),
    });
    throw error;
  }
}
