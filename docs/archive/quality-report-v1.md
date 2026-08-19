# 📊 Agent Drive 项目质量评估报告

> 评估日期：2026-08-13 ｜ 评估对象：/root/projects/agent-drive（HEAD b307260，20 commits）
> 评估方法：实测运行（8 套单测 + pytest + 前端构建 + 真实 LLM 基准 + 静态复杂度分析 + git 历史统计）
> 基线材料：docs/architecture.md · agent-definition.md · review-summary.md · 四份详细审查报告（loop/memory/tools/product）

---

## 一、总评分卡

| # | 维度 | 评分 | 一句话评语 |
|---|------|------|-----------|
| a | 架构一致性 | **7/10** | 五层依赖纪律真实落地（领域层零 HTTP 依赖），但文档目标树多处未兑现/未更新 |
| b | 代码质量 | **7/10** | 模块职责清晰、命名一致、文档完备，但核心编排函数复杂度 F(73) + 少量重复/死代码 |
| c | 测试质量 | **5/10** | 8 套单测全绿且覆盖 P0 回归，但 `make test` 官方入口是红的、7/8 套件对 pytest 不可见、前端零测试 |
| d | 安全质量 | **6/10** | P0 九项全部验证修复（HMAC 确认/脱敏/注入三层防护），残余风险：关键词级注入可绕过、move/copy 静默覆盖、密钥明文落盘 |
| e | 性能与成本 | **5/10** | 路由+工具组检索+压缩已省下结构成本，但最终回复双 LLM 调用、首包被 dreaming 阻塞、无成本上限 |
| f | 可维护性 | **6/10** | 文档体系优秀（886 行含四份审查）且提交信息清晰，但 make test/bench 已损坏、双依赖清单、无 lint/CI |
| g | 可靠性工程 | **6/10** | 指数退避重试 + Actor-Critic 校验 + 审计闭环是亮点，但 analyze_failures 运行时崩溃、审计缺工具结果、无超时/停滞检测 |

### **总分：42 / 70 = 6.0 / 10**

**结论：架构骨架与工程理念优秀（业界对标不落下风），P0 修复扎实可信；当前扣分主要来自"工程护栏层"——测试入口损坏、质量门禁为零、若干 P1 债务未清。这是一个"骨架优秀、血肉待补"的 6 分项目：补齐测试入口 + CI + 三四处运行时 bug 即可快速升到 7+。**

---

## 二、实测数据基线

### 2.1 代码量（不含依赖/构建产物）

| 类别 | 行数 | 明细 |
|------|------|------|
| 后端应用代码 | 3,775 | 31 个模块；最大 loop.py 598 行、files.py 354 行、preferences.py 307 行；其余模块 <160 行 |
| 后端测试 | 996 | 8 套脚本式单测 + pytest 集成测试（test_api 87 行） |
| 前端源码 | 965 | ChatPanel.jsx 387 行、Onboarding.jsx 120 行、styles.css 154 行 |
| 文档 | 886 | 架构 154 + 定义 102 + 审查 4 份 589 + 汇总 41 |
| 技能包/脚本 | 378 | 3 个 SKILL.md + mock_llm + benchmark_real |

### 2.2 测试套件运行结果（2026-08-13 实测）

| 入口 | 结果 | 说明 |
|------|------|------|
| 8 套脚本式单测（python3 直跑） | ✅ 8/8 全部通过 | test_agent(4) / test_critic(6) / test_reliability(9 断言, Princeton 四维度) / test_bugfixes(8 项 P0 回归) / test_compress(11) / test_memory(16) / test_retry(5) / test_write_tools(8)，合计约 67 个断言 |
| `pytest`（backend 裸跑） | ✅ 6 passed | pyproject `testpaths=["tests/integration"]` 只收集集成测试 |
| `make test`（官方入口） | ❌ **exit 2，5 failed** | pytest-asyncio 未安装 → test_retry.py 5 个 async 用例全部报 "async def functions are not natively supported"；make 在第一行命令失败后终止，后续 7 套脚本根本不会执行 |
| `make bench`（官方入口） | ❌ **目标损坏** | 引用的 `tests/integration/test_benchmark_real.py` 已不存在（脚本已迁到 scripts/benchmark_real.py，且该脚本内 `Path(__file__).parent / "system"` 路径错误，永远报"LLM 未配置"） |
| 前端 `npm run build` | ✅ 通过 | gzip 约 100KB（js 318KB → 100.78KB） |
| 真实 LLM 基准（本次补跑 repeat=1） | ⚠️ 4/5 | 见 §4.5 |

