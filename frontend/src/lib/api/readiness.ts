import { api } from "./client";

export interface ReadinessView {
  ready: boolean;
  database: { ok: boolean; detail?: string; error?: string };
  storage?: { ok: boolean; used?: number; total?: number; free?: number; free_bytes?: number; total_bytes?: number; detail?: string; error?: string };
  backup?: { ok: boolean; retained?: number; last_backup_at?: number | string; error?: string };
  checked_at?: number | string;
}

/** DB/存储 readiness；与公开 liveness `/health` 分开，供状态中心和部署 smoke 使用。 */
export const getReadiness = () => api<ReadinessView>("/ready", { cache: "no-store" });
