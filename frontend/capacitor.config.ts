import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "top.rainaki.agentdrive",
  appName: "Agent Drive",
  // 静态导出产物（next build → out/）打进 App：离线壳，API 走扫码配置的服务器
  webDir: "out",
  server: {
    androidScheme: "https",
  },
  android: {
    // Android 15 强制 edge-to-edge：强制给 WebView 加系统栏边距，
    // 否则头部 UI 会与状态栏（时间/信号）重叠。PWA 浏览器端不受影响。
    adjustMarginsForEdgeToEdge: "force",
  },
};

export default config;