### 2.3 Git 提交历史

- **20 个提交、同一天（12:30–14:24，约 2 小时）、单作者**（Agent Drive Dev）
- 类型分布：feat 11 / fix 6 / test 1 / refactor 1 / docs 1 → **修复密度 30%**
- 里程碑：01c50cc 初始架构（+5,759 行）→ dd50471 Agent v2 → 73d0dc5 单一职责拆分（297+/284-）→ ece7787 上下文管理 → 7ff4595 记忆系统 → 2f841f2 **P0 九项修复（13 文件 +545/-69）** → c95c22a 工具步骤内联 → 2 个后续回归修复（abortRef 白屏、历史工具记录消失）
- 观察：c95c22a 新功能引入后紧跟 2 个 fix（白屏/记录丢失），说明**前端改动靠手测兜底、无自动化测试拦截回归**

### 2.4 依赖与版本

- `pyproject.toml`：fastapi/uvicorn/openai/anthropic/pydantic/pydantic-settings/tiktoken；dev extra 仅 `pytest + httpx`
- `requirements.txt`：与 pyproject **运行时依赖逐行重复（双份真相源，已有漂移风险）**，且不含 dev 依赖
- **缺失**：pytest-asyncio（导致 make test 红）、ruff（pyproject 有 `[tool.ruff]` 配置但未安装、无 CI 执行）、mypy/pyright、pytest-cov
- **无 CI 配置**（.github/ 不存在）、无 pre-commit、docker-compose.yml 引用 `build: ./backend` `build: ./frontend` 但**两个 Dockerfile 均不存在**（compose build 必失败）
- 环境：Python 3.10 运行正常（pyproject requires >=3.10，`X | None` 语法使用正确）

### 2.5 复杂度快照（radon）

| 函数 | 圈复杂度 | 评级 |
|------|---------|------|
| `AgentLoop._execute`（loop.py，约 300 行单生成器） | **73** | **F（极高）** |
| `MemoryStore._migrate_from` | 18 | C |
| `router.classify` | 15 | C |
| `OpenAIResponsesProvider.chat` / `AnthropicProvider.chat` | 13 / 11 | C |
| 其余模块函数 | ≤10 | A/B ✅ |

---

## 三、各维度详细分析

### a. 架构一致性 — 7/10

**符合纪律的证据：**
- ✅ 五层单向依赖真实成立：`grep fastapi/starlette/httpx` 于 app/agent、app/llm、app/storage 零命中——领域层完全不依赖 HTTP 框架，分层纪律不是纸面文章
- ✅ 组合根模式：`Container` 显式组装全部依赖（logger/audit/llm/storage/memory/sessions/skills），`main.py` 只做工厂+挂载；无模块级可变单例（唯一例外见下）
- ✅ 版本化 API `/api/v1` + router 聚合、Pydantic schemas 独立目录、storage 用 Protocol 抽象（local 可平滑换 s3）、llm 三协议适配（base/manager/providers）、core 层 config/logging/errors/container 职责单一
- ✅ Agent 模块单一职责拆分（73d0dc5）：loop/prompt/context/confirm/router/skills/memory 边界清晰，与 architecture.md §4 目标树高度对应

