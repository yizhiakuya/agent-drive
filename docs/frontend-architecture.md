# 前端架构

> 现行说明（2026-08-22）。前端是 Next.js 16 + React 19 + TypeScript 5 的静态导出应用；生产由 Java WebFlux 托管 `frontend/out`，Android 通过 Capacitor 7 复用同一套 web UI。

## 1. 目录与分层

```text
frontend/src/
├── app/                 # layout、主题、viewport、认证门控和主页面
├── components/
│   ├── auth/            # 登录/设密、重扫码、服务未就绪
│   ├── chat/            # ChatPanel、模型目录、文件引用、工具轨迹和流事件/状态/帧模块
│   ├── files/           # FilePage、上传队列、FilePanel、FilePreview、FileDetails
│   ├── sessions/        # 会话列表和摘要刷新
│   ├── settings/        # provider、embedding、Skill、设备、同步和系统状态
│   ├── workspace/       # 工作区面板收缩、拖拽调整和键盘分隔轨道
│   ├── onboarding/      # web-only AI 配置引导
│   ├── ui/              # shadcn/ui 基础控件
│   └── PullToRefresh/   # Web/App 共用的下拉刷新
└── lib/
    ├── api/             # client、auth、chat、files、config、index 等 API 封装
    ├── native/          # ServerConfig、PhotoSync Capacitor 桥
    ├── store.ts         # zustand 全局状态和前端动作队列
    ├── events.ts        # 类型化窗口事件总线
    ├── operation-activity.ts # UI/Agent 长操作活动状态中心
    ├── index-policy.ts  # 浏览器内智能摄入策略
    ├── auto-index.ts    # 上传成功后的统一自动索引入口
    └── format.ts        # 时间、大小、工具参数等展示格式化
```

组件只通过 `lib/api` 访问后端，不在业务组件中直接 `fetch`。通用控件必须复用 `components/ui`；主题 token 的唯一来源是 `app/globals.css` 的 `:root`。

## 2. 页面与状态

`app/page.tsx` 负责认证门控和 Chat/File/Settings 三个主视图的切换。启动检查把 401/403 与服务故障分开：前者进入 web 登录或原生重扫码，5xx、网络、JSON 解析和布尔字段契约错误进入保留凭据的 `server-error`，通过同一入口重新执行完整认证与配置检查。整页 Skeleton 只由初次 `authMode=loading` 控制；下拉刷新仍更新 store.loading，但工作区继续挂载，因此未发送草稿、SSE 和工具步骤不会丢失。对话主区使用 CSS hidden 保持 `ChatPanel` 挂载。会话列表按 session ID 去重，并在空标题摘要完成后按请求序列重新加载。工具轨迹默认以用户级 Activity 标题展示（例如“统计文件”“读取文件内容”“更新索引”），原始 `backend_api` operation 仅作为次级技术标识，展开后仍可审计完整参数和结构化结果。

对话工作区的会话列表和桌面文件栏由 `app/page.tsx` 统一维护布局状态；`lib/workspace-layout.ts` 负责版本化 localStorage 的读写与宽度边界，`components/workspace/PanelResizeHandle.tsx` 负责鼠标拖拽、键盘调整和收缩入口。完整会话侧栏在 `xl` 以下隐藏并改用抽屉，文件栏同样在 `xl` 以下隐藏，避免中等宽度挤压聊天 composer；收缩状态不会卸载 ChatPanel，也不会丢失已打开的文件预览状态。设置页的 `SystemStatusCenter` 负责并行健康探测和局部错误展示。

工作区头部的 `OperationActivityCenter` 使用 `lib/operation-activity.ts` 的 `useSyncExternalStore` 外部状态，避免高频操作进度更新导致整个工作区重新渲染。运行中活动只在当前页面进程内保存，完成/部分完成/失败结果按版本化 localStorage 保存有限条目；Toast 只作短摘要。FilePage 的上传、索引和批量 mutation、Chat SSE 的 backend 文件/index mutation 都通过该 store 记录统一生命周期。`index-policy.ts` 保存手动/图片/全部文件三档智能摄入策略；`auto-index.ts` 只在上传 API 成功后触发，索引 promise 不阻塞上传队列，失败由活动中心和 toast 共同反馈。

跨组件刷新使用 `lib/events.ts` 中的类型化事件：

