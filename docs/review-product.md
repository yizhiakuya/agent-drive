# Agent Drive 前端产品体验审查报告

> 审查对象：frontend/src（App.jsx / ChatPanel / FilePanel / SessionList / Onboarding / styles.css / api 层）
> 审查维度：① "AI 中心"理念落地程度 ② 流式体验·操作可视化·透明度 ③ 会话体验 ④ 缺失的关键体验 ⑤ 与优秀产品对比
> 结论先行：**架构与理念的"骨架"是对的，但"AI 中心"的闭环断在了最后一公里——Agent 干完活，文件面板不刷新、改了什么看不见、结果点不开；生成过程不可停、工具轨迹与对话割裂、成本不透明。** 补上"对话↔文件联动 + 生成可控 + 移动端"三件事，产品体验可以上一个台阶。

---

## 一、优点

1. **"AI 中心"骨架正确**：主界面即对话（ChatPanel 占据中央 flex:1），Onboarding 首页明示"这是唯一需要手动的一步，之后所有事情都可以交给 Agent"——产品理念与信息架构一致，方向对。
2. **后端工具集完整支撑 agent-first**：11 个文件工具（list/search/read/write/append/copy/mkdir/rename/move/delete/storage_info）+ 系统配置（set_llm_provider/test_llm_connection）+ 规则/记忆/审计工具，文件操作理论上全部可通过对话完成；LLM 配置存在网盘里，Agent 能自己改配置。
3. **流式基础好**：SSE 逐块渲染（text/tool_trace/done/error 四类事件）、等待时"Agent 思考中…"占位、Markdown（GFM）完整渲染（表格/代码块/引用样式齐全）。
4. **透明度组件已有雏形**：PlanCard（⏳🔄✅⏭️❌ 步骤状态）、TraceCard（list_files 表格化、search_files 卡片化、错误红色提示）、上下文进度条（50%/80% 阈值变色）、高风险确认框（red 级工具"不可撤销"确认）——这四件套是同类开源项目少见的用心。
5. **会话闭环基本可用**：自动标题生成（≥2 条消息触发）+ 摘要（needs_summary 阈值）+ 切换加载历史 + 删除；sidRef 正确区分"会话创建"与"用户切换"。
6. **Onboarding 体验好**：协议卡片选择、Base URL/模型随协议自动填充、测试连接 + 模型诊断（延迟/工具支持/上下文窗口）、失败信息具体——这是新手用户的第一印象，做得好。
7. **工程卫生尚可**：单一 CSS 变量体系、状态色语义化、`npm run build` 干净通过（gzip 约 100KB）。

---

## 二、问题清单

### A. "AI 中心"理念落地

**A1. 🔴 Agent 操作结果与文件面板完全脱节**
FilePanel 只在挂载时加载一次。用户在对话里说"帮我建一个叫 项目 的文件夹"，Agent 执行成功，但右侧文件面板纹丝不动——必须刷新整个页面才能看到成果。"一切通过对话"的闭环断在最关键的一步：**对话做完的事，界面上看不见**。
建议：后端把文件变更（工具审计事件）通过 SSE 广播，前端订阅后刷新 FilePanel；文件面板对最近被 Agent 改动的文件做高亮/动效标记；TraceCard 中的文件路径可点击，跳转并定位到文件面板。

**A2. 🔴 没有文件预览/下载**
后端已有 `GET /api/v1/files/download`，前端零使用。双击文件无任何动作；作为"网盘"，用户看不了图、打不开文档、下不了文件——这是品类的基本能力，不是可选项。
建议：预览抽屉（图片/文本/markdown/PDF 内嵌渲染）+ 下载按钮；预览页加"让 Agent 总结/整理这个文件"按钮，把预览变成 agent-first 的入口而非旁路。

