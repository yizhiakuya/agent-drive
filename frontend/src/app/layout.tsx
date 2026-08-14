import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Agent Drive — AI 个人网盘",
  description: "以 AI 为中心的个人云盘：配置 Agent，一切通过对话完成",
  manifest: "/manifest.webmanifest",
  appleWebApp: {
    capable: true,
    title: "Agent Drive",
    statusBarStyle: "default",
  },
  icons: [{ url: "/icon-192.png", sizes: "192x192", type: "image/png" }],
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  viewportFit: "cover",
};

function SWRegister() {
  return (
    <script
      dangerouslySetInnerHTML={{
        __html: `if ("serviceWorker" in navigator && !location.hostname.includes("localhost")) {
  window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));
}`,
      }}
    />
  );
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <body className="min-h-full flex flex-col">
        <SWRegister />
        {children}
      </body>
    </html>
  );
}
