# frontend — Agent Drive 前端（Next.js 16）

> Next.js 16 (App Router) + React 19 + TypeScript + Tailwind v4 + shadcn/ui + zustand + Capacitor 7
> UI 规范见 docs/frontend-design.md；新控件一律 components/ui/，禁止内联自造。
> 静态导出 `out/`，由 backend（FastAPI）单服务托管；同时打包进安卓 App（Capacitor 原生壳）。

## 开发

```bash
npm install
npm run dev                    # next dev :3000（默认同源 /api/v1）
NEXT_PUBLIC_API_BASE=http://localhost:8000/api/v1 npm run dev   # 直连后端开发

npm run build                  # 静态导出 out/（backend 托管）
npm test                       # vitest（SSE、API 身份/缓存竞态、上传与组件）
npx cap sync android           # 拷贝 web 资源进安卓工程（frontend/android）
```

## 结构

```
src/
├── app/                # layout（主题/安全区）+ page（认证门控/三 tab/下拉刷新）
├── components/
│   ├── ui/             # shadcn/ui 组件库（button/input/select/combobox/card/badge/switch/skeleton/alert/separator）
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

- web/PWA：HttpOnly Cookie（登录/设密页）；普通 API、上传与 Chat SSE 的 401 都通过 `EV.unauthorized` 全局回登录页；旧身份迟到的 401 不影响已切换的新身份
- API GET 缓存按 base + credential generation + cache generation + path 隔离；凭据切换和每个写请求的开始/结束（含 HTTP 错误、网络失败和 Abort）各自失效，交错写也不会让旧 GET 快照继续缓存
- Chat SSE 解析支持 LF/CRLF/CR、跨 chunk 换行与 UTF-8、多行 `data:`、流末尾无空行事件；非 2xx 保留字符串或结构化后端 `detail` 为 `ApiError`
- 原生 App：扫码配对换设备令牌（Bearer）；配置存独立 EncryptedSharedPreferences，升级兼容旧明文与 1.0.27 同文件密文（同键密文优先于更老明文残留；独立新旧密文冲突则保留双方并失败关闭）；存储错误会 reject 并显示而不会降级明文或静默使用默认地址。无令牌 → 重扫码页（密码登录为逃生口）。登出先清除加密令牌：离线/5xx 明示服务端吊销状态未知，401/403 视为凭据已不可用
- AI 配置界面仅 web 渲染；App 内提示"配置在网页端管理"

## 相关文档

- 架构：`docs/architecture.md` / `docs/frontend-architecture.md`（历史分析存档 + 现状速览）
- 安卓原生壳：`docs/android.md` · 认证设计：`docs/security.md`