import { Capacitor, registerPlugin } from "@capacitor/core";

/** 原生端服务器连接配置（扫码获得，存 SharedPreferences）。 */
export interface ServerConfigPlugin {
  getServer(): Promise<{ server: string | null }>;
  setServer(options: { server: string }): Promise<{ server: string }>;
  hasServer(): Promise<{ has: boolean }>;
  /** 原生端重新扫码连接：成功后自动保存并重载 web 界面 */
  rescan(): Promise<{ started: boolean }>;
}

export const ServerConfig = registerPlugin<ServerConfigPlugin>("ServerConfig");

export const isNativePlatform = () => Capacitor.isNativePlatform();

/** 当前服务器地址：原生 App = 扫码配置；web = 当前页面 origin。 */
export async function currentServer(): Promise<string> {
  if (Capacitor.isNativePlatform()) {
    const { server } = await ServerConfig.getServer();
    return server ?? "";
  }
  return typeof window !== "undefined" ? window.location.origin : "";
}