**A3. 🟡 状态失真：`Agent 已就绪` 是静态文案**
无论后端挂没挂、LLM 掉没掉线，header 永远是"Agent 已就绪"；启动时后端不可达，App 把 error 存进 status 但没传给 Onboarding，用户看到的是空表单而非错误原因；listSessions 失败被静默吞掉。
建议：健康检查轮询驱动状态 pill（就绪/降级/离线三态）；错误信息传递到 Onboarding 展示；会话加载失败显示重试按钮。

**A4. 🟡 无全局"Agent 活动"可见性（审计有后端、无前端）**
后端有 view_audit_log 工具和完整审计日志，但前端没有任何审计/活动视图。用户不知道 Agent 历史上做过什么、有没有后台任务。
建议：header 显示当前活动（如"🔧 list_files 执行中…"）+ 活动抽屉，可回放任意会话的工具轨迹（参考 OpenClaw Dashboard 的总览面板）。

**A5. 🟡 文件面板与对话无"就地唤起"**
选中一个文件后，没法对它说"整理这个文件"——指令全靠手动打字，文件名也要手动抄。违背"认知减负"原则。
建议：文件 hover/右键菜单带 Agent 快捷动作（整理/总结/移动/归档…），自动填入带 @文件引用 的对话消息。

### B. 流式体验·操作可视化·透明度

**B1. 🟡 工具轨迹与对话流割裂**
Trace 区在消息区之外、输入框上方一个 120px 的小滚动条里，没有时间先后关联；切换会话后历史轨迹全部清零，无法回看"Agent 当时到底做了什么"。用户读着回复，看不到每一步工具调用的发生时机。
建议：工具卡片作为内联节点按时间顺序插进消息流（可折叠），或右侧活动时间线面板（Devin "Follow Devin"/Grok 纵向步骤条模式）可点击回看；轨迹随会话持久化。

**B2. 🔴 生成过程不可控（无停止/无重试）**
流式进行中 input 被禁用，无停止/暂停按钮，只能等它跑完或刷新页面；出错只显示"⚠️ 出错了：HTTP 500"，无重试、无重新生成。这直接违反 Agent 交互的核心设计模式"暂停-反馈-继续"。
建议：停止按钮（AbortController 取消 fetch）；错误气泡加"重试"按钮；assistant 消息 hover 加"重新生成"。

**B3. 🟡 成本与用量不透明**
无 token/费用/延迟统计；ContextBar 只在回复结束后出现一次，过程不实时更新。Claude Code 生态的共识是"实时成本反馈"是信任的关键。
建议：每轮结束显示 token 消耗与延迟；流式中实时刷新上下文占用；后续加长期用量统计页。

**B4. 🟡 高风险确认的恢复机制绕**
确认后不是"原位继续执行"，而是构造一条新消息"请继续执行刚才确认的操作…"重新发起一轮对话——多一次 LLM 往返、聊天里多一条冗余用户消息、Agent 还要重新理解上下文。与 Claude Code "批准后原位继续"差距明显。
建议：后端支持 pending_confirmation 直接携带 confirmations 恢复同一次执行；前端确认框只回传批准信息，不生成伪消息。

**B5. 🟢 流式细节**
"思考中"只在空内容时显示，开始吐字后无任何生成中指示（无光标/尾随动画）；plan 完成后不折叠不总结；未知工具的输出直接裸 JSON；trace 区 120px 滚动条阅读体验差。
建议：尾部光标动画；plan 完成后折叠为一行摘要；JSON 美化折叠展示；trace 区改为内联（见 B1）。

### C. 会话体验

**C1. 🔴 会话切换与在途流冲突（串消息）**
无 AbortController：切换会话时旧请求继续运行，其文本继续追加到**新会话**的消息数组（串消息）；完成后 `onSessionCreated(r.session_id)` 还会把 App 切回旧会话，覆盖用户的选择；busy 状态不随切换重置。
建议：切换时 abort 在途流并丢弃其结果；done 回调校验 sid 一致性；或流式期间禁止切换并提示。