- `agent-drive:refresh`：全局下拉刷新；
- `agent-drive:files-changed`：文件 mutation 或索引入队后刷新文件列表；
- `agent-drive:toast`：跨页面反馈；
- `agent-drive:unauthorized`：当前身份失效，回到登录/重扫码流程。

全局 API client 将 API base、credential generation、cache generation 和 path 纳入 GET cache key。凭据切换、写请求开始/结束、HTTP 错误、网络异常和 Abort 都会使对应缓存失效；旧身份的迟到响应不能写入新状态。

## 3. Chat 流

`useChatStream` 负责编排请求生命周期，纯逻辑按职责拆分。流式忙碌状态只由这个 hook 持有，ChatPanel 不再维护第二份 `busy` 状态，避免发送按钮、停止按钮和流结束回调出现状态分叉：

- `chat-stream-events.ts`：context/text/reasoning/tool_start/tool_progress/tool_trace 等 SSE 事件校验和映射；
- `chat-stream-state.ts`：上下文注入顺序、消息、reasoning、工具轮次和终态状态转换；
- `chat-stream-frame.ts`：80ms 批量刷新、工具轮次边界和最终冲刷。
- `chat-stream-dispatch.ts`：把已校验事件分发到消息、计划和前端动作处理器；`useChatStream` 只负责请求生命周期。
- `useModelCatalog.ts`：独立管理模型目录、能力映射和配置变化时的迟到响应隔离；`AssistantMarkdown.tsx` 与 `FileMentionPicker.tsx` 分别负责安全文件引用渲染和候选列表，避免把这些职责继续堆入 ChatPanel。
- ChatPanel 的会话滚动策略按状态收敛：历史加载完成和会话切换默认定位底部；活动流的消息、reasoning、工具步骤和计划更新持续跟随底部；非运行状态用户手动上滑后不抢回位置，只显示 composer 上沿的圆形“回到最新消息”按钮。
- ChatPanel 的模型 Combobox 按需调用 `POST /config/models` 读取当前 Provider 的模型目录；响应同时提供按模型 ID 编排的 `model_capabilities`，显式 Provider 能力字段优先，未知模型保守视为不支持图片。选中的 `model` 只随本轮 `/chat/stream` 请求发送，不修改设置页默认配置。聊天内联图片保持原始字节，不在浏览器或 Java 侧缩放/重编码；runtime 以 LangChain4j `ImageContent.DetailLevel.HIGH` 请求较高视觉细节，视觉索引的 OpenAI 兼容请求也设置 `image_url.detail=high`，最终 Provider 是否内部缩放/分块由其协议决定。Onboarding 使用同一目录接口完成首次模型选择，并在协议、地址或 API key 变化时使旧请求失效；协议或地址变化还会销毁旧 key 草稿。
- Skill 正文由后端按会话恢复：前端不把工具轨迹伪装成客户端 history；服务端从 owner transcript 读取权威 user/assistant history，并识别已成功读取的 Skill，下一轮直接注入当前版本正文。已加载 Skill 有数量/字符预算，目录标记为“已加载”，因此模型不会重复调用 `read_skill`。
- 输入区的 `@` 选择器通过 name 搜索加载 owner 文件/文件夹：文件点击即引用，文件夹点击进入目录浏览，目录行和浏览头部另提供“引用文件夹”动作；浏览状态用路径栈和请求代次隔离迟到响应，Escape 返回上级或关闭候选层。文件选择器附件走普通上传接口后加入 `file_context`；桌面右侧 `FilePanel` 的文件/文件夹项通过受校验的 `application/x-agent-drive-file` 拖拽数据投放到 ChatPanel composer，同样直接加入 owner-scoped `file_context`，不重复上传或复制文件；composer 显示拖拽高亮。剪贴板图片只读入内存并以受限 Base64 `inline_images` 随本轮请求发送，单张原图上限 50 MiB，不创建聊天附件路径，发送前按当前模型图片能力校验。浏览器同时提供 `clipboard.items` 与 `clipboard.files` 时以前者作为唯一图片来源，只有前者未能读取图片才回退到后者，避免同一张图因元数据差异重复附加；草稿缩略图和当前用户消息都可点击打开原图预览，预览数据只保留在当前浏览器内存中，API 的 Base64 `data` 不进入客户端 history 或服务端 transcript；预览支持遮罩、关闭按钮和 Escape 收起。文件 context 以 `untrusted_data` 信任等级展示，回答中的 `[[file:path]]` / `[[folder:path]]` 使用固定内部 HTTPS 哨兵链接，经本地 registry、schema 和路径校验后派发已登记的文件打开动作。
- 输入区权限控件支持 `请求批准`、`帮我批准`（默认）和 `完全访问` 三档，选择保存在当前浏览器并随 `/chat/stream` 的 `permission_mode` 发送。后端按读写方法和 `green/yellow/red` 风险决定确认：请求批准模式拦截非读取调用，但绿色内部前端导航和会话 `plan` 辅助工具不拦截；帮我批准模式只拦截 red，完全访问按用户授权直接执行已登记 operation；ask/auto 下 red 仍走服务端一次性签名确认。
- 上下文使用量紧跟输入区顶部的推理层级，以紧凑圆环、已用/总量和下拉箭头显示；默认只占一个控制位，点击原生 `<details>` 后展开窗口上限、已用、可用空间和本轮输入/输出详情。Java runtime 优先使用 Provider 返回的 `TokenUsage.inputTokenCount`，流式 Provider 未回传时按已组装消息做保守估算，不能伪报 0；点击外部区域或 Escape 会关闭浮层。颜色按 50%/80% 阈值映射 warn/danger，完整数据保留在可访问标签和悬浮提示中。

