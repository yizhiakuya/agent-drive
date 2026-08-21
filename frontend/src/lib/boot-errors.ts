export type BootFailure = "unauthorized" | "unavailable";

/** 跨 bundle 提取 API status；未知错误按服务不可用处理，不能伪装成凭据失效。 */
export function classifyBootFailure(errorOrStatus: unknown): BootFailure {
  const status = typeof errorOrStatus === "number"
    ? errorOrStatus
    : typeof errorOrStatus === "object" && errorOrStatus !== null && "status" in errorOrStatus
      && typeof (errorOrStatus as { status?: unknown }).status === "number"
      ? (errorOrStatus as { status: number }).status
      : null;
  return status === 401 || status === 403 ? "unauthorized" : "unavailable";
}

/** 把启动失败映射为 UI 状态；原生端的未授权需要重新扫码。 */
export function authModeForBootFailure(native: boolean, errorOrStatus: unknown) {
  if (classifyBootFailure(errorOrStatus) === "unauthorized") {
    return native ? "rescan" as const : "login" as const;
  }
  return "server-error" as const;
}
