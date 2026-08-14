import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 纯静态导出：backend 单服务托管 out/（与 vite dist 同形态）
  output: "export",
  trailingSlash: false,
};

export default nextConfig;