解析器支持 LF/CRLF/CR、跨 chunk 换行、跨 chunk UTF-8、多行 `data:` 和没有终止空行的尾事件。活动流以 session key 保存在 hook 的 Map 中；切换会话标记 detached 但保持网络读取，text/reasoning 帧用当前 session 检查隔离 UI 写入，context/tool 事件只写所属会话视图，返回原会话或结束后从持久历史收敛。初始请求从 `X-Session-ID` 收养服务端 session，页面把当前 ID 持久化；重新挂载时先查询 `/chat/{sessionId}/active`，活动 relay 通过 `/chat/{sessionId}/stream` 回放并继续渲染，结束后重新读取 `no-store` 历史。当前会话 stop 才调用 cancel；组件卸载只 Abort 浏览器订阅，不能把刷新误报为用户取消。流异常收尾仍先同步 flush/cancel，再清理空助手占位和追加错误消息；错误事件带服务端 session ID 时，新会话立即收养该 ID，后台会话只刷新列表而不污染当前视图。

ChatPanel 读取会话历史和模型目录时各自维护请求代次，并在提交响应前同时确认代次和当前 session/config 边界。切换会话会立即显示目标历史，但不会终止原会话；后台完成时刷新会话列表，回到原会话时重拉已持久化的 assistant/context/tool 记录。模型请求的历史由服务端 owner/session transcript 组装，客户端 history 仅用于兼容旧 runtime。模型配置变化会使进行中的模型目录请求失效。SettingsPage 对 LLM/视觉模型探测采用同样的请求代次规则和密钥草稿销毁规则；已存 Key 只在点击眼睛后按需读取，配置边界变化会使在途回显失效，防止迟到明文进入新地址表单。

## 4. 文件页请求生命周期

`FilePage` 对列表、选中文件详情、完整文本、索引刷新和回收站列表分别维护请求代次，并在响应提交前校验当前路径/选中文件/回收站开关。目录切换、文件切换、关闭回收站和卸载都会使对应旧请求失效；迟到响应不能覆盖新状态，迟到失败也不能弹出与当前操作无关的 toast。只有仍属当前代次的详情和回收站失败显示错误反馈。文件变更事件负责统一刷新，mutation 后不重复手动加载旧目录。`useUploadQueue.ts` 单独持有文件引用、AbortController、进度、取消和失败重试，组件卸载统一终止在途上传；`UploadQueueBar.tsx` 只负责固定高度的状态展示。

`FilePanel` 对目录列表和文件详情使用独立请求代次；`SettingsPage` 的配置刷新和模型目录探测也必须在响应提交前确认仍属于当前请求。快速点击、切换筛选、修改模型接口配置、全局刷新或组件卸载时，迟到响应只能被丢弃，不能覆盖当前页面状态。上传成功后按智能摄入策略异步触发正文/视觉/向量处理，重试上传复用同一回调；索引失败不能回滚或伪报上传失败。

