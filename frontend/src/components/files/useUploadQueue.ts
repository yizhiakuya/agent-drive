"use client";

import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { uploadFile } from "@/lib/api/files";
import { emitToast } from "@/lib/events";

export type UploadEntry = {
  id: string;
  name: string;
  status: "queued" | "uploading" | "succeeded" | "failed" | "cancelled";
  progress: number;
  error?: string;
};

/** 统一管理上传进度、取消、重试和组件卸载清理。 */
export function useUploadQueue(currentPathRef: RefObject<string>, onSettled: () => void) {
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
    try {
      for (let index = 0; index < files.length; index += 1) {
        const file = files[index];
        const entry = entries[index];
        if (cancelledRef.current.has(entry.id)) {
          updateQueue((current) => current.map((item) => item.id === entry.id ? { ...item, status: "cancelled" } : item));
          filesRef.current.delete(entry.id);
          continue;
        }
        updateQueue((current) => current.map((item) => item.id === entry.id ? { ...item, status: "uploading" } : item));
        const controller = new AbortController();
        controllersRef.current.set(entry.id, controller);
        try {
          await uploadFile(
            file,
            currentPathRef.current,
            (progress) => updateQueue((current) => current.map((item) => item.id === entry.id ? { ...item, progress } : item)),
            controller.signal,
          );
          updateQueue((current) => current.map((item) => item.id === entry.id ? { ...item, status: "succeeded", progress: 100 } : item));
          filesRef.current.delete(entry.id);
          if (mountedRef.current) emitToast({ kind: "ok", text: `已上传 ${file.name}` });
        } catch (error) {
          const cancelled = controller.signal.aborted;
          updateQueue((current) => current.map((item) => item.id === entry.id
            ? { ...item, status: cancelled ? "cancelled" : "failed", error: cancelled ? undefined : String(error) }
            : item));
          if (!cancelled && mountedRef.current) emitToast({ kind: "error", text: `上传 ${file.name} 失败: ${error}` });
        } finally {
          controllersRef.current.delete(entry.id);
        }
      }
      if (mountedRef.current) onSettled();
    } finally {
      finishOperation();
    }
  }, [beginOperation, currentPathRef, finishOperation, onSettled, updateQueue]);

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
    const controller = new AbortController();
    controllersRef.current.set(id, controller);
    updateQueue((current) => current.map((item) => item.id === id
      ? { ...item, status: "uploading", progress: 0, error: undefined }
      : item));
    try {
      await uploadFile(
        file,
        currentPathRef.current,
        (progress) => updateQueue((current) => current.map((item) => item.id === id ? { ...item, progress } : item)),
        controller.signal,
      );
      updateQueue((current) => current.map((item) => item.id === id
        ? { ...item, status: "succeeded", progress: 100 }
        : item));
      filesRef.current.delete(id);
      if (mountedRef.current) {
        emitToast({ kind: "ok", text: `已重试上传 ${file.name}` });
        onSettled();
      }
    } catch (error) {
      const cancelled = controller.signal.aborted;
      updateQueue((current) => current.map((item) => item.id === id
        ? { ...item, status: cancelled ? "cancelled" : "failed", error: cancelled ? undefined : String(error) }
        : item));
      if (!cancelled && mountedRef.current) emitToast({ kind: "error", text: `重试上传 ${file.name} 失败: ${error}` });
    } finally {
      controllersRef.current.delete(id);
      finishOperation();
    }
  }, [beginOperation, currentPathRef, finishOperation, onSettled, updateQueue]);

  return { uploading, uploadQueue, uploadFiles, cancelUpload, retryUpload };
}
