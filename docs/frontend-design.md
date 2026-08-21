# 前端设计规范（现行）

> 现行 UI/UX 规范，所有前端改动必须遵守。主题基座：shadcn/ui（radix 底座）+ Tailwind 4。
> 设计 token 唯一来源：`frontend/src/app/globals.css` `:root`。本文件随实现更新（铁律 §0）。

## 1. 视觉基座

- 灰白 light 主题：品牌 token（`--bg/--panel/--card/--border/--text/--muted-text/--accent-brand/--accent-soft/danger/success/warn + soft`）唯一主题源；
  shadcn 语义变量（`--primary/--accent/--muted/--ring/...`）全部映射到品牌 token，**禁止在组件里写死颜色/圆角/阴影值**。
- 主色采用黑白灰 `--accent-brand #202124`（primary）；红/橙/绿只表达错误、警告和成功；界面分区使用细边框与开放留白，卡片与控件不超过 8px 圆角；1px `--border` 描边。
- 仅 light 主题（`.dark` 保留未启用，不要依赖 dark 样式）。

## 2. 控件清单（唯一实现，禁止自造复刻）

| 控件 | 组件 | 用法 |
|------|------|------|
| 按钮 | `ui/button` | 主操作 default；次要 outline；轻量/图标 ghost；危险操作（删除/清空/登出）destructive；文字链接 link。大小 default/sm |
| 输入框 | `ui/input` | 全站唯一输入实现；需要内嵌图标/按钮用 `ui/input-group` |
| 密钥输入 | `ui/secret-input` | 模型 API Key 默认隐藏；有草稿时切换可见性，有已存掩码且配置边界匹配时，小眼睛按需读取并回显完整值；读取中显示加载态，清空或边界变化后恢复隐藏 |
| 下拉 | `ui/select` | 多选一场景；**单值场景禁用**（见反模式 1）；选中/键盘高亮使用 `accent-soft + text-text`，不要直接用深色品牌 `accent` 做背景。可搜索选择使用 `ui/combobox`，其高亮项遵循同一对比度规则 |
| 组合框 | `ui/combobox` | 可过滤选择 + 自由文本并存场景（模型名）。Base UI 底座：Root 受控 `value/onValueChange`，`items=[{value,label}]` 或 ComboboxCollection/Item，`ComboboxInput/Content/List/Empty/Trigger/Clear` |
| 卡片 | `ui/card` | `Card/CardHeader/CardTitle/CardDescription/CardContent/CardFooter`，bg-panel |
| 状态标签 | `ui/badge` | 任务状态/设备/索引等；variant default/secondary/destructive/outline |
| 开关 | `ui/switch` | 布尔设置（PhotoSync 等） |
| 骨架屏 | `ui/skeleton` | 加载态 |
| 折叠内容 | 原生 `<details>` | 思考过程、长内容等默认收叠；summary 提供明确标题与展开入口 |
| 就地反馈 | `ui/alert` | 卡片内成功/失败细条：成功 `className="bg-success-soft text-success border-success/30"`、失败 `bg-danger-soft text-danger border-danger/30` |
| 分隔线 | `ui/separator` | 区块分隔 |

## 3. 排版与节奏

- 页面优先使用 `border-b/border-y` 分区、列表和表格；确需成组时使用 `bg-panel border rounded-md p-4`（Card 组件），避免卡片套卡片；设置/认证页容器使用 `max-w-4xl` 或内容需要的稳定宽度。
- 表单：label `text-xs text-muted` + 控件 `text-sm`，字段纵向 `gap-2`，区块内 `mb-3`。
- 标题：卡片 `CardTitle text-sm font-bold`；页面 h2 `text-lg font-bold`。
- 聊天输入区位于视口底部，聊天模型 Combobox 的候选层固定向上展开；列表沿用组件的可用高度上限并内部滚动，不能压缩成底部窄条或遮挡输入框。
- 模型上下文以紧凑的原生 `<details>` 行显示“上下文注入 · 来源”，默认折叠；展开正文使用有界滚动的等宽文本区，不与助手回复或工具卡混成同一种视觉层级。

