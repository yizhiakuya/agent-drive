import { registerPlugin } from "@capacitor/core";

export interface PhotoSyncStatus {
  configured: boolean;
  permissionGranted: boolean;
  enabled: boolean;
  wifiOnly: boolean;
  /** 周期小时数（原生端下限 0.25 = 15 分钟） */
  intervalHours: number;
  targetFolder: string;
  /** epoch 秒；null = 从未同步 */
  lastSyncAt: number | null;
  lastSyncedCount: number;
  lastError: string | null;
  lastScanned: number;
  lastUploaded: number;
  lastDeduped: number;
  lastSkipped: number;
  lastFailed: number;
  lastRetryable: number;
  notificationsEnabled: boolean;
  lastNotification: boolean;
  // 实时进度（与 syncProgress 事件同构）
  running: boolean;
  phase: string; // scanning / uploading / done / idle
  currentFile: string;
  uploaded: number;
  total: number;
}

export interface SyncProgress {
  running: boolean;
  phase: string;
  currentFile: string;
  uploaded: number;
  total: number;
}

interface PhotoSyncOptions {
  enabled?: boolean;
  wifiOnly?: boolean;
  intervalHours?: number;
  targetFolder?: string;
}

interface PhotoSyncPlugin {
  getStatus(): Promise<PhotoSyncStatus>;
  configure(options: PhotoSyncOptions): Promise<PhotoSyncStatus>;
  syncNow(): Promise<{ started: boolean; reason?: string }>;
  requestPermissions(): Promise<{ granted: boolean }>;
  openNotificationSettings(): Promise<{ opened: boolean }>;
  /** 同步进度实时事件（原生逐张广播） */
  addListener(
    eventName: "syncProgress",
    listener: (data: SyncProgress) => void,
  ): Promise<{ remove: () => Promise<void> }>;
}

export const PhotoSync = registerPlugin<PhotoSyncPlugin>("PhotoSync");