**偏差证据：**
- ❌ **Application 服务层（services/）不存在**：architecture.md §一 定义"services/（会话服务·文件服务·配置服务）"为应用层，实际代码中 API 路由直接调用 container/AgentLoop 编排，应用层与接口层合并——功能正确，但与文档分层图不符
- ❌ **文档目标树多处未兑现**：`storage/s3.py`、`llm/types.py`、`ingest/pipeline.py`（README 也称"ingest/ M2 摄入管线占位"，实际目录不存在）、`scripts/run_dev.sh`/`benchmark.sh`（实际是 mock_llm.py/benchmark_real.py）、`frontend/src/hooks/useChat.js`（hooks/ 是空目录）、`components/common/` 均缺失
- ❌ **文档未反向更新**：v2/v3 新增模块（onboarding.py、tools/memory.py、tools/plan.py、core/retry.py、test_bugfixes/test_compress/test_memory/test_retry/test_write_tools）未写进 architecture.md 目录树
- ❌ `main.py:54` 模块级 `app = create_app()`：与 architecture.md §3.1"禁止模块级全局单例"字面冲突（每次 import 都真实构造 Container 并触碰文件系统；测试靠注入替身绕开）

### b. 代码质量 — 7/10

**优点：**
- 命名统一（files 组 `*_file(s)` 动词后缀、system 组 get_/set_/add_/remove_ 前缀、常量表集中）；全代码库 **0 个 TODO/FIXME**（grep 验证）
- 每个工具按"API 文档标准"写 doc（用途/参数/输出/前置条件/错误情况），`{ok:false, error}` 结构化错误贯穿全链路，LLM 可读可重试
- 模块小而专：31 个模块中 27 个 <160 行；docstring 齐全；类型标注（`X | None`）覆盖主路径
- 修复代码带溯源注释（"修复 R2/memory-review #2/A2"等），评审报告与代码可互查

**问题：**
- ❌ `AgentLoop._execute` 圈复杂度 **F(73)**：路由/确认恢复/工具循环/流式回复/持久化/摘要触发全塞在一个 300 行生成器里，是最主要的理解与修改风险点
- ❌ 重复代码：loop.py 最终回复分支内联复制了 `_persist_tool_trace()` 的完整循环体（方法本身也存在，同一逻辑两份）；chat 路径的 context_usage 计算内联重复 `_context_usage()` 逻辑
- ❌ `self._last_messages` 未在 `__init__` 初始化（chat 路径不会赋值），属性生命周期不清晰
- ❌ 死代码：前端 `trace` state + `TraceCard` 组件已完全不渲染（每次 tool_trace 仍更新 state 触发无谓重渲染）；client.js 中 getStatus（指向 `/api/v1/api/status`）、uploadFile（指向 `/api/files/upload`）是错误路径的死导出；FilePanel 的 `dragging` state 从未被 set；ChatPanel.loadSession 连续两行重复 `setContextUsage(null)`
- ❌ 异常体系半落地：errors.py 定义了 AppError 五子类，但 API 层只捕 ConfigError，LLMError/ToolError 从未被 raise（provider 直接抛 SDK 原生异常）——"错误分类可预测"只做到一半

### c. 测试质量 — 5/10

**优点：**
- 8 套单测（约 67 断言）**直跑全绿**，含 P0 九项修复的专项回归套件 test_bugfixes（路由/压缩结构/摘要放行/尾部截断/USER.md 保护/签名确认伪造重放/审计脱敏），修复-测试绑定做得对
- test_reliability.py 按 Princeton 四维度（Consistency/Robustness/Predictability/Safety）组织，用 ScriptedProvider 端到端跑 AgentLoop——测试理念在同类项目里靠前
- 集成测试用临时目录 + 注入 Container，不污染真实数据；`npm run build` 可作为前端健康门禁

