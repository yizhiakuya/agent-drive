// 事件总线常量（类型化，防魔法字符串拼错）
export const EV = {
  filesChanged: "agent-drive:files-changed",
  toast: "agent-drive:toast",
  refresh: "agent-drive:refresh",
} as const;

export interface ToastDetail {
  kind?: "ok" | "error";
  text: string;
}

export function emitFilesChanged() {
  window.dispatchEvent(new CustomEvent(EV.filesChanged));
}

export function emitToast(detail: ToastDetail) {
  window.dispatchEvent(new CustomEvent(EV.toast, { detail }));
}

/** 全局刷新信号（下拉刷新触发）：各面板监听后重新拉取数据 */
export function emitRefresh() {
  window.dispatchEvent(new CustomEvent(EV.refresh));
}
