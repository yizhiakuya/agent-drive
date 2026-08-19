# Agent Drive 长期记忆系统审查报告

> 审查日期：2026-08-13 ｜ 审查对象：backend/app/agent/memory/*、tools/memory.py、loop.py 的 dreaming/每日笔记链路、prompt.py/context.py 的注入链路
> 对照案例：OpenClaw（docs.openclaw.ai）、Claude Code auto-memory、Mem0、Letta(MemGPT)、Zep、OpenAI memory tool

## 总体评价

架构方向正确：**AGENT.md（角色）/ USER.md（用户模型）/ MEMORY.md（策划层）/ notes/（工作层每日笔记）** 的四层划分与 OpenClaw 的分层模型一致，「记忆即网盘文件、用户可见可编辑」符合 "no hidden state" 原则，Dreaming 巩固 + 按需检索的组合也是业界验证过的正确路线（LongMemEval 等评测反复证明：**写什么比怎么索引更重要**）。

但当前实现处于「骨架正确、血肉缺失」状态：核心的**读写-注入链路有三处致命 bug（存了等于没存）**，且缺少优秀案例中普遍具备的**更新/遗忘/矛盾处理、去重、语义检索、来源追踪**等能力。审查中对所有 🔴 问题均做了代码级复现验证（见附录）。

---

## 优点

1. **分层清晰，方向正确**。工作层（每日笔记，不注入）/ 策划层（MEMORY.md，全量注入）/ 用户模型（USER.md，指令式）/ 角色（AGENT.md）职责分明，与 OpenClaw 的 tier 模型（episodic → curated core → user model → instructions）一一对应。
2. **记忆在文件空间，透明可编辑**。记忆直接存在网盘 `Agent/` 目录，用户可以随时查看、修改、导出，符合「模型只记得写进磁盘的内容、无隐藏状态」原则——OpenClaw 官方文档把这条列为第一条设计原则。
3. **记忆工具引导词写得好**。`remember` 工具的 doc 明确写了「何时用 / 临时信息不要记 / 已有相似记忆时 supersede」，对 Agent 的行为约束意图正确（只是缺机制支撑，见问题 7）。
4. **Dreaming 有基本节流与错误隔离**：每天最多一次、笔记少于 3 行不巩固、单次最多 5 条、try/except 保证记忆子系统故障不阻塞回复（"failures never block replies"）。
5. **旧系统迁移兼容层完善**：memory.json → USER.md、system/ → Agent/、旧 memory/ 目录清理，迁移逻辑覆盖了各种边界情况，测试也在。
6. **跨会话摘要 + 上下文压缩体系**与记忆分层互补（compress_history / compress_tool_roundtrips），思路对（虽然有一个 🔴 实现 bug，见问题 3）。
7. **审计日志完整**（audit.log 记录每次工具调用），为将来的记忆溯源提供了基础素材。

---

## 问题清单

### 🔴 严重（记忆丢失 / 数据损坏，必须修）

**1. MEMORY.md 注入截断方向反了：最新记忆永远进不了上下文**
`MemoryStore.memory_text()` 用 `read_text()[:max_chars]` 从**文件头**截断，而 `remember()` 是**尾部追加**。一旦 MEMORY.md 超过 2000 字符（prompt.py 注入预算），被截掉的是**最新**的记忆条目——Agent 越记越多，却越来越看不到自己最近的记忆，且完全无感知、无告警。OpenClaw 同样会截断，但配套了 `/context` 诊断显示 raw vs injected 尺寸，并把截断当作"该压缩了"的显式信号。
*实测*：写入 30 条记忆后，`memory_text(2000)` 包含最旧的第 1 条、不含最新的第 30 条。
*建议*：① 截断方向改为**从尾部取**（保留最近内容），或头部保留固定引导语；② 当文件超过预算时，在系统提示中注入一行告警，提示 Agent "MEMORY.md 超预算，请把细节移到笔记、在 MEMORY.md 只留摘要"，对齐 OpenClaw 的做法。

**2. set()/add_rule() 会静默销毁 USER.md 中用户手写的内容**
`_write_user_md()` 只回写固定 4 个 section（语言/整理偏好/命名规则/规则）。用户在网盘里给 USER.md 手写的自定义 section（如「## 沟通风格」）解析进了 `_data` 却永远不被写回——下一次 Agent 调用 `set()` 整文件重写时，自定义内容**永久丢失**。另外 `MemoryStore` 是容器级单例，`_load_user_md()` 只在启动时执行一次：用户运行期编辑 USER.md 不生效，且下一次 `set()` 会用陈旧的 `_data` 覆盖用户的新编辑。
*实测*：手动追加「## 沟通风格」后调用 `set('language','English')`，该 section 从文件中消失。
*建议*：① `_write_user_md` 保留未知 section（合并而不是重建）；② 每次写前从磁盘重新加载 USER.md（或每次请求时 `_load_user_md()` 刷新 `_data`），保证用户编辑与 Agent 写入互不覆盖。

**3. 滚动压缩摘要被静默丢弃——LLM 摘要从未真正进入上下文**
`compress_history()` 生成的 `[早期对话摘要]` system 消息，在 `_build_messages() → build_history()` 中被过滤（只保留 user/assistant 角色）。压缩因此退化为**直接删除早期消息**：花了 LLM 调用生成的摘要，从不注入本轮或后续轮次；会话恢复时 `rolling_summary` 也没有任何注入路径。长会话的早期上下文（含用户偏好、已完成操作）是**永久丢失**的。
*实测*：`build_history([{system 摘要}, user, assistant])` 返回结果中摘要消息消失。
*建议*：① build_history 放行携带摘要的 system 消息；② 恢复会话时把 meta 里的 rolling_summary 注入 system prompt（或作为历史第一条）；③ 对齐 OpenClaw 的「压缩前 memory flush」：压缩前先把未落盘的上下文 flush 进每日笔记，压缩失败也不丢记忆。

### 🟡 中等（机制缺陷，影响记忆质量）

**4. Dreaming 漏日子：错过的日子永远不补**
`_dream()` 只处理「昨天」的笔记，且只在**任务路径**的首次对话触发。闲聊日、当天没打开 App、或首条消息走 chat 模式的日子，前一天的笔记**永久失去被巩固的机会**（没有 pending 队列/补跑）；笔记 < 3 行的日子不标记，多日稀疏笔记也永远不会被合并处理。此外 `await self.llm.chat()` **没有超时**，LLM 挂起会拖死当天第一条消息。
*建议*：用标记文件记录「已巩固到哪一天」，每次触发时**顺序补跑所有未巩固的日期**；对 LLM 调用加 `asyncio.wait_for` 超时。

**5. Dreaming 蒸馏无去重、无现有记忆上下文、输出无结构校验**
蒸馏 prompt 不含现有 MEMORY.md 内容，重复条目持续累积；输出是自由文本直接落盘（"1. xxx"、"以下是值得记住的内容" 都会原样存入）；同日多次 `remember()` 各自追加一个 `## YYYY-MM-DD` 标题，文件结构混乱（实测同日 32 条 remember 产生 32 个重复标题；现场数据 `backend/data/Agent/MEMORY.md` 已出现两个 `## 2026-08-13`）。OpenClaw 的 deep 阶段会带着**当前 MEMORY.md** 让 consolidation subagent 做 merge/supersede/dedupe，且通过 minScore/minRecallCount 阈值门控后才允许重写。
*建议*：① 蒸馏 prompt 传入现有 MEMORY.md（或最近 N 条）；② 要求结构化输出（JSON：fact / type / confidence），解析失败即丢弃；③ 写前做简单去重（子串/相似度比对）；④ 同一天的条目合并进一个日期标题。

**6. 检索只有裸子串匹配，无语义检索、无排序**
`search_memory()` 是 `query.lower() in line.lower()` 单查询词子串匹配：搜"蓝色"命中不了"群青色"，搜"color"命中不了中文条目，没有分词/BM25/embedding，也没有相关性排序、新近度加权、多样性去重（固定返回前 10 行，文件顺序）。OpenClaw 是 **embedding + BM25 双路并行 → 加权合并 → 新近度/重要性 boost → MMR 去重**。
*建议*：第一步（零依赖）：中文分词 + BM25 打分 + 按文件日期加权 + 多关键词扩展；第二步（本地可离线）：接入本地 embedding（如 bge-small-zh / Ollama），做轻量向量检索。

**7. 无更新/遗忘/矛盾处理——append-only 记事本**
没有 `memory_update` / `memory_forget` 工具，没有 TTL/衰减，没有 active/superseded 状态。用户说「我改主意了，不去东京了」时，旧条目仍然存在且每次被注入，新旧矛盾并存。工具 doc 里让 Agent "supersede" 但没有机制支撑。对照：Mem0 的抽取管线对每条事实做 ADD/UPDATE/DELETE/NOOP 冲突裁决；Zep 的时序知识图谱为每条事实维护 valid-time（事实何时为真）+ ingestion-time + 置信度 + 过期；OpenClaw 条目带 observed 日期 + active/superseded 元数据、原地替换。
*建议*：① 增加 `memory_update`/`memory_forget` 工具（底层实现为按条目定位替换/删除）；② 条目格式带上 observed 日期与状态标记；③ Dreaming 时对过期/被取代事实做 supersede（旧条目标记为 `~~superseded~~` 或移入归档区）。

**8. 每日笔记保真度太低**
只记录最终一问一答、两侧各截 60 字符：工具动作、文件操作、决策这些**任务型网盘最有价值的 episodic 信息全部丢失**；闲聊路径（chat 模式）完全不记笔记；实测 notes 里还混入了 tool_call 残留文本（`[13:24] ... → <tool_calls>`）。Dreaming 建立在这样的低质量原料上，蒸馏上限很低。
*建议*：① 记结构化事件行：`[时间] 动作 | 工具 | 文件 | 结果`（loop 里已有 tool_trace，直接序列化即可）；② 截断放宽到 200+ 字符；③ 闲聊也写一行笔记。

**9. 会话摘要覆盖不全，衔接断裂**
摘要仅在 message_count ≥ 12 时由前端触发**一次**，且只取最近 100 条中的 30 条——长会话后半段永不入摘要；摘要只注入**最近 5 个**会话，更早的会话摘要既不在上下文也不可检索；`needs_summary` 依赖前端在线，用户关掉页面就没摘要。
*建议*：① 后端在对话结束时自动触发摘要（异步）；② 长会话做**增量摘要**（与 rolling_summary 联动，修好问题 3 后自然成立）；③ 会话摘要文件纳入 `memory_search` 检索范围。

**10. 并发与原子性缺失**
`_dream()` 的检查-写入非原子：两个并发任务路径请求可能同时通过 `last_dream()` 检查 → 重复蒸馏、重复条目；`remember()`/`daily_note()` 追加无文件锁，多请求并发写可能交错。容器单例 + 异步协程使这些竞态真实存在。
*建议*：进程内 `asyncio.Lock` 包住 dream 与记忆写入；dream 标记先写后跑（claim-then-work）。

**11. 无记忆卫生治理：无归档、无召回环路防护、无来源追踪**
MEMORY.md 无限增长无治理；注入上下文的记忆被 Agent 再次 `remember` 的**召回环路**没有防护（OpenClaw 对注入内容打标记、永不重复抽取，"一个事实被召回一百次还是一个事实"）；无 provenance（用户说的 vs Agent 推测 vs 工具输出）与置信度，无法区分可信事实与猜测（OpenClaw 有 owner/agent/untrusted/system 来源分级 + 会话类型门控）。
*建议*：① 注入的记忆片段加 `<!-- recalled -->` 标记，remember 工具跳过含标记内容；② 条目带来源标注；③ 预算超限时自动把 MEMORY.md 旧条目归档到 `notes/archive/` 并在 MEMORY.md 留一行索引。

### 🟢 轻微（打磨项）

- **12. USER.md 日期失真**：每次 `set()` 把所有偏好的日期刷成今天，日期字段失去"何时观察到"的意义（OpenClaw 的 observed-date 语义）。
- **13. 标记文件比较依赖 ISO 字符串**：`last_dream() >= yesterday` 的字典序比较在日期格式恒定时可行但脆弱，建议改成 `date` 对象比较。
- **14. `yesterday_notes()` 包含文件标题行** `# YYYY-MM-DD`，会混入蒸馏语料；`get_memory_file()` 要求文件名精确匹配，笔记多了之后 Agent 很难猜对。
- **15. 测试覆盖薄弱**：test_memory.py 只覆盖初始化/迁移/基本读写，**没有** dreaming 质量、截断方向、USER.md 覆盖、并发、超预算等测试——上面 1/2/3 三个 🔴 都是单测就能拦住的回归。
- **16. 现场数据已出现污染苗头**：`backend/data/Agent/notes/2026-08-13.md` 含 tool_call 残留文本，`MEMORY.md` 出现同日重复标题——建议顺手清理并加格式约束。

---

## 优秀案例对比

| 能力 | OpenClaw | Claude Code | Mem0 | Letta | Zep | **Agent Drive 现状** |
|---|---|---|---|---|---|---|
| 分层 | instructions / curated core / episodic / review 四层 | CLAUDE.md(用户写) + auto-memory(Agent 写) 双层 | 用户/Agent/会话三维隔离 | core(工作) / recall(语义检索) / archival(归档) 三层 | episodic/semantic/community 子图 | ✅ 三层结构对，缺 review 层 |
| 检索 | **embedding + BM25 混合 + 新近度/重要性 + MMR** | 直接注入（200 行/25KB 上限） | 向量+图双写、语义检索+重排 | 语义检索 | 图+语义混合检索 | ❌ 裸子串匹配 |
| 写入治理 | **Dreaming 是 MEMORY.md 唯一写入者**，确定性阈值门控 + LLM 在门内 | Agent 自主写，/memory 可审计编辑 | 抽取管线 + **ADD/UPDATE/DELETE/NOOP 冲突裁决** | **自编辑记忆**（memory_replace/insert） | 置信度门控抽取 | ❌ append-only，Agent 直接写 MEMORY.md 无门控 |
| 生命周期 | observed 日期 + **active/superseded**、超预算告警+归档 | 用户手动清理 | **衰减/expiry**、时间戳版本 | 心跳自维护 | **valid-time + 过期** | ❌ 无遗忘/无过期/无状态 |
| 溯源防污染 | **provenance 分级 + 会话类型门控 + 召回环路防护** | 无（信任用户） | 用户/Agent 维度隔离 | 无 | 置信度+时间双时间线 | ❌ 无来源标注 |
| 可审查性 | DREAMS.md + phase 报告，人类可读 | /memory 面板审计编辑 | 管理 API | 记忆块可视化 | 管理 API | ⚠️ 文件可看但无引导/无面板 |
| 压缩衔接 | **压缩前 memory flush**，压缩不丢记忆 | 内置 compact | 摘要管线 | 上下文管理 | 摘要 | ❌ 压缩摘要被丢弃（问题 3） |

**我们缺什么（一句话）**：OpenClaw 把"写"当成最难的部分——确定性门控 + 唯一写入者 + 溯源防污染；我们把写路径完全交给了 prompt 自觉。Mem0/Zep 把"生命周期"当成一等公民——更新/删除/过期/置信度；我们是 append-only。Claude Code 告诉我们最小可用集也可以很成功——Agent 自主写 + 用户可审计；我们缺审计入口和项目级 scope（网盘语境下可以做成**每个项目文件夹内各自的 `Agent/` 记忆**，即文件夹级记忆作用域）。

---

## 优先级建议（最值得做的 3 件事）

**① 修好注入链路的三处 🔴（预计 0.5 天，零依赖，纯 bugfix）**
尾部截断（问题 1）、USER.md 合并回写 + 每次请求刷新（问题 2）、滚动摘要放行并注入（问题 3）。这三处是"存了等于没存"的致命伤，修完整个系统从"看起来有记忆"变成"真的有记忆"。**先做这个，其他一切都建立在它的地基上。**

**② 给 MEMORY.md 加生命周期：update/forget 工具 + 条目元数据 + 去重 Dreaming（预计 2~3 天）**
对齐 OpenClaw/Mem0 的核心教训：记忆系统的成败在写路径。具体：`memory_update`/`memory_forget` 工具；条目统一为 `- [observed日期] [来源] [状态] 内容` 格式；Dreaming 带现有 MEMORY.md 上下文、结构化 JSON 输出、写前去重、**补跑机制**（问题 4/5/7）。顺带加 asyncio 锁 + 超时（问题 10）。

**③ 检索升级：BM25 + 分词 + 新近度加权，并把会话摘要纳入检索（预计 1~2 天）**
第一步先做零依赖的 BM25/分词/日期加权，即可解决"关键词换种说法就搜不到"的 80% 痛点（问题 6/9）；向量检索作为后续可选增强（本地 embedding 可离线部署，不破坏私人网盘的隐私定位）。

**做完 ①②③ 后的下一个自然里程碑**：记忆卫生治理（问题 11：召回环路防护、来源标注、归档）+ 测试补齐（问题 15）+ DREAMS.md 式人类可读巩固日志（用户能看到"昨晚 Agent 记住了什么"，也可一键撤销）。

---

## 附录：问题复现记录

以下 bug 均已在本仓库代码上实测复现（2026-08-13）：

```
TEST1  memory_text(2000)：30 条记忆后，含最旧条目、不含最新条目   → 问题 1 确认
TEST2  set() 后 USER.md 自定义 section「沟通风格」丢失              → 问题 2 确认
TEST3  搜索"蓝色"命中"群青色"=False；搜索"color"命中中文= False     → 问题 6 确认
TEST4  同日 32 次 remember → 32 个重复 "## 2026-08-13" 标题        → 问题 5 确认
TEST5  build_history 过滤掉 "[早期对话摘要]" system 消息            → 问题 3 确认
现场    backend/data/Agent/MEMORY.md 两个同日标题；
        notes/2026-08-13.md 含 tool_call 残留文本                  → 问题 5/8 确认
代码    MemoryStore 为容器单例，_load_user_md() 仅启动时执行        → 问题 2 确认
代码    _dream() 仅在任务路径调用、只处理 yesterday、无超时/无锁     → 问题 4/10 确认
```

审查范围：preferences.py（MemoryStore）、sessions.py（SessionStore）、tools/memory.py（3 个记忆工具）、loop.py（_dream/daily_note/压缩衔接）、prompt.py（注入）、context.py（build_history/compress_history）、api/v1/chat.py、api/v1/sessions.py、frontend ChatPanel.jsx（摘要触发/会话恢复）、container.py（生命周期）。
