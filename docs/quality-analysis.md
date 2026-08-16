# 工程质量分析 Agent 协议

> 给仓库内的编码 Agent 与维护者：任何“工程质量 / 可维护性 / 代码质量 / 审查”类任务，
> 一律按本协议执行。本文件是**持久 agent 规格**——每次直接照做，不要每次重写流程。

## 0. 红线（最高优先级）

1. **分析阶段只读**：不修改、不移动、不重写任何文件（含“顺手格式化”）。
2. **先汇报，后动手**：分析完成后输出结构化报告并停下，等待用户确认；
   只有用户明确说“直接修 / 按你的方案改 / 继续修”之后，才进入修复阶段。
3. 修复阶段才按 AGENTS.md「修改检查单」走门禁与文档同步（铁律 §0）。

## 1. 基线 vs 增量（避免重复劳动）

**架构与技术栈属于基线，不每次重审**——正常情况下不会频繁换架构/技术栈。
日常分析只做“增量检查”，引用基线结论即可。

- **基线审计（一次性，或在重大变更时）**：架构分层、模块职责、技术栈选型、
  依赖清单、部署形态。审计结论记录在下方「基线快照」，带日期；之后直接引用，
  不再展开分析。
- **增量检查（每次执行）**，按序完成：

| # | 检查项 | 方法 |
|---|--------|------|
| 1 | 门禁现状 | 跑 AGENTS.md §2 的 backend/frontend/android 门禁，记录红绿 |
| 2 | 代码风格一致性 | lint/格式化是否全绿；命名、注释语言、图标/token 用法、错误处理风格是否与相邻代码一致 |
| 3 | 耦合与重复 | 跨模块 import 方向是否符合分层；重复逻辑（如两套文件 UI、重复请求封装）只报告、给抽取建议，不先动手 |
| 4 | 静态检查与类型 | ruff/mypy/eslint/tsc 的结果（mypy 当前为 0 错误阻断） |
| 5 | 测试覆盖与缺口 | 全量通过数；本次/近期改动是否有对应回归测试；指出高风险无测试路径 |
| 6 | 复杂度热点 | 文件行数/函数长度 Top N、嵌套深的模块（只报告，不做重构计划） |
| 7 | 死代码与残留 | `console.*`、TODO/FIXME、未用导出/导入、失效 eslint-disable、遗留临时文件 |
| 8 | 文档同步 | 按铁律 §0 抽查：近 N 次提交的行为变更是否同步 docs/AGENTS |
| 9 | 安全/健壮性抽查 | 对照 AGENTS.md「关键约定与坑位」清单核对相关改动（错误路径、并发、原子性、失败关闭） |

## 2. 标准命令（可直接复制）

```bash
# 后端（workdir: backend）
ruff check app/ && python3 -m mypy app/
python3 -m pytest tests/unit -q && python3 -m pytest tests/integration -q
for t in test_agent test_critic test_reliability test_retry test_compress test_write_tools test_memory test_bugfixes; do python3 tests/unit/$t.py || exit 1; done

# 前端（workdir: frontend）
npm run lint && npm test && npm run build

# Android（workdir: frontend/android；本机需 ANDROID_HOME=/root/.bubblewrap/android_sdk）
ANDROID_HOME=/root/.bubblewrap/android_sdk ANDROID_SDK_ROOT=/root/.bubblewrap/android_sdk bash ./gradlew testDebugUnitTest

# 复杂度/重复辅助（只读）
wc -l backend/app/**/*.py frontend/src/**/*.{ts,tsx} 2>/dev/null | sort -rn | head -20
grep -rn "console\.\|TODO\|FIXME" frontend/src backend/app --include='*.ts' --include='*.tsx' --include='*.py' | grep -v test
```

## 3. 报告模板（固定结构）

```markdown
# 工程质量分析报告（YYYY-MM-DD）

## 基线结论（引用 docs/quality-analysis.md 基线快照，不重述）
架构/技术栈：见快照（无重大变更则一句带过）。

## 门禁结果
| 后端 | ruff/mypy/unit/integration/8 脚本 | ✅/❌（附失败项） |
| 前端 | lint/vitest/build | ✅/❌ |
| Android | JVM 单测 | ✅/❌ |

## 增量发现（只列增量检查项，按严重度）
### 明确问题（可复现、给出文件:行号与证据）
### 建议（风格/耦合/测试缺口，不构成 bug）
### 已达标（一句话确认，不要铺开）

## 建议的下一步（分组列优先级，等用户挑选；本阶段不实施）
P0 / P1 / P2 …
```

## 4. 基线快照（记录一次，后续直接引用）

> 审计日期：2026-08-16（随重大架构/技术栈变更时更新本节，并同步 AGENTS.md §1 文档地图）

- **架构**：FastAPI 单体后端（`app/api|agent|storage|tasks|auth|llm|ingest|core` 分层，API 与任务 Worker 进程分离）+ Next.js 16 静态导出前端（zustand 状态 + 类型化事件总线 + 身份隔离缓存 API 层）+ Capacitor 7 Android 原生壳（WorkManager 相册同步）。详见 `docs/architecture.md`。
- **技术栈**：Python 3.10+（FastAPI/uvicorn/pytest/ruff/mypy）；TypeScript 5 + React 19 + Tailwind 4 + shadcn/ui（radix 底座，2026-08-16 引入，规范 docs/frontend-design.md）+ Vitest；Java 17 + AndroidX Security Crypto / WorkManager。
- **审计结论**：分层清晰、锁序与原子性约定完整、CI 覆盖三方门禁；无变更不重审。
