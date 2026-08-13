# 🔍 Agent 核心循环与提示工程审查报告

> 审查对象：`backend/app/agent/`（loop.py · prompt.py · context.py · router.py · confirm.py + tools/ + memory/）
> 审查依据：《Building Effective Agents》(Anthropic 2024)、《How we built our multi-agent research system》(Anthropic 2025)、《Effective context engineering for AI agents》(Anthropic)、《Dive into Claude Code: The Design Space of Today's and Future AI Agent Systems》(arXiv 2604.14228)、OpenClaw 官方文档（agent-loop / memory / compaction）、Loop Engineering 系列（Addy Osmani / Requesty）
> 审查方法：逐行读码 + 可复现验证（压缩结构、路由分类均实际运行验证）+ 业界案例对比

---

## 一、优点（做得好的地方）

1. **编排引擎结构干净**：`_execute()` 单一生成器 + `run()`/`run_stream()` 复用，消除重复代码；agent 模块按 loop/prompt/context/router/confirm 单一职责拆分，依赖方向正确，符合"领域层不依赖 HTTP"的分层纪律。
2. **提示工程整体达到"API 文档级"**：工具手册统一含 用途/参数/输出/前置条件/错误情况；行为准则 10 条覆盖工具优先、一致性、鲁棒性、删除直调工具、优雅失败、规划、记忆维护——与 Anthropic"工具文档质量决定 Agent 成败"的建议高度一致。
3. **Actor-Critic 已在工具层落地**：写操作带 validator（写入后读回校验、rename/move/delete 后验、create_folder 幂等校验），失败返回结构化 `{ok:false,error}` 让模型可读可重试——这是业界（Claude Code 之外）少见的做法，方向正确。
4. **安全护栏闭环**：green/yellow/red 三级 + red 确认→前端 confirm 框→confirmations 重放 + 全量审计日志 + storage 层路径越界防护，符合"错误有界"原则。
5. **上下文管理有实质投入**：tiktoken 精确计数 + 预算反向截断 + LLM 滚动摘要（失败回退滑动窗口）+ 轮内压缩 + 上下文进度条，方向与 Claude Code 的 compaction 理念一致（虽然后文指出实现 bug）。
6. **记忆系统是 OpenClaw 模式的忠实落地**：USER.md/MEMORY.md/每日笔记三层 + 按需 memory_search + dreaming 每日巩固 + 会话摘要跨会话注入——这正是社区验证过的长期记忆最佳实践。
7. **意图→工具组检索**（而非全量注入）符合"拆分上下文/防注意力衰减"原则；闲聊走轻量提示省 token；技能包"索引注入 + read_skill 按需加载"符合 Anthropic Agent Skills 模式。
8. **规划器 + 前端 PlanCard** 显式展示执行路径，符合 Anthropic"prioritize transparency by explicitly showing planning steps"。
9. **幂等与重试意识**：create_folder 已存在即成功；provider 层统一 `with_retry` 指数退避（LLM 调用），瞬态/永久错误区分。
10. **测试理念对齐 Princeton 四维度框架**：test_reliability.py 按 Consistency/Robustness/Predictability/Safety 组织测试，端到端 FakeProvider 验证循环，比多数同阶段项目扎实。

---

## 二、问题清单

### 🔴 严重（会导致任务静默失败或 API 报错）

**R1. 意图路由把"问候+任务"整句吞掉，能力静默归零**
- 位置：`router.py L38`（`low.startswith(g)`）+ `loop.py L218`（chat 路径无任何工具）
- 问题：`classify()` 只要消息以问候词**开头**就判 chat。实测："你好，帮我把文件整理一下"、"hi，帮我找一下预算文件"、"谢谢你，帮我把模型换成 DeepSeek"、"你是谁？帮我看看网盘有什么" 全部 → `('chat', None)`。chat 路径无工具、且用户看到的是礼貌闲聊，**任务静默失败，没有任何提示**。误分类后果不对称：chat 误判 = 能力归零；task 误判只是多花 token。
- 建议：① 问候词只应在"消息纯粹是问候"时触发（无任务关键词才判 chat）；② 更根本：改用 LLM 一次调用做分类（返回 mode+tool_groups+置信度，可复用主模型或小模型），关键词表仅作零成本快路径，低置信度回退全量工具；③ 分类结果打日志/前端 debug 展示，便于定位"Agent 没做事"的投诉。

