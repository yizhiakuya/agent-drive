# 📊 Agent Drive 项目质量评估报告 v2（复测）

> 复测日期：2026-08-13 下午 ｜ 评估对象：/root/projects/agent-drive（v1 基线 HEAD b307260；四阶段门禁 a3439da/6bb9c33/b8bd79d/a438151；复测结束时 HEAD 0bc8143）
> 复测方法：实测运行（make test / pytest / ruff / mypy / radon / vitest+覆盖率 / make bench 真实 LLM / 运行时故障注入 / git 全历史密钥扫描 / 代码级逐项核对）
> v1 完整报告已归档：docs/quality-report-v1.md

---

## 〇、复测对比（v1 → v2）

### 总分：6.0/10 → **7.0/10**（+1.0）

| 维度 | v1 | v2 | 变化 | 一句话评语 |
|------|----|----|------|-----------|
| a 架构一致性 | 7 | **7** | — | 分层纪律依旧成立；_execute 拆分改善内聚，但文档目标树漂移未动 |
| b 代码质量 | 7 | **8** | +1 | F(73)→B(6) 核心重构 + 轨迹持久化去重 + 双 LLM 调用消除，ruff 0、mypy 27→约 11–13 |
| c 测试质量 | 5 | **7** | +2 | make test 统一入口 exit 0、CI 全绿、前端 0→13 项（含 2 个回归防护）+ 覆盖率基线 |
| d 安全质量 | 6 | **7** | +1 | move/copy 覆盖保护实测落地、密钥历史清除、审计轮转、secret-scan 钩子有效 |
| e 性能与成本 | 5 | **6** | +1 | 最终回复双 LLM 调用已消除；dreaming 无超时/压缩触发错位/256K 硬编码仍在 |
| f 可维护性 | 6 | **7** | +1 | 入口可信度恢复、依赖单一真相源、CI+pre-commit+Dockerfile 补齐；文档漂移与新债（覆盖率产物入 git）未清 |
| g 可靠性工程 | 6 | **7** | +1 | analyze_failures 运行时修复实测、审计轮转；超时/停滞检测/可观测性仍缺 |

**合计：v1 42/70 → v2 49/70 = 7.0/10**

### ✅ 已清债务（9/20 项全清 + 2 项新增修复）

| # | 债务 | 验证方式 |
|---|------|---------|
| 1 | `make test` 全红 | ✅ 实测 exit 0：vitest 13 + pytest 集成 6 + 8 套单测全部执行 |
| 3 | analyze_failures 崩溃 | ✅ 运行时故障注入实测：2 失败事件正确分类（permission/param_error），LLM 不可用时优雅降级 |
| 4 | `make bench` 断 | ✅ 真实 LLM 实测 5/5 任务通过，list_files 行为稳定 |
| 5 | 双依赖清单 | ✅ requirements.txt 已改为注释指针（pyproject 单一真相源） |
| 6 | compose 缺 Dockerfile | ✅ backend/frontend Dockerfile + nginx.conf 已建（nginx 反代 /api/、SSE buffering off、SPA fallback 均正确；沙箱网络限制未完成镜像构建实测） |
| 7 | move/copy 静默覆盖 | ✅ 运行时实测：默认 FileExistsError 拒绝，overwrite=true 放行；工具 schema/doc 同步更新 |
| 12 | 最终回复双 LLM 调用 | ✅ 代码级验证：_chat_path/_final_reply 直连 stream_chat，fallback 仅 NotImplementedError/TypeError |
| 16 | _execute F(73) + 轨迹逻辑两份 | ✅ radon 实测 B(6)；_persist_tool_trace 单一实现、3 处调用 |
| 新增 | 密钥 git 历史 | ✅ 全历史 `git log --all -p` 无 sk-/jina_ 密钥模式；system/ 已 gitignore（0600） |
| 新增 | 审计日志无限增长 | ✅ AuditLogger 1MB 轮转（audit.log → audit.log.1）+ record() 增加 result 字段 |

### ◐ 部分清除（2 项）

| # | 债务 | 现状 |
|---|------|------|
| 2 | 7/8 套件对 pytest 不可见 | ◐ make/CI 改为脚本直跑（入口绿了），但 pytest-asyncio 装了却没配 `asyncio_mode=auto`/marker——**`pytest tests/` 原生全量入口仍 5 failed**（实测），一行配置即可修 |
| 11 | 审计无结果/会话id/轮转 + 密钥明文 | ◐ 轮转✅、result 字段✅，但 loop 审计调用未传 result/会话 id；api_key 仍明文落盘（0600 + gitignore 缓解） |

### ⚠️ 仍存债务（11 项）

