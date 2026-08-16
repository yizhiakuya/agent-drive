import { useState } from "react";
import { ServerConfig } from "@/lib/native/server-config";

/**
 * 原生端重新扫码连接：统一「忙碌态 → ServerConfig.rescan() → 失败提示」流程。
 * RescanCard（无凭据入口）与 ConnectAppCard（设置页重扫）共用；
 * 忙碌态/错误提示绑定在 hook 内部，调用方无需各自维护。
 */
export function useRescan() {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);

  async function rescan() {
    setBusy(true);
    setMsg(null);
    try {
      await ServerConfig.rescan();
    } catch (e) {
      setMsg(String(e));
    } finally {
      setBusy(false);
    }
  }

  return { busy, msg, rescan };
}
