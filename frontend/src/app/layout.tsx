import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Agent Drive — AI 个人网盘",
  description: "以 AI 为中心的个人云盘：配置 Agent，一切通过对话完成",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