**缺口（扣分主因）：**
- ❌ **官方入口 `make test` 是红的**（pytest-asyncio 缺失 → 5 failed、exit 2），且失败发生在第一行，**后续 7 套脚本在 make 下从未被执行**——"测试全绿"只对手动逐个运行成立
- ❌ **7/8 套件是 `async def main()` 脚本式，pytest 完全收集不到**（collection 仅 11 个：6 集成 + 5 retry）；pyproject `testpaths` 又只指 integration——三套测试体系互相看不见，无统一入口、无覆盖率数据
- ❌ 前端 **0 测试**（无 jest/vitest/playwright），c95c22a 之后两个回归（白屏、工具记录丢失）正是前端手测盲区
- ❌ 覆盖空白：LLM provider 三协议无测试（含 Anthropic 消息转换、流式）；memory 无 dreaming 质量/并发/超预算测试（审查报告明确点名）；无 HTTP 层 chat 流式集成测试（chat 端点只有"未配置→400"一条）；无注入绕过/覆盖写/大文件 append 的负面测试
- ❌ 基准工具自身有 bug（config 路径、make bench 目标失效），且本次复跑 list_files 由早上的 3/3 变为失败（多余 read_file），说明基准未被纳入任何持续执行

### d. 安全质量 — 6/10

**P0 修复全部验证属实（含代码级核对 + test_bugfixes 实测）：**
- ✅ 路径穿越：`resolve()+is_relative_to` 全入口覆盖，符号链接逃逸实测被拦截（PermissionError）
- ✅ 确认机制 v2：HMAC-SHA256 签名 nonce + 10 分钟 TTL + 一次性消费 + 会话级持久化 pending + 确认后**确定性重放**（不再依赖 LLM 重新推导）+ 验证失败不覆盖原 pending；实测伪造/篡改/过期/重放全拒
- ✅ 审计脱敏：api_key 等 6 类敏感字段正则替换 `***`；`set_llm_provider` 升 red（原 yellow，数据外泄通道已关）
- ✅ 注入防护三层落地：系统提示第 10 条"数据≠指令"硬性原则 + read_file 命中 8 类注入标记附警示 + add_rule/set_preference 拒绝指令式文本并设长度/数量上限
- ✅ 三级分级 + delete_file 唯一 red + 6 个写操作 Critic validator + agent-config.json 权限 0600

**残余风险（P1 未清）：**
- ⚠️ **注入防护是关键词匹配**：标记词（"忽略/无视/ignore…"）换个说法即可绕过；无输出层动作筛查（对标 Claude Code auto mode 的 transcript classifier 完全缺失），yellow 写工具仍可被注入内容诱导自动执行
- ⚠️ **move_file/copy_file 静默覆盖同名目标**（tools 审查 A6 实测复现，本次复核代码仍是 `shutil.move/copy2` 直接替换）：yellow 级自动执行的数据丢失路径，未修复
- ⚠️ api_key 明文存 agent-config.json（0600 已缓解但无加密/环境变量引用）；审计日志无限增长无轮转
- ⚠️ 无认证边界：API 绑 0.0.0.0 + CORS `*`（JSON 请求有 preflight 兜底，但局域网内任意客户端可直连操控 yellow 工具）；确认签名密钥是进程级随机（重启后旧 pending 永久失效，属可接受折衷但需知晓）

### e. 性能与成本 — 5/10

**已做对的（结构成本）：**
- 意图路由：纯闲聊走轻量提示路径（build_chat_prompt 无工具手册）；任务路径按组检索工具（files 组手册约 1.2K token vs 全量 1.8K，实测估算）
- tiktoken 精确计数 + 双向截断 + 轮内压缩（修复后按完整 roundtrip 切，无 API 400 风险）+ 工具输出 2000 字符截断
- 流式 SSE 逐块渲染；重试带抖动避免风暴

