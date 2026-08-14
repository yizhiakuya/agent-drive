# frontend — Agent Drive 前端（Next.js 16）

> Next.js 16 (App Router) + React 19 + TypeScript + Tailwind v4 + zustand + Capacitor 7
> 静态导出 `out/`，由 backend（FastAPI）单服务托管；同时打包进安卓 App（Capacitor 原生壳）。

## 开发

```bash
npm install
npm run dev                    # next dev :3000（默认同源 /api/v1）
NEXT_PUBLIC_API_BASE=http://localhost:8000/api/v1 npm run dev   # 直连后端开发

npm run build                  # 静态导出 out/（backend 托管）
npm test                       # vitest（19 项：SSE 流解析 + 组件）
npx cap sync android           # 拷贝 web 资源进安卓工程（frontend/android）
```

## 结构

```
src/
├── app/                # layout（主题/安全区）+ page（认证门控/三 tab/下拉刷新）
├── components/
│   ├── chat/           # 对话面板 + 工具轨迹/计划/上下文条
│   ├── files/          # 文件页/侧栏面板
│   ├── sessions/       # 会话列表
│   ├── settings/       # LLM/向量化(仅 web)、连接 App、设备列表、相册同步(仅 App)
│   ├── onboarding/     # AI 配置向导（仅 web 显示）
│   ├── auth/           # 登录/设密、重扫码、服务器未就绪提示
│   └── PullToRefresh   # 全局下拉刷新
└── lib/
    ├── api/            # client(基座+鉴权) chat files config sessions devices auth
    ├── native/         # Capacitor 插件桥：server-config / photo-sync
    └── store events format
```

## 鉴权约定

- web/PWA：HttpOnly Cookie（登录/设密页）；401 全局拦截回登录页
- 原生 App：扫码配对换设备令牌（Bearer）；无令牌 → 重扫码页（密码登录为逃生口）
- AI 配置界面仅 web 渲染；App 内提示"配置在网页端管理"

## 相关文档

- 架构：`docs/architecture.md` / `docs/frontend-architecture.md`（历史分析存档 + 现状速览）
- 安卓原生壳：`docs/android.md` · 认证设计：`docs/security.md`