**R2. 轮内压缩 `compress_tool_roundtrips` 破坏消息结构（必现 API 错误）**
- 位置：`context.py L126`（`cut = tool_idx[-keep_roundtrips*2]`）
- 问题：cut 点按 tool 消息下标切，**不对齐 assistant(tool_calls) 边界**。实测（10 轮×1 工具、keep=4）：压缩后第一句是 `tool(c3)` 而它的 `assistant(tool_calls)` 已被切进摘要 → **孤儿 tool 消息**。OpenAI/DeepSeek 协议直接 400（tool message 无匹配 tool_calls），Anthropic 会因 tool_use_id 不存在报错。另：压缩摘要以 `role:system` 插在对话中间——Anthropic provider 的 `_convert_messages` 会**静默丢弃**非开头 system（摘要丢失），OpenAI 系虽收下但语义错位。多 tool_calls 单轮（并行调用）时破坏更严重。
- 建议：① 压缩单元改为"完整 roundtrip"（assistant(tool_calls)+其全部 tool 结果），cut 只落在 assistant 消息边界；② 摘要信息放 system 开头追加或 user 消息，不要中途插 system；③ 加单测断言"压缩后无孤儿 tool 消息、role 合法"。

**R3. 步数耗尽静默失败：用户看到空回复**
- 位置：`loop.py L373`（`truncated: True`）+ 前端 `ChatPanel.jsx`（不消费 truncated 字段）
- 问题：max_steps 用尽只 yield done，**不发任何 text 事件**，前端对 `truncated` 未做任何处理 → 用户消息下面挂着一个空白的 assistant 气泡，无解释无引导。复杂任务（批量整理）大概率触发。
- 建议：truncated 时补发一条说明文本（"已达到本轮最大步数（N），任务可能未完成；你可以说'继续'或拆分任务"）；前端把 truncated 渲染为警告态。

### 🟡 中等（成本/可靠性/体验显著受损）

**M1. 最终回复双 LLM 调用：双倍成本与延迟，答案可能前后不一致**
- 位置：`loop.py L280-294`：先 `chat()` 拿 tool_calls，无工具调用时再对**同一份 messages** 调 `stream_chat()` 重新生成
- 问题：每轮最终回复多付一次完整 prompt token（含系统提示+历史+工具手册），延迟翻倍；且第一次调用的 `result.content` 被直接丢弃——两次调用答案可能不同。
- 建议：无工具调用时直接复用 `result.content`（放弃流式），或升级为"支持工具调用的流式单次调用"（Claude Code 做法）。至少保留一个开关权衡流式体验 vs 成本。

**M2. 系统提示 LLM 状态恒为"未配置"——提示与事实矛盾**
- 位置：`loop.py L179`（`build_system_prompt(..., {}, ...)` 硬编码空 status）+ `prompt.py L31`
- 问题：无论实际配了什么模型，系统提示永远写着"LLM: 未配置"。违背本项目自己的"提示词无矛盾"原则，且会误导 Agent 对系统状态的判断（如用户问"我的模型是什么"）。`status` 参数是死代码。
- 建议：从 LLMManager.load() 注入真实配置（type/model），或直接删除该行。