**成本与延迟问题（实测/代码级）：**
- ❌ **最终回复双 LLM 调用未修复**（loop review M1）：无工具调用时先 `chat()` 拿完整结果、丢弃 content，再对同一份 messages 全量 `stream_chat()`——每轮最终回复 prompt token ×2、延迟 ×2；非流式 fallback 分支 completion token 双计（G1）仍在
- ❌ `_dream()`（每日首条消息）与 `_generate_title()`（每条首答）**inline await 阻塞首包**，LLM 挂起时无超时保护（G2）
- ❌ 压缩触发条件错位（M6）：前端只传最近 30 条，后端阈值 14.4K token，普通对话长期不触发压缩 → 早期上下文在窗口外永久丢失
- ❌ 无单次运行 token/成本上限、无运行超时、无停滞检测（连续重复同一工具调用会烧满 10 步）（M7）
- ❌ context_window 硬编码 256K（M11）：DeepSeek 64K/Ollama 32K 下进度条与实际窗口脱节，压缩阈值不随真实窗口收缩
- ⚠️ 真实 LLM 实测延迟偏高：本次复跑单任务 2.8–11.9s（早上报告 3.3–5.5s），list_files 从"纯 list_files"漂移为"list_files+read_file"（多一次工具往返+一次 LLM 调用）；未做 prompt caching（OpenAI 兼容前缀缓存可省 ~90% 前缀成本）

### f. 可维护性 — 6/10

**优点：**
- 文档体系是全项目最亮点：886 行文档含架构设计、Agent 定义规范、四份专业级审查（均带实测复现附录）+ 汇总路线图，新接手者可快速建立全局认知
- 提交信息语义化（feat/fix/refactor/docs/test），修复提交明确列出 1–10 条修复项；Makefile 提供 install/dev/test/bench/build 入口；记忆旧系统迁移兼容层完备

**债务：**
- ❌ **`make test` 红、`make bench` 断**，README §🧪 承诺的开发命令与实测不符——入口可信度受损
- ❌ requirements.txt 与 pyproject 双份依赖清单（改一处漏一处的漂移源）；dev 依赖不完整（缺 pytest-asyncio）
- ❌ 文档与代码漂移（见 a 维度清单）；review-summary 的 P1/P2/P3 路线图无状态跟踪（哪些已修哪些未修需人工核对，本次评估已代劳）
- ❌ 无 lint/类型检查/CI/pre-commit——代码质量靠人工自律维持，`tool.ruff` 配置是"纸面门禁"
- ⚠️ 巨型提交（初始 5.7K 行、单 UI 提交 1.6K 行）难以回溯定位；20 提交全在 2 小时内，无分支/PR 流程

### g. 可靠性工程 — 6/10

**已落地（亮点）：**
- `with_retry` 指数退避 + 抖动 + 瞬态/永久错误分类，三个 provider 的 chat/stream 全走 retry（test_retry 5 用例验证）
- Actor-Critic：6 个写工具带程序化 validator（写后读回、rename 后源消目标现、delete 后不存在、create_folder 幂等校验），失败返回结构化错误供模型重试/降级
- 优雅失败文化：dreaming/标题/摘要全部 try/except 不阻塞主流程；步数耗尽发说明文本（R3 已修）；压缩 LLM 失败回退滑动窗口
- 审计日志 JSONL 追加 + analyze_failures 规则分类（7 类）+ LLM meta-agent 设计（原则 5 落地意图）
- 会话持久化（jsonl + meta）+ 跨会话摘要注入 + 工具轨迹持久化（历史恢复）

**缺口：**
- ❌ **`analyze_failures` 运行时崩溃（本次新发现）**：`container.py` 传入的是 AuditLogger 对象，analytics.py 按 Path 调用 `.exists()/.read_text()` → AttributeError → 工具恒返回 `{ok:false}`。meta-agent 错误分析闭环形同虚设
- ❌ 审计日志只记工具调用参数，**不含执行结果/错误/会话 id/步数**（M5/D5 未修）——失败分析无米下锅；无轮转策略
- ❌ 无运行超时、无停滞检测、无每会话串行化/写锁（dreaming 检查-写入竞态、remember/append 并发交错，memory review #10）
- ❌ 工具层重试是固定 1 次 + 文本模式匹配（D3：脆弱，错误文案一变即失效）；registry 静默丢弃多余参数（D2：模型幻觉参数时掩盖错误）
- ❌ API 层错误未分类映射（M10）：LLM 故障→裸 500 英文异常串；前端状态 pill "Agent 已就绪"是静态文案，无健康检查轮询
- ❌ 无可观测性基建：无 latency/token 指标收集、无结构化日志查询、dev 环境日志非 JSON

