"use client";

import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { uploadFile } from "@/lib/api/files";
import { emitToast } from "@/lib/events";
import { finishOperationActivity, startOperationActivity, updateOperationActivity } from "@/lib/operation-activity";

export type UploadEntry = {
  id: string;
  name: string;
  status: "queued" | "uploading" | "succeeded" | "failed" | "cancelled";
  progress: number;
  error?: string;
};

/** 统一管理上传进度、取消、重试和组件卸载清理。 */
export function useUploadQueue(
  currentPathRef: RefObject<string>,
  onSettled: () => void,
  onUploaded?: (file: File, path: string) => void | Promise<void>,
) {
  const [uploading, setUploading] = useState(false);
  const [uploadQueue, setUploadQueue] = useState<UploadEntry[]>([]);
  const cancelledRef = useRef(new Set<string>());
  const filesRef = useRef(new Map<string, File>());
  const controllersRef = useRef(new Map<string, AbortController>());
  const mountedRef = useRef(true);
  const activeOperationsRef = useRef(0);

  useEffect(() => {
    const controllers = controllersRef.current;
    const files = filesRef.current;
    const cancelled = cancelledRef.current;
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      activeOperationsRef.current = 0;
      controllers.forEach((controller) => controller.abort());
      controllers.clear();
      files.clear();
      cancelled.clear();
    };
  }, []);

  const updateQueue = useCallback((update: (current: UploadEntry[]) => UploadEntry[]) => {
    if (mountedRef.current) setUploadQueue(update);
  }, []);

  /** 上传已提交后触发附加处理；模型索引不能阻塞上传队列的完成态。 */
  const notifyUploaded = useCallback((file: File, path: string) => {
    if (!onUploaded) return;
    void Promise.resolve()
      .then(() => onUploaded(file, path))
      .catch((error) => {
        if (mountedRef.current) emitToast({ kind: "error", text: `自动索引 ${file.name} 失败: ${String(error)}` });
      });
  }, [onUploaded]);

  const beginOperation = useCallback(() => {
    activeOperationsRef.current += 1;
    if (mountedRef.current) setUploading(true);
  }, []);

  const finishOperation = useCallback(() => {
    activeOperationsRef.current = Math.max(0, activeOperationsRef.current - 1);
    if (mountedRef.current) setUploading(activeOperationsRef.current > 0);
  }, []);

  const uploadFiles = useCallback(async (files: File[]) => {
    if (files.length === 0) return;
    const entries = files.map((file, index) => ({
      id: `${Date.now()}-${index}-${file.name}-${Math.random().toString(36).slice(2, 8)}`,
      name: file.name,
      status: "queued" as const,
      progress: 0,
    }));
    entries.forEach((entry, index) => filesRef.current.set(entry.id, files[index]));
    updateQueue((current) => [...current, ...entries].slice(-12));
    beginOperation();
    const activityId = startOperationActivity({
      source: "ui",
      kind: "file-upload",
      title: files.length === 1 ? "上传文件" : "批量上传文件",
      target: `${files.length} 个文件`,
      phase: "uploading",
      message: `正在上传 0/${files.length} 项`,
      completed: 0,
      total: files.length,
    });
    let succeeded = 0;
    let failed = 0;
    let cancelled = 0;
    try {
      for (let index = 0; index < files.length; index += 1) {
        const file = files[index];
        const entry = entries[index];
        if (cancelledRef.current.has(entry.id)) {
          updateQueue((current) => current.map((item) => item.id === entry.id ? { ...item, status: "cancelled" } : item));
          filesRef.current.delete(entry.id);
          cancelled += 1;
          continue;
        }
        updateQueue((current) => current.map((item) => item.id === entry.id ? { ...item, status: "uploading" } : item));
        const controller = new AbortController();
        controllersRef.current.set(entry.id, controller);
        try {
          const uploaded = await uploadFile(
            file,
            currentPathRef.current,
            (progress) => updateQueue((current) => current.map((item) => item.id === entry.id ? { ...item, progress } : item)),
            controller.signal,
          );
          updateQueue((current) => current.map((item) => item.id === entry.id ? { ...item, status: "succeeded", progress: 100 } : item));
          filesRef.current.delete(entry.id);
          succeeded += 1;
          updateOperationActivity(activityId, {
            completed: succeeded + failed + cancelled,
            succeeded,
            failed,
            message: `已处理 ${succeeded + failed + cancelled}/${files.length} 项`,
          });
          notifyUploaded(file, uploaded.uploaded.path);
          if (mountedRef.current) emitToast({ kind: "ok", text: `已上传 ${file.name}` });
        } catch (error) {
          const wasCancelled = controller.signal.aborted;
          updateQueue((current) => current.map((item) => item.id === entry.id
            ? { ...item, status: wasCancelled ? "cancelled" : "failed", error: wasCancelled ? undefined : String(error) }
            : item));
          if (!wasCancelled) {
            failed += 1;
            updateOperationActivity(activityId, {
              completed: succeeded + failed + cancelled,
              succeeded,
              failed,
              message: `已处理 ${succeeded + failed + cancelled}/${files.length} 项`,
            });
          } else {
            cancelled += 1;
          }
          if (!wasCancelled && mountedRef.current) emitToast({ kind: "error", text: `上传 ${file.name} 失败: ${error}` });
        } finally {
          controllersRef.current.delete(entry.id);
        }
      }
      if (mountedRef.current) onSettled();
      const status = failed === 0 && cancelled === 0
        ? "succeeded"
        : succeeded === 0 && failed === 0
          ? "cancelled"
          : succeeded === 0 && cancelled === 0
            ? "failed"
            : "partial";
      finishOperationActivity(activityId, status, {
        phase: "finished",
        completed: succeeded + failed + cancelled,
        succeeded,
        failed,
        message: cancelled > 0 && failed === 0
          ? `已上传 ${succeeded}/${files.length} 项，取消 ${cancelled} 项`
          : failed === 0 ? `已上传 ${succeeded} 项` : `已上传 ${succeeded}/${files.length} 项`,
        ...(failed > 0 ? { error: `${failed} 项上传失败` } : {}),
      });
    } finally {
      finishOperation();
    }
  }, [beginOperation, currentPathRef, finishOperation, notifyUploaded, onSettled, updateQueue]);

  const cancelUpload = useCallback((id: string) => {
    cancelledRef.current.add(id);
    controllersRef.current.get(id)?.abort();
    updateQueue((current) => current.map((item) => item.id === id && item.status === "queued"
      ? { ...item, status: "cancelled" }
      : item));
  }, [updateQueue]);

  const retryUpload = useCallback(async (id: string) => {
    const file = filesRef.current.get(id);
    if (!file || activeOperationsRef.current > 0) return;
    beginOperation();
    const activityId = startOperationActivity({
      source: "ui",
      kind: "file-upload",
      title: "重试上传",
      operation: "POST /api/v1/files/upload",
      target: file.name,
      phase: "uploading",
      message: "正在重试上传",
      completed: 0,
      total: 1,
    });
    const controller = new AbortController();
    controllersRef.current.set(id, controller);
    updateQueue((current) => current.map((item) => item.id === id
      ? { ...item, status: "uploading", progress: 0, error: undefined }
      : item));
    try {
      const uploaded = await uploadFile(
        file,
        currentPathRef.current,
        (progress) => updateQueue((current) => current.map((item) => item.id === id ? { ...item, progress } : item)),
        controller.signal,
      );
      updateQueue((current) => current.map((item) => item.id === id
        ? { ...item, status: "succeeded", progress: 100 }
        : item));
      filesRef.current.delete(id);
      updateOperationActivity(activityId, { completed: 1, succeeded: 1, failed: 0, message: "重试上传已完成" });
      finishOperationActivity(activityId, "succeeded", { phase: "finished", completed: 1, succeeded: 1, failed: 0, message: "重试上传已完成" });
      notifyUploaded(file, uploaded.uploaded.path);
      if (mountedRef.current) {
        emitToast({ kind: "ok", text: `已重试上传 ${file.name}` });
        onSettled();
      }
    } catch (error) {
      const cancelled = controller.signal.aborted;
      updateQueue((current) => current.map((item) => item.id === id
        ? { ...item, status: cancelled ? "cancelled" : "failed", error: cancelled ? undefined : String(error) }
        : item));
      finishOperationActivity(activityId, cancelled ? "cancelled" : "failed", {
        phase: "finished",
        completed: cancelled ? 0 : 1,
        succeeded: 0,
        failed: cancelled ? 0 : 1,
        message: cancelled ? "重试上传已取消" : "重试上传失败",
        ...(cancelled ? {} : { error: String(error) }),
      });
      if (!cancelled && mountedRef.current) emitToast({ kind: "error", text: `重试上传 ${file.name} 失败: ${error}` });
    } finally {
      controllersRef.current.delete(id);
      finishOperation();
    }
  }, [beginOperation, currentPathRef, finishOperation, notifyUploaded, onSettled, updateQueue]);

  return { uploading, uploadQueue, uploadFiles, cancelUpload, retryUpload };
}