**M3. 确认恢复靠"重新推导"，非确定性重放**
- 位置：`loop.py L342-360` + 前端 `ChatPanel.jsx confirmYes()`
- 问题：确认后以"请继续执行刚才确认的操作：delete_file {...}"作为新用户消息 + 客户端 confirmations 列表重新走完整循环。① 模型若生成**不同参数**会再次卡确认，若**不再调用**则确认落空，系统不校验"被确认的操作确实执行了"；② plan_state 每请求新建（`container.build_agent()` 每次新实例），恢复后计划进度全部丢失；③ 确认状态无服务端持久化、无时效。
- 建议：服务端持久化 pending_confirmation（含 sid、工具、参数、时间戳），确认后**确定性重放**该工具调用并把结果注入消息流继续循环；重放完成后校验并审计。

**M4. 工具组映射不可靠，跨组动作工具缺失**
- 位置：`router.py L28/L36`（`GROUPS_FILES = ["files","plan","skills","memory"]`，SYSTEM 关键词在后）
- 问题：实测"把规则删掉" → files 组（含"删除"关键词），而 `remove_rule` 在 system 组 → 工具不可见，Agent 只能失败或瞎试。"删掉规则/清理记忆/删除会话"这类跨组动作无兜底。关键词穷举对改写（"把这个文件给我弄没了"→ 无关键词 → 长度<10 判 chat）覆盖率低。
- 建议：① 跨组动作词（删除/整理/恢复…）直接给全量工具；② 分类器输出置信度，低置信回退全量；③ 路由结果进审计/调试面板。

**M5. 审计日志不含工具结果与错误 → meta-agent 错误分析无米下锅**
- 位置：`loop.py L186`（只审计 `[tool:name] args`）+ `tools/analytics.py`（`analyze_failures` 扫审计日志找 error/fail）
- 问题：工具执行失败（`{ok:false, error}`）从不进审计日志；`analyze_failures` 只能扫到 pending-confirm 事件，"原则5：LLM 驱动错误分析"闭环形同虚设。
- 建议：`_execute_tool` 执行后审计 `[tool-result:name] ok/err`（含错误分类 category），让 analyze_failures 有真实数据。

**M6. 滚动摘要存在永久丢失窗口**
- 位置：前端 `send()` 只传最近 30 条历史；`loop.py L262` 压缩阈值 `context_budget(24K) * 0.6 ≈ 14.4K tokens`
- 问题：30 条普通消息很难达到 14.4K token 阈值 → 压缩长期不触发 → 第 31 条以前的消息**既不在窗口内、也没进过摘要**，永久丢失。压缩是对"前端截断后的窗口"做摘要，不是对会话全量。
- 建议：压缩改由服务端基于 SessionStore 全量历史做（前端只传会话 id）；触发条件加"消息条数"维度（如 ≥20 条且超 token 阈值）。

**M7. 无停滞检测 / 无成本上限 / 无运行超时**
- 位置：`loop.py L273` 的 for 循环只有 max_steps 一个退出条件
- 问题：无"连续 N 步重复调用同一工具"检测（业界 DebounceHook 模式）、无单次运行 token/花费上限、无超时 abort（OpenClaw 有 run timeout）。模型陷入重试循环时只能烧满 10 步。
- 建议：① 相同 (tool, args) 重复 ≥2 次 → 强制终止并注入提示"该操作已重复，请改变策略或询问用户"；② 增加每次运行 token 预算上限，超限暂停要求确认；③ 加运行级超时。

**M8. 工具输出截断破坏 JSON 结构**
- 位置：`loop.py L194`（`output[:max_tool_output]` 直接切字符）
- 问题：2000 字符截断点常落在 JSON 中间（list_files 结果数组、系统状态对象），模型收到残缺 JSON 无法解析。截断是字符级不是结构级。
- 建议：结构化截断——dict/list 截断后**重新序列化**合法 JSON 并加 `...(截断, N 项省略)` 字段；文本按行截断。