---

## 四、技术债务清单（按优先级）

### 🔴 P0 — 已损坏的工程入口 / 运行时 bug（今天就能修，半天内）

| # | 债务 | 位置 | 影响 |
|---|------|------|------|
| 1 | `make test` 全红：pytest-asyncio 未安装，5 个 retry 用例失败 | pyproject `[project.optional-dependencies]`；Makefile:25 | 官方测试入口不可信，CI 无法落地 |
| 2 | 7/8 单测套件对 pytest 不可见（脚本式 async main） | tests/unit/test_*.py（除 test_retry 外） | 无统一测试入口、无覆盖率 |
| 3 | `analyze_failures` AttributeError（AuditLogger 当 Path 用） | app/core/container.py:79 → app/agent/tools/analytics.py:44 | 错误分析工具 100% 不可用 |
| 4 | `make bench` 引用不存在的文件；benchmark_real.py 配置路径错误 | Makefile bench 目标；scripts/benchmark_real.py:66 | 基准回归入口全断 |
| 5 | requirements.txt 与 pyproject 双份重复 | backend/ | 依赖漂移 |
| 6 | docker-compose 引用不存在的 Dockerfile | docker-compose.yml:19,30 | compose 构建必失败 |

### 🟡 P1 — 安全与正确性残余（1–3 天）

| # | 债务 | 位置 | 影响 |
|---|------|------|------|
| 7 | move/copy 静默覆盖同名目标（yellow 自动执行） | app/storage/local.py:move/copy | 数据丢失路径，需 overwrite 显式参数或升 red |
| 8 | 注入防护仅关键词匹配，无输出层动作筛查 | app/agent/tools/files.py:INJECTION_MARKERS | 改写措辞即可绕过，yellow 工具可被注入诱导 |
| 9 | append_file validator 从头截断读取，大文件追加必误报；copy_file 无 validator | app/agent/tools/files.py:_validate_appended | Critic 误报/缺位 |
| 10 | rename_file doc 称 dst 仅名称，实际按完整路径解析（与 move_file 重叠） | app/agent/tools/files.py:rename_file | 工具语义歧义（审查 B1 实测复现） |
| 11 | 审计日志无工具结果/会话 id/轮转；密钥明文落盘 | app/core/logging.py；system/agent-config.json | 溯源能力弱 + 密钥暴露面 |
| 12 | 最终回复双 LLM 调用 + fallback token 双计 | app/agent/loop.py（无工具分支） | 每轮成本×2、延迟×2 |
| 13 | dreaming/title inline await 阻塞首包且无超时 | app/agent/loop.py:_dream/_generate_title | 首包延迟 + 挂起风险 |
| 14 | 压缩触发错位（前端 30 条窗口 vs 后端 14.4K 阈值）；256K 硬编码窗口 | ChatPanel.jsx send()；app/core/config.py | 早期上下文永久丢失、进度条误导 |

### 🟢 P2 — 架构/体验/记忆增强（按 M2/M3 规划推进）