`SkillsManager` 独立维护列表与详情请求代次，搜索和分页读取摘要，选中后才加载完整 instructions。内置 Skill 只读且始终启用；自定义 Skill 支持新建、编辑、启停和删除，mutation 后重拉当前查询。新建名称保存后不可改名，避免把 rename 隐式实现成跨记录覆盖。


索引、向量和视觉操作直接通过 `lib/api/index.ts` 调用业务 API；文本文件的向量化动作先抽取正文再写入向量，图片视觉索引由后端一次完成。请求完成或失败都在当前文件详情中反馈，不再显示任务队列或“后台排队”状态。


文件页支持：

- name 搜索和 semantic 搜索；
- 多选批量移动/复制/删除、排序和上传队列；队列项通过 XHR upload progress 展示真实百分比，支持取消当前请求、失败和重试状态；
- 全部文件、收藏、最近访问三个集合；收藏可在列表行内切换，最近访问由服务端访问记录排序；
- 文件类型、修改时间范围和 semantic 最低相关度筛选；有筛选时列表请求显式传递参数，无筛选时保持兼容的三参数调用；
- semantic 最低相关度过滤、分页和结果截断提示；
- 默认过滤低于 30% 的语义结果，并在无可靠命中时显示明确空态；结果可标记视觉描述命中；
- 文本、Markdown、图片、PDF 预览以及受限完整文本读取；
- revision、全文和向量状态查看；详情面板区分 `text` 文本向量与 `vision` 视觉描述向量，并读取 `/files/versions` 展示真实内容快照，通过 `/files/versions/restore` 恢复为新 revision；
- embedding/vision 异步索引、上传、移动、复制、回收站和恢复；单文件重命名/移动/复制/删除在成功 toast 中提供一次性撤销，批量 mutation 提交前显示受影响路径预览。

移动端预览与回收站是全屏覆盖层；小于 640px 时文件工具栏保持 3×2 触控网格，极窄屏隐藏品牌文字但保留无障碍名称。当前主视图是 tab state，不提供可分享的 URL 路由。上传队列状态只在当前页面生命周期内维护；收藏/最近访问加载失败只影响当前集合，不清空其他列表状态。

## 5. 状态与自动化中心

`SystemStatusCenter` 并行读取 readiness（含数据库、存储和备份摘要）、Provider 配置、embedding 状态、设备同步和磁盘信息。每个请求通过 `Promise.allSettled` 独立收敛，失败项显示局部告警，已成功的其他项继续渲染；状态行提供 Provider、同步和设备跳转入口，但不携带密钥或文件内容。

质量指标暂不落库：后端 `BusinessMetrics` 通过现有日志链路输出索引/视觉/向量成功数与耗时、语义搜索无结果、文件打开、Agent operation 成功和取消事件。前端活动中心仍是用户反馈真相源，不能把这些日志事件改造成任务队列或可恢复后台任务。


## 6. 认证与原生桥

- Web/PWA 使用 HttpOnly Cookie；401 通过 `agent-drive:unauthorized` 返回认证入口。
- 启动阶段只有 401/403 表示身份失效；`/auth/status`、`/config/status` 的 5xx、网络、JSON 解析或字段契约错误展示可重试服务故障，不清理 Cookie/设备令牌，也不降级成“AI 未配置”。
- Android 首启扫码兑换设备令牌，原生侧写入独立 EncryptedSharedPreferences；没有令牌时进入重扫码页，密码登录是逃生口。
- AI 配置只在 web 设置页提供，App 通过原生插件管理服务器地址、设备令牌和相册同步。
- `MainActivity` 必须在 `super.onCreate()` 前注册 Capacitor 插件；插件生命周期、权限回调和 observer 约束见 [`android.md`](android.md) 与 [`AGENTS.md`](../AGENTS.md)。

## 7. 开发与验证

```bash
cd frontend
npm run dev -- -p 3333
npm run lint
npm test
npm run build
npm run verify:build
```

前端行为变化需要同步测试和 Service Worker cache 版本。独立 hook 的并发/代次语义优先写直接 Vitest，再由页面测试覆盖组合行为。生产静态资源通过顶层 `scripts/deploy.ps1` 原子发布，不使用 PowerShell 通配符上传 `out`，以免遗漏 `.well-known/assetlinks.json`。
