import { useCallback, useEffect, useRef, useState } from "react";
import { getTaskDetail, type TaskDetailResponse } from "@/lib/api/tasks";

/** 管理任务详情展开状态、按任务加载详情，以及详情请求的迟到响应隔离。 */
export function useTaskDetails() {
  const [expandedTaskId, setExpandedTaskId] = useState<string | null>(null);
  const [taskDetails, setTaskDetails] = useState<Record<string, TaskDetailResponse>>({});
  const [detailErrors, setDetailErrors] = useState<Record<string, string>>({});
  const [detailLoadingId, setDetailLoadingId] = useState<string | null>(null);
  const requestRef = useRef(0);

  const loadDetail = useCallback(async (id: string) => {
    // 同一 hook 内只认最后一次请求；切换展开项或刷新详情时，旧响应不得污染当前缓存/错误状态。
    const request = ++requestRef.current;
    setDetailLoadingId(id);
    setDetailErrors((current) => {
      const next = { ...current };
      delete next[id];
      return next;
    });
    try {
      const data = await getTaskDetail(id);
      if (request !== requestRef.current) return;
      setTaskDetails((current) => ({ ...current, [id]: data }));
    } catch (reason) {
      if (request === requestRef.current) {
        setDetailErrors((current) => ({
          ...current,
          [id]: reason instanceof Error ? reason.message : String(reason),
        }));
      }
    } finally {
      if (request === requestRef.current) setDetailLoadingId(null);
    }
  }, []);

  const toggleDetails = useCallback((id: string) => {
    if (expandedTaskId === id) {
      setExpandedTaskId(null);
      return;
    }
    setExpandedTaskId(id);
    // 首次读取和任务事件后的刷新由页面统一触发，避免展开时与列表刷新并发请求同一详情。
  }, [expandedTaskId]);

  useEffect(() => () => {
    requestRef.current += 1;
  }, []);

  return {
    expandedTaskId,
    taskDetails,
    detailErrors,
    detailLoadingId,
    loadDetail,
    toggleDetails,
  };
}