**M9. 缺显式"反思"环节：观察后无评估、完成前无自检**
- 位置：`loop.py` 循环体（工具结果直接 append，下一轮裸调 LLM）
- 问题：Anthropic 研究系统的"interleaved thinking"（每次工具结果后评估质量/差距/下一步）与 Loop Engineering 的"verify before stop"都缺失。现有 validator 只验证单个工具操作副作用，不验证**任务目标**是否达成（"文件整理完了吗"无人检查）。
- 建议：工具结果消息后追加轻量 critic 提示（可配置开关，小任务关）；或最终回复前加一步"对照用户目标逐条自检，未完成则继续"。

**M10. API 层错误未分类映射（Predictability 受损）**
- 位置：`api/v1/chat.py L17`（只捕 ConfigError）
- 问题：LLMError/ToolError → 裸 500 + 原始异常文本；`core/errors.py` 的错误分类体系在 Agent 路径没有用起来。用户端看到的是 `❌ 出错了：...` 英文异常串。
- 建议：统一异常 → 结构化错误响应（错误类别 + 中文可操作建议），与 errors.py 对齐。

**M11. 上下文进度条用 256K 固定窗口，与实际模型不符**
- 位置：`loop.py L36`（context_window=262144）+ `prompt`/前端 ContextBar
- 问题：DeepSeek 64K、Ollama 本地模型 32K 时，进度条仍按 256K 显示 → 误导性安全感；轮内压缩阈值绑定 context_budget(24K) 而非真实窗口，窗口更小时兜不住。
- 建议：窗口大小从 provider test_connection 的诊断结果动态获取；压缩阈值按 min(budget, 实际窗口) 计算。

### 🟢 轻微（顺手修）

**G1.** 非流式 fallback 分支 completion tokens 双计（`loop.py L295-298`：`result.usage` 已含 content，再 `_add_usage(None, full_reply)`）。
**G2.** `_dream()`（每日首条消息）与 `_generate_title()`（每条首答）inline await，阻塞用户首包延迟——应 `asyncio.create_task` 后台执行（`loop.py L258, L307`）。
**G3.** chat 路径提示词不含 AGENT.md 自定义人设、不含记忆摘要 → 同一 Agent 两个人格（`prompt.py build_chat_prompt`）。
**G4.** `read_skill` 返回全文无长度限制，大技能包可撑爆上下文（`loop.py L79-93`）。
**G5.** `summarize_session` 用 `summary[:20]` 覆盖 `_generate_title` 生成的好标题。
**G6.** confirmations 是客户端自证清单（无服务端状态、无时效），任意客户端可伪造确认——本地单用户可接受，但确认事件应强制写审计。
**G7.** 工具执行重试是单次无退避（`loop.py L190-192`），建议复用 `with_retry`。
**G8.** 确认暂停时前端残留空白 assistant 气泡（"Agent 思考中"解除后内容为空）。

---

## 三、优秀案例对比（我们缺什么）