| # | 债务 | 位置 | 影响 |
|---|------|------|------|
| 15 | architecture.md/README 与代码漂移（services 层、s3.py、types.py、ingest/、hooks/、scripts 名） | docs/architecture.md、README.md | 上手成本、架构评审失真 |
| 16 | `_execute` 复杂度 F(73)；工具轨迹持久化逻辑两份 | app/agent/loop.py | 修改风险 |
| 17 | main.py 模块级 app 单例；_last_messages 未初始化 | app/main.py:54；loop.py | 导入副作用 |
| 18 | 前端死代码：trace state/TraceCard 未渲染、client.js 错误路径死导出、dragging 死状态、重复 setContextUsage | ChatPanel.jsx / client.js / FilePanel.jsx | 混淆 + 无谓重渲染 |
| 19 | 记忆系统 P3：无 update/forget 工具、无召回环路防护/来源标注、裸子串检索、dreaming 无补跑去重、并发无锁 | app/agent/memory/* | 记忆质量天花板 |
| 20 | 产品缺口：文件面板不联动刷新、无预览/下载、无停止按钮、无移动端（0 条 @media）、会话删除无确认 | frontend/src/* | 体验闭环断裂（product review P0 项） |

---

## 五、质量门禁路线图

### 阶段 0：修复测试入口（本周，1 天）
1. dev 依赖补 `pytest-asyncio`；test_retry.py 改标准 pytest 用例（或加 asyncio_mode=auto）
2. 把 7 套脚本式套件转换为 pytest 用例（保持断言不变）；`make test` = 单条 `pytest tests/` 且 **exit 0 才算过**
3. 修 analyze_failures（容器传 `self.audit.path` 或 analytics 接受 AuditLogger）、修 `make bench` 目标与脚本路径、清掉 requirements.txt 重复（改指向 pyproject 或加注释说明同步规则）
4. 重建 docker-compose 为可构建（补 Dockerfile 或改为 volume 挂载源码）

### 阶段 1：静态门禁（下周，1–2 天）
5. 启用已配置的 ruff（`ruff check` + `--fix` 第一轮）；`line-length=110` 已定，目标 0 error
6. mypy（或 pyright）对 `app/` 开 `--strict` 起步，先 core/llm/storage 再逐步放开 agent/
7. 前端加 eslint（无 lint 配置现状）+ 保持 `npm run build` 门禁
8. pre-commit 钩子：ruff + build 检查，阻止"纸面配置"

### 阶段 2：CI + 覆盖率基线（两周内）
9. CI（GitHub Actions）：backend pytest 全量 + 前端 build + ruff + mypy，PR 必须全绿
10. pytest-cov 引入，基线目标：`app/core` ≥80%、`app/agent` ≥70%、`app/storage` ≥85%；把 §四 P1 的 7–12 号债务每项配一个回归测试后再修
11. 定时（非 PR）跑真实 LLM 基准（修好的 benchmark），连续两次漂移即报警——本次复跑已显示 list_files 行为漂移的价值

### 阶段 3：E2E 与可观测（M2 前）
12. Playwright E2E 三条关键路径：Onboarding → 首条对话 → red 确认流程；会话切换串消息回归
13. 运行指标：每轮 latency/tokens 落结构日志或内存指标；前端状态 pill 接 /health；审计日志轮转 + 保留策略
14. 安全门禁：注入绕过用例集（改写措辞类）、move/copy 覆盖回归、密钥落地扫描（git secrets 类工具）
15. 修复 §四 P2 后，把架构文档/README 与代码树做一次机械化一致性检查（脚本 diff 文档树 vs 实际树），纳入 CI

---

## 六、评估方法论与可复现性

- 所有结论基于 2026-08-13 在本仓库 HEAD(b307260) 上的实测：`make test`（exit 2）、8 套单测直跑、`pytest`/`pytest tests/`、`npm run build`、真实 LLM 基准复跑（repeat=1）、radon 圈复杂度、grep 分层纪律检查、现场数据（MEMORY.md 同日重复标题、notes 含 tool_call 残留）核对
- 与四份审查报告对照：9 项 🔴 修复全部代码级验证属实（含新增 test_bugfixes 8 项回归）；审查中的 🟡 项（M1/G1/G2/M6/M7/M10/M11/A6/B1/C1/D1-D7 等）**多数未修**，本报告已将其逐项落入债务清单
- 评分权重说明：c/e 两维被"工程入口损坏 + 成本未优化"重点扣分；a/b 因分层纪律与文档质量真实过硬给 7 分；d/g 因 P0 修复扎实但有明确残余风险给 6 分

*报告生成：Agent Drive 项目质量分析师（2026-08-13）。*