**C2. 🟡 会话管理单薄**
删除无确认（误删即丢，无恢复）；无重命名/搜索/置顶/分组；新建会话后是空白页——欢迎消息只存在于组件首挂载的初始 state，空态没有任何引导。
建议：删除二次确认；双击重命名；空态展示欢迎语 + 建议指令 chips（"看看网盘里有什么文件"等一键填入）。

**C3. 🟢 会话细节**
SessionList.newSession 的 busy 是 50ms 假防抖；标题 20 字截断后无完整 tooltip；会话列表仅在每轮结束后刷新（可接受，但可升级为 SSE 推送）。

### D. 缺失的关键体验

**D1. 🔴 移动端/响应式为零**
styles.css 没有任何 @media query；240px + 320px 固定侧栏 + 100vh 布局，手机上完全不可用。私人网盘恰恰是移动端高频场景（照片备份、出门找文件）。
建议：移动断点（侧栏转抽屉、文件面板转底部 tab）、触控手势（双击→单击）、上传照片流。

**D2. 🟡 上传体验弱**
单文件（`files[0]`）、无进度条、无多选、无拖拽——`dragging` state 是死代码（setDragging 从未被调用）；Agent 也没有 upload 工具，大文件只能手动。
建议：多文件 + 进度 + 拖放；上传完成事件并入文件变更流，让文件面板即时可见。

**D3. 🟡 文件面板导航弱**
双击进目录的交互不可发现（无 chevron、单击无反馈）；路径面包屑不可点击；无排序/搜索/选中态；文件项无任何操作入口。
建议：单击进入 + 面包屑可点 + 排序；保留 agent-first 但提供最小手动兜底（下载/预览）。

**D4. 🟢 无障碍与质感**
会话项/文件项是不可键盘聚焦的 div、无 aria 标签、无 focus-visible 样式；无深色模式；中文硬编码无 i18n；emoji 图标跨平台渲染不一致。
建议：转 button + aria-label + focus 样式；CSS 变量已就绪，补 dark 主题成本低。

**D5. 🟢 代码卫生**
client.js 是死代码且路径错误（getStatus → `/api/v1/api/status`、uploadFile → `/api/files/upload`，与 config.js/files.js 重复且不一致）；ChatPanel.loadSession 有重复的 `setContextUsage(null)`；Onboarding 的 Anthropic 默认模型 "claude-sonnet-4-5" 不是有效的 API 模型 id（应为 claude-sonnet-4-5-20250929 之类）。
建议：删除 client.js 死导出；清理重复语句；核对默认模型 id。

---

## 三、优秀案例对比（搜索调研结果）

### 3.1 理论框架：Agent 交互七大设计模式（UX 设计师贾思玉《AI Agent 产品交互设计》）

| 设计模式 | 优秀产品做法 | Agent Drive 现状 | 缺什么 |
|---|---|---|---|
| 思考外显 | Cursor/Grok/Devin 全程展示步骤与工具 | PlanCard + TraceCard 已有雏形 ✅ | 轨迹内联、历史回放 |
| 注意力引导 | Cursor 把对话步骤与代码 diff 高亮对应（绿=新增/红=删除）；渐进展示非核心信息 | ❌ 无任何"操作→文件"高亮联动 | 文件改动高亮、trace 与文件定位 |
| 暂停-反馈-继续 | Genspark 暂停按钮；Devin 关键决策点暂停询问 | ❌ 无停止/暂停按钮 | 停止按钮、重试、恢复执行 |
| 就地澄清 | Cursor 在代码上直接编辑；v0 选中局部修改 | ❌ 文件面板与对话无就地唤起 | 文件 hover/右键 → Agent 指令 |
| 自动建议 | Gemini Deep Research 先给计划再"修改方案"；关键决策点给选项 | 🟡 有计划卡但无确认/修改入口 | 计划可编辑、建议指令 chips |
| 上下文/知识匹配 | flowith 知识花园、Devin Knowledge 标注引用的记忆 | 🟡 有记忆系统但前端不展示引用来源 | 回复中标注引用的文件/记忆 |
| 环境/工作流适配 | Gemini 融入 Workspace、结果直接入 Drive | ❌ 无移动端、无导出、无跨设备 | 移动端、结果一键下载/导出 |