## 4. 反馈与状态语义

- 轻量信息：就地小字（字段旁/卡片底部）；跨页事件才走全局 ToastStack（事件总线 `EV.toast` 不变）。
- 状态色语义：danger=失败/破坏性操作；success=成功；warn=警示；muted=次要文本。**禁止错位**（红底选中、蓝字错误等）。
- 生产界面不使用 emoji 作为导航、工具栏、状态或空态图标；图标统一使用 lucide，文字作为可读标签，颜色只承担语义。
- 黑色主操作与选中态必须克制使用；普通次要操作使用 outline/ghost，避免一行按钮全部变成深色块。
- 操作后必须有反馈：保存/获取/删除/恢复都要就地提示或 toast。
- 会话列表每条记录同时显示完整会话 ID；ID 使用低对比度等宽小字，长值允许换行，不用截断值替代真实标识。
- 对话思考等级放在输入区，使用 `ui/select`，默认 `auto`；Provider 返回的模型 reasoning 单独显示在助手气泡内的原生 `<details>`，默认收叠，展开后使用现有 markdown 样式；没有 reasoning 返回时不展示伪造内容。
- 聊天输入区默认保持紧凑：推理层级、输入行和快捷操作使用短间距；外层 composer 只在聊天文本框聚焦时显示主题边框与轻量外环，Select 触发器使用自身的轻量键盘焦点反馈，避免菜单关闭后外层输入框残留强焦点态；外环不得改变布局尺寸或造成输入区跳动。
- 聊天输入区外层不绘制横跨页面的分隔线或边框，只保留居中的 composer 容器边界；避免把整个底部区域视觉上框住。
- 对话工作区的会话列表和桌面文件栏支持收缩/展开与拖拽拉伸：收缩后保留 48px 图标 rail；会话栏宽度限制为 220–360px，文件栏为 260–460px；分隔轨道必须支持鼠标拖动、方向键、Home/End 和 Enter/Space 收缩。布局状态使用 `agent-drive-workspace-layout-v1` 持久化，面板隐藏的响应式断点不变。

- 任务列表行提供明确的 Chevron 展开/收起入口；展开区展示任务 ID、队列、来源、尝试次数、时间、执行输入、执行结果、失败原因和子任务。长 JSON 使用等宽文本、滚动容器和统一脱敏，不能把错误只放在列表小字里；详情加载中、失败和空结果都要有明确状态。

## 5. 移动端（沿用 AGENTS.md 坑位 + 新增）

- <640：表单单列、多列选择卡竖排；触控目标 ≥40px（globals 已全局 min-h-44）；320px 无横向滚动。
- FilePage 预览/回收站移动端全屏覆盖层 `fixed inset-0 z-40 lg:static` 保持不变。
- FilePage 的“已选”操作栏始终保留固定高度；桌面端单行，移动端横向滚动不换行，空状态使用透明边框占位，选中或详情加载不得推动面包屑和文件列表。

## 6. 不应该的样子（反模式，禁止）

1. **同一值两个控件**（select+input 并列、chips+input 等价物）——一个值只允许一个控件；选择+手输并存用 Combobox。
2. **只有一个选项的下拉框**——单值改为静态文案。
3. **手写 className 复刻 ui/ 组件**——新控件一律走 `components/ui/`。
4. **提示文案错位**——解释 A 字段的文字必须贴在 A 字段下。
5. **全宽横幅刷屏**——轻量信息用就地小字。
6. **固定多列小屏挤压**、无空态文案的列表、无加载/禁用态的操作区。

## 7. 迁移原则

- 只换表现层（className/组件），**不改任何逻辑**：SSE 解析/80ms 节流/事件总线/状态机/缓存键一律不动。
- 行为等价：控件触发、文案、状态流转与改造前一致；视觉对齐本规范。
- 每页改完 `npm run lint && npm test && npm run build` 全绿；部署脚本负责发布前的统一构建和健康检查。
