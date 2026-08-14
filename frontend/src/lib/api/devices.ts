import { api } from "./client";

export interface DeviceSyncState {
  enabled: boolean;
  wifi_only: boolean;
  interval_hours: number;
  last_sync_at: number | null;
  last_synced_count: number;
  last_error: string | null;
}

export interface DeviceInfo {
  device_id: string;
  name: string;
  model: string;
  platform: string;
  app_version: string;
  first_seen: number;
  last_seen: number;
  sync?: DeviceSyncState;
}

export const getDevices = () => api<{ devices: DeviceInfo[] }>("/devices");
export const removeDevice = (id: string) =>
  api(`/devices/${encodeURIComponent(id)}`, { method: "DELETE" });
