import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "top.rainaki.agentdrive",
  appName: "Agent Drive",
  // 静态导出产物（next build → out/）打进 App：离线壳，API 走扫码配置的服务器
  webDir: "out",
  server: {
    androidScheme: "https",
  },
};

export default config;
