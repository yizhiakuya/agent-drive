# Agent Drive 前端

Next.js 16 App Router + React 19 + TypeScript 5 + Tailwind 4 + shadcn/ui + zustand + Capacitor 7。

生产使用 Next 静态导出 `out/`，由 Java WebFlux API 托管；Android 壳通过 Capacitor 复用同一份 web 资源。分层和请求生命周期见 [`docs/frontend-architecture.md`](../docs/frontend-architecture.md)，UI 约定见 [`docs/frontend-design.md`](../docs/frontend-design.md)。

## 开发与验证

```bash
npm install
npm run dev -- -p 3333

npm run lint
npm test
npm run build
npm run verify:build
```

默认开发页面使用同源 `/api/v1`。直连本地 API 时设置 `NEXT_PUBLIC_API_BASE=http://localhost:8000/api/v1`。`npm run build` 输出静态 `out/`，不要手工删除 `.well-known/assetlinks.json`。

Android 资源同步：

```bash
npx cap sync android
cd android
gradlew.bat testDebugUnitTest
```

## 当前结构

```text
src/
├── app/              # layout、viewport、认证门控和主页面
├── components/
│   ├── ui/           # shadcn/ui 基础控件
│   ├── auth/         # 登录、设密、重扫码
│   ├── chat/         # ChatPanel、工具轨迹、SSE 流状态
│   ├── files/        # 文件页、预览、详情、回收站
│   ├── sessions/     # 会话列表
│   ├── settings/     # provider、设备、同步设置
│   └── tasks/        # 任务中心
└── lib/
    ├── api/          # 类型化 API client
    ├── native/       # ServerConfig、PhotoSync 插件桥
    ├── store.ts      # zustand 状态和 frontend action 队列
    ├── events.ts     # 类型化事件总线
    └── format.ts     # 展示格式化
```

## 关键约定

- 新控件必须复用 `components/ui`；主题 token 只来自 `app/globals.css` 的 `:root`。
- API client 负责 base、身份、401、GET cache generation 和写请求失效；业务组件不直接 `fetch`。
- Chat SSE 解析支持跨 chunk 换行/UTF-8、多行 data 和尾事件；`useChatStream` 负责 Abort、stream generation、reasoning 和 80ms 帧节流。
- `FilePage` 的列表、详情、全文和索引请求使用请求代次，迟到响应不能覆盖新目录或新选中文件。
- Web 使用 HttpOnly Cookie，App 使用扫码兑换的 Bearer 设备令牌；AI 配置只在 Web 设置页提供。
- 修改静态代码后必须递增 `public/sw.js` 的 cache 版本。生产发布使用顶层 `scripts/deploy.ps1`，不要用 `out\*` 通配符上传。