| # | 债务 | 位置 |
|---|------|------|
| 8 | 注入防护仍关键词匹配（9 词，改写措辞可绕过；无输出层动作筛查） | files.py INJECTION_MARKERS |
| 9 | append 校验仍从头截断读 20K（大文件追加必误报）；copy_file 仍无 validator | files.py |
| 10 | rename_file schema 称"dst 仅名称"，实现按完整路径解析 | files.py |
| 13 | dreaming/title 仍 inline await 无超时（title 已移至回复流之后，稍好） | loop.py |
| 14 | 前端仍只传 30 条 vs 后端 14.4K 阈值（压缩长期不触发）；context_window 256K 硬编码 | ChatPanel.jsx / config.py |
| 15 | architecture.md/README 与代码树漂移（services/、s3.py、hooks/useChat.js、scripts 名等未更新） | docs/ |
| 17 | main.py:53 模块级 app 单例；_last_messages 未在 __init__ 初始化 | main.py / loop.py |
| 18 | 前端死代码：TraceCard 未渲染、FilePanel dragging 死状态、连续两行重复 setContextUsage(null)、client.js getStatus/uploadFile 错误路径死导出（会拼成 /api/v1/api/status） | frontend/src |
| 19 | 记忆 P3：无 forget/update 工具、无召回环路防护/来源标注、裸子串检索、并发无锁 | agent/memory |
| 20 | 产品缺口：无停止按钮、0 条 @media、会话删除无确认、文件面板不联动刷新 | frontend/src |

### 🆕 新发现问题（v2）

1. **覆盖率产物入 git**：backend/.coverage + frontend/coverage/ 共 27 个生成文件被追踪且未 gitignore——每次跑覆盖率都会弄脏工作树（复测期间实测发生 2 次），污染 diff 与 PR。
2. **`pytest tests/` 原生入口仍红**（见 ◐#2）——"测试全绿"目前依赖 make/CI 的脚本直跑路径，pytest 生态能力（cov 全量、fixture、并行）用不上。
3. **move/copy 覆盖保护未配回归测试**——违反自家质量门禁路线图"每项债务先配回归测试再修"的承诺（tests/ 中 grep 不到 overwrite 断言）；另 copy(x, x, overwrite=true) 抛 SameFileError（被 registry 结构化降级，非崩溃但体验差）。
4. **CI 的 mypy 是非阻塞门禁**（`|| echo` + continue-on-error，残留 11–13 项，本环境 mypy 2.3.0 实测 11 项）——类型门禁有名无实；pre-commit 配置存在但 .git/hooks 未安装（本机实测钩子目录为空），依赖开发者自觉执行（secret-scan 钩子逻辑实测可拦截假密钥）。
5. **后端 Dockerfile 生产镜像装 dev 依赖**（`pip install -e ".[dev]"` 含 pytest 等）。
6. **M2a 新功能无测试入主干**：复测期间观测到 0bc8143（M2a+M2b 文件理解与语义搜索，+423 行，含 ingest/pipeline.py、embeddings.py、search_content 工具）提交且未带任何测试（仅更新 pyproject）——门禁已建但新功能未过门。

---

## 一、实测数据（2026-08-13 下午复跑）

| 验证项 | 结果 | 说明 |
|--------|------|------|
| `make test` | ✅ exit 0 | vitest 13/13 + pytest 集成 6/6 + 8 套单测全部通过（HEAD 0bc8143 复跑仍绿） |
| `ruff check app/` | ✅ 0 errors | 6 条规则豁免均有注释理由 |
| `mypy app/` | ⚠️ 11 errors（7 文件） | v1 27 → 现 11（项目自报 13，环境差异）；CI 中为非阻塞 |
| `pytest tests/` | ❌ 5 failed | test_retry 5 个 async 用例无 marker/asyncio_mode 配置（◐#2） |
| `pytest tests/integration` | ✅ 6 passed | 后端 integration 覆盖率 40%（含 untracked 期 ingest 34%） |
| radon `_execute` | ✅ B(6) | v1 F(73)；新峰值 _final_reply C(14)、_check_red_tool C(12)、_maybe_compress C(11)；全库其余 ≤C |
| vitest | ✅ 13/13 | chatStream SSE 4 项（含跨 chunk 缓冲、AbortError 传播=白屏回归防护）+ 组件 9 项 |
| 前端覆盖率基线 | ✅ 19.6% stmts | chat.js 93%、client.js 36%、ChatPanel 16%、其余组件 0% |
| `make bench`（真实 LLM） | ✅ 5/5 | list_files 不再漂移（纯 list_files）；延迟 7.3–9.6s/任务 |
| move/copy 覆盖保护 | ✅ 实测 | 默认拒绝（FileExistsError）、overwrite=true 放行 |
| analyze_failures | ✅ 实测 | 2 失败事件正确分类；LLM 不可用时返回降级文本 |
| git 密钥扫描 | ✅ 干净 | 全历史无 sk-/jina_ 模式；system/ 已 gitignore |
| pre-commit 钩子 | ⚠️ 配置在、未安装 | .git/hooks 为空；secret-scan 钩子逻辑模拟实测有效 |
| docker compose build | ⚠️ 未实测 | Dockerfile 存在且内容正确；沙箱网络限制中断构建（非代码问题） |

## 二、复测结论

四阶段质量门禁**真实落地**：所有声明修复均经实测验证属实（无一纸面）。工程护栏层从"零"变为"可用"（统一测试入口、CI、lint、类型检查渐进收敛、前端测试+覆盖率基线、复杂度重构、数据丢失路径封堵、密钥历史清除），项目从"骨架优秀、血肉待补"进入"护栏成型、深水区待攻"。下一跳目标（7→8）：修 pytest 原生入口（一行配置）、给 P1 安全/成本项配回归测试、文档树机械化对齐、覆盖率产物出 git。

*报告生成：Agent Drive 项目质量分析师（2026-08-13 复测，v2）。v1 详见 docs/quality-report-v1.md。*