| 案例 | 他们的做法 | Agent Drive 现状 | 差距 |
|------|-----------|-----------------|------|
| **Claude Code**（arXiv 2604.14228 源码级分析） | 核心就是简单 while 循环：调模型→跑工具→重复，**工程全在循环外围**；五层压缩管线（budget reduction→snip→microcompact→context collapse→auto-compact）+ 预模型上下文整形器；显式 Stop Conditions 与 Recovery 机制；权限 7 模式 + ML 自动批准分类器；subagent 委托 + sidechain transcript | 循环骨架相同（方向对）；压缩只有两层且轮内压缩有结构 bug（R2）；停止条件只有 max_steps（R3/M7）；确认=关键词路由+手动确认框；无 subagent 委托 | 🔴 压缩正确性；🟡 停止/恢复机制、auto-approve 分类器、subagent（M2 可选） |
| **Anthropic 多智能体研究系统**（2025 官方文章） | 编排者**把计划持久化到 Memory** 防截断丢失；每次工具结果后 interleaved thinking（评估质量→找差距→定下一步）；effort scaling 规则防过度投入；full production tracing + LLM-as-judge 评测；确定性保障=retry+checkpoint+resume | plan_state 每请求重置、不持久化（M3）；无显式反思（M9）；无努力度分级；审计不含错误内容（M5）；无 eval 数据集 | 🟡 计划持久化、反思提示、可观测性、评测集 |
| **OpenClaw**（官方 docs：agent-loop） | 每会话串行化队列 + **run 超时 abort**；hook 体系（before_prompt_build / before_tool_call / after_tool_call / before_compaction）；compaction 按模型真实上限预留 token；writer-claim 防并发脏写；无可用回复时发兜底错误回复 | 无并发控制、无超时（M7）；无 hook 扩展点；压缩阈值与真实窗口脱钩（M11）；无兜底回复整形（R3 的背面） | 🟡 超时、hook、并发护栏；记忆三层设计已对齐 ✅ |
| **Anthropic《Building Effective Agents》** | Routing 的前提是"分类**能够准确**"（LLM 或传统分类器皆可）；augmented LLM 的工具接口必须易用、文档完备；简单组合优于复杂框架；用 eval 先验证 | 路由是关键词穷举且已实证误吞任务（R1）；工具文档完备 ✅；架构简单 ✅；无 eval 集（只有脚本式测试） | 🔴 路由准确性；🟡 评测文化 |
| **Anthropic Context Engineering** | 系统提示无矛盾、分层放置指令、静态前缀缓存（prompt caching 省 ~90% 前缀成本）；工具描述经"工具测试 Agent"实测改写（任务耗时 -40%） | 存在矛盾项（M2 LLM 状态恒"未配置"）；未做 prompt cache 静态前缀优化；工具文档是手写未实测校准 | 🟡 一致性、缓存优化 |
| **Loop Engineering**（Addy Osmani / Requesty） | 停止条件=final answer / max steps / **budget cap** 三件套；重复调用检测（DebounceHook）；retry-verify-stop-escalate 状态机 | 只有 max_steps；无预算上限、无重复检测、无升级路径（M7） | 🟡 护栏完备性 |

**一句话差距总结**：Agent Drive 的**骨架和理念对齐度很高**（循环结构、API 文档级提示、Critic 校验、OpenClaw 记忆、工具检索都做对了方向），但**护栏层的确定性**落后于业界：分类会静默吞任务、压缩会造出非法消息、耗尽时静默失败、确认重放非确定性——都是"循环外围"工程，正是 Claude Code 那篇论文说的"核心循环简单，工程全在外围"所指的部分。

---

## 四、优先级建议（最值得做的 3 件事）

**P0（本周，正确性 bug，直接产生用户可见故障）**
修 R2 轮内压缩消息结构破坏 + R3 步数耗尽静默失败。两个都是必现/高频路径上的硬伤：前者导致长任务直接 API 报错，后者让长任务"假死"。修复量都很小（对齐 cut 边界 / 补一条 text 事件 + 前端渲染 truncated），收益最大。

**P1（能力完整性，影响每一次对话的第一印象）**
R1 意图路由升级：先做最低成本的修复（问候词仅在纯问候时触发；带任务动词的问候句走 task），再做 LLM 分类 + 置信度回退全量工具，同时把 M4 的跨组动作词（删除/整理等）给全量工具。目标：任何"带任务的句子"都不能被降级成无工具闲聊。

**P2（成本与安全闭环）**
M1 最终回复单次调用（直接省一半最终回复成本）+ M3 确认机制服务端持久化与确定性重放（把"确认后真的执行了、执行了一次、结果回了模型"变成程序保证，而不是祈祷模型照做）。

> 快速赢家（半天内可完成、性价比高）：M2（status 死代码一行修掉）、M5（审计加一行 result）、G2（dreaming/标题后台化）、M11（进度条按真实窗口显示）。

---

*报告生成：审查专家（Agent 循环与提示工程）。所有 🔴/🟡 问题均经代码路径追踪或实际运行验证，复现脚本见审查会话记录。*