### 3.2 具体产品对标

- **Claudia（Claude Code 图形界面，火山引擎社区测评）**：聊天一开始透明显示**所用模型、可用工具、工具调用**；每次对话结束显示 **token 与费用统计**；CheckPoint 可视化时间线可回滚。→ 我们缺：模型/工具透明展示、成本统计、时间线回放。
- **Devin / Grok**："Follow Devin"窗口用进度条定位到任意步骤查看详情；Grok 用纵向步骤条兼作锚点导航。→ 我们缺：可点击回看的步骤时间线（trace 目前是底部 120px 小滚动区，切会话即丢）。
- **OpenClaw Dashboard（openclaw-dashboard, 457 star）**：多 agent/会话/通道/定时任务的总览控制面板，可"watch"正在运行的 agent 会话。→ 我们缺：全局 Agent 活动/审计总览（后端审计日志在前端无入口）。
- **ChatGPT/Gemini Deep Research**：执行前先出计划并允许"修改方案"，模糊需求先澄清。→ 我们有 PlanCard 展示，但缺"修改计划"入口与需求澄清交互。
- **OpenAI 开发者社区共识（Best Agent UI 2026 讨论）**："UI 不应优先优化手动编辑速度，而应优化 **observability（可观测性）、traceability（可追溯性）、task-level control（任务级控制）**；state、rationale、progress、branching、rollbacks、approvals 应成为一等公民。" → 我们的 rationale（为什么这么做）、branching/rollback 完全缺失，approvals 只覆盖高风险写。
- **微软 Copilot UX 指南**：渐进披露、透明度建立信任、人在环路。→ 我们的透明度组件有了，但"人在环路"只在 red 级工具上存在，yellow 级写操作（write_file/copy/move/rename）执行时用户无感知、无撤销入口。

---

## 四、优先级建议（最值得做的 3 件事）

**P0 ① 打通「对话 ↔ 文件」闭环 —— 让 Agent 的成果可见、可点、可继续**
文件变更事件推送 + FilePanel 自动刷新 + 最近改动高亮 + 文件预览/下载（复用后端已有 download 端点）+ 文件 hover 唤起 Agent 指令。
这是"AI 中心"从口号变成体验的关键一步：用户说一句话，马上能在文件面板看到结果并点开。预计工作量中等（SSE 事件 + FilePanel 改造 + 预览抽屉），收益最大。

**P0 ② 生成过程可控 + 透明 —— 停止按钮、内联工具时间线、成本统计、在途流隔离**
AbortController 停止/重试、工具卡片内联进消息流（可折叠、随会话持久化）、每轮 token/延迟展示、修复会话切换串消息的 bug。
对应用户信任的两个支柱（可控制 + 可解释），也直接修复最严重的体验缺陷（C1 串消息）。

**P0 ③ 移动端响应式 + 会话管理补强**
媒体查询断点（侧栏转抽屉、文件面板转底部 tab）、触控适配、上传照片流；会话删除确认、空态引导、建议指令 chips。
私人网盘的核心使用场景在移动端；会话补强是低成本高感知的体验提升。

> 次要但快速可做（P1）：删除 client.js 死代码、修复默认模型 id、状态 pill 接真实健康检查、Onboarding 展示启动错误、plan 完成后折叠、trace JSON 美化。

---

*审查方法：通读 frontend 全部源码（组件/API/CSS）+ 后端工具与路由对照 + `npm run build` 验证 + 通过 websearch 检索 Claude Code/Claudia、ChatGPT/Codex/Deep Research、Cursor/Devin/Grok/Manus、OpenClaw Dashboard 及 Agent UX 设计文献进行对比。*
