# 前端架构

> ⚠️ **本文档正文是 2026-08-14 迁移当天做的架构分析存档**（下文目录树/行数反映迁移前 Vite 版与迁移初期状态，已不反映现状）。
> 最新结构与实现请以 [frontend/README.md](../frontend/README.md) 与代码为准。以下"现状速览"为当前（2026-08-14 晚）真实结构摘要。

## 现状速览（当前真实结构）

```
src/
├── app/                # layout.tsx（主题/安全区/manifest）+ page.tsx（认证门控 + 三 tab + 全局下拉刷新）
├── components/
│   ├── chat/           # ChatPanel（SSE 流式）+ ToolStep/ContextBar/PlanCard
│   ├── files/          # FilePage + FilePanel（文件/回收站/预览/上传）
│   ├── sessions/       # SessionList（多会话）
│   ├── settings/       # SettingsPage + ConnectAppCard(配对二维码) + DevicesCard + PhotoSyncCard(仅 App)
│   ├── onboarding/     # AI 配置向导（仅 web 渲染）
│   ├── auth/           # LoginCard(登录/设密) / RescanCard(重扫码) / ServerNotReadyCard
│   └── PullToRefresh   # 全局下拉刷新（触屏手势）
└── lib/
    ├── api/            # client(基座+鉴权头+401 拦截) chat files config sessions devices auth
    ├── native/         # Capacitor 插件桥：server-config / photo-sync
    └── store(zustand)  events(事件总线)  format
```

**演进要点**（相对迁移当天）：
- 认证体系：登录/设密页、扫码配对、401 全局拦截、原生端"重扫码"模式
- 原生桥：ServerConfig / PhotoSync 插件（服务器地址、设备令牌、同步进度事件）
- 事件总线扩展：`agent-drive:refresh`（下拉刷新全局联动）+ 既有 files-changed/toast
- AI 配置界面 web-only；App 内仅显示提示
- Capacitor 7（frontend/android）打包 web 资源，插件注册须在 super.onCreate 之前

---

## 历史分析存档（2026-08-14 迁移时）

> 技术栈（当时）：Next.js 16 (App Router) + React 19 + TypeScript + Tailwind v4 + zustand + vitest
> 部署：`output: 'export'` 静态导出 out/，由 backend（FastAPI）单服务托管
> 演进：2026-08-14 从 React18+Vite 迁移，架构分析清单问题 ①-④ 已随迁移清零

## 一、目录结构与分层

```
src/
├── main.jsx                    # React 入口（挂载 App）
├── App.jsx (128)               # 根组件：全局状态 + 三视图 tab
├── styles.css (~700)           # 单文件全局样式：CSS 变量设计系统
├── api/                        # API 层（薄封装，逐模块）
│   ├── client.js (45)          # fetch 基座 api() + 【半废弃】重复导出
│   ├── chat.js (48)            # SSE 流解析（chatStream）
│   ├── config.js (6)           # 状态/LLM 配置
│   ├── files.js (11)           # 列表/上传
│   └── sessions.js (7)         # 会话 CRUD
└── components/
    ├── chat/ChatPanel.jsx (452)      # 对话容器 + 导出展示组件
    ├── files/FilePage.jsx (189)      # 全宽文件管理页
    ├── files/FilePanel.jsx (162)     # 对话页右侧面板
    ├── onboarding/Onboarding.jsx (120)
    ├── sessions/SessionList.jsx (43)
    └── settings/SettingsPage.jsx (105)
```

**分层纪律**：components → api → HTTP。组件原则上不直接 fetch（例外见 §三-1）。

## 二、核心模式

### 2.1 状态管理（无库，3 种模式）
| 模式 | 载体 | 例子 |
|------|------|------|
| props 下发 | App → 子组件 | status/sessions/sessionId/tab |
| 组件本地 state | useState 函数式更新 | ChatPanel 的 messages/busy/pending |
| 事件总线 | window CustomEvent | `agent-drive:files-changed` / `agent-drive:toast` |

事件总线是亮点：ChatPanel 广播"文件变了"→ FilePanel/FilePage 各自监听刷新，**跨分支组件零 props 穿透**。

### 2.2 数据流（对话主链路）
```
用户输入 → chatStream(SSE) → onEvent 回调
  ├─ "text"        → setMessages 函数式更新（流式追加）
  ├─ "tool_start"  → 插入 tool_step 节点（🔄 执行中）
  ├─ "tool_trace"  → 更新节点状态(✅/❌) + 广播 files-changed
  └─ "done"        → pending 确认/plan/contextUsage/session_id
AbortController: 切会话中止在途流（防串消息）+ 停止按钮复用
```

### 2.3 路由
三视图 tab（chat/files/settings），**state 切换而非 URL 路由**——无 react-router。

### 2.4 样式
单文件 styles.css：`:root` 设计变量（色板/圆角/阴影/动效）+ BEM 式前缀类名（fp-/pv-/set-/sl-），灰白 light 主题一致。

### 2.5 测试策略
- vitest + testing-library：SSE 解析单测（跨 chunk 缓冲/AbortError）+ 小组件导出测试
- **务实模式**：把纯展示组件（ToolStep/ContextBar/PlanCard/fmtToolArgs）从 ChatPanel 导出独立测，避免 mock 大组件

## 三、问题清单（迁移后状态：2026-08-14 复核）

> ✅ = 已随 Next.js 迁移修复；⏳ = 仍未处理。

1. ✅ **client.js 半废弃层**：已重写为 client.ts 单一真相源 + apiPath()，死导出/死路径清零。
2. ✅ **组件绕层 fetch**：API 层已补全（files.info/raw/mkdir/rename/move/copy/delete/trash、config.getConfig/saveEmbeddings），组件全部走封装。
3. ✅ **ChatPanel 452 行**：已拆分 ChatPanel + ToolStep/ContextBar/PlanCard + lib/format.ts（现各 <280 行）。
4. ✅ **事件总线魔法字符串**：lib/events.ts 类型化常量（EV.filesChanged/EV.toast + emit* 函数）。
5. ⏳ **无 URL 路由**：仍是 tab state（个人项目可接受；若需分享链接再上 hash 路由）。
6. ⏳ **上传无进度**：仍 spinner（share target 场景浏览器自带进度；后续可 XHR）。
7. ✅ 小项：Markdown 用全局 .markdown-body 类；Tailwind 取代单文件样式；SSE 错误已有友好文案。

## 四、优点（保持）

- 薄 API 层 + 组件分层清晰，新人可 10 分钟上手
- SSE 封装干净（buffer 跨 chunk 正确处理）
- 事件总线解耦聊天↔文件面板联动
- 函数式 setState 全链路防闭包过期
- 生产/开发双形态（vite dev / dist 由 backend SPA 托管）

## 五、演进建议（按投入排序）

1. **清 client.js**：删死导出（getStatus 死路径等），api() 只留基座；半小时
2. **API 层补全**：files.js 加 getFileInfo/getRawUrl、config.js 加 getConfig/saveEmbeddings，
   组件不再绕层；1 小时
3. **ChatPanel 拆分**：format.js + ToolStep.jsx/ContextBar.jsx/PlanCard.jsx 独立文件；1 小时
4. **事件常量**：events.js 导出 BUS 常量；半小时
5. **URL 路由**：react-router 或 URL hash 同步 tab（先 hash，零依赖）；半天
6. **上传进度**：XHR uploadFile(onProgress)；1 小时
7. 样式拆分/状态库（zustand）——**暂不需要**，复杂度未到
