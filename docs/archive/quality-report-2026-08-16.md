# 工程质量分析报告（2026-08-16）

> 历史快照，只读存档。按 docs/quality-analysis.md 协议执行：只读分析 → 报告 → 用户确认（"做吧"）→ 修复。

## 门禁结果（分析时点，本机实测全绿）

| 后端 | ruff / mypy（68 文件 0 错误）/ pytest unit 78 / integration 23 / 8 遗留脚本 | ✅ |
|------|------|------|
| 前端 | eslint / vitest 6 文件 39 测试 / next build | ✅ |
| Android | gradle testDebugUnitTest | ✅ |

## 分析发现（摘要，详见各子代理报告）

- 后端：storage/base.py Storage 协议死抽象零引用；devices/registry.py 原子写缺 fsync/0600；loop.py 双重 try/except 死代码与冗余局部导入；tasks/__init__ 未用重导出；scheduler/skills/providers/chat API 无测试。
- 前端：page.tsx 三处硬编码事件名绕过 EV 常量；文件预览六分支在 FilePanel/FilePage 重复；时间格式化三处内联 toLocaleString；RescanCard/ConnectAppCard 重扫重复；lib/api 多个未用导出；FilePage/ChatPanel 主流程无测试。
- Android：SyncEngine skips 死计数器；异常构造参数废料；catch 命名不统一；sync() 主流程/断网中止/4xx 映射/同秒续传无测试。
- 文档：铁律 §0 同提交同步 8/8 合规；但 README dev 端口 3000/3333 矛盾 + CORS 缺 :3333；测试口径 "13 套" 失真（实为 15）；CI Python 3.11 与生产 3.10 不符；gradlew 可执行位未入库。

## 修复内容（同一次提交，文档同步）

- 后端：devices/registry.py _save 对齐 0600+fsync+原子 replace+失败清理（+单测）；loop.py 删双重 try 与冗余导入；删 Storage 协议死抽象；删 tasks 未用重导出；CORS 增加 localhost:3333。
- 前端：page.tsx 改用 EV.toast；删 11 个未引用导出并去重 isNativePlatform；抽 FilePreview（panel/page variant）收敛六分支预览；lib/format 增 fmtTime 收敛时间格式化；抽 useRescan 收敛重扫逻辑；+format.test.ts(5)、FilePreview.test.tsx(7)。
- Android：SyncEngine 删 skips 死计数器；catch 变量命名统一；+1 纯逻辑 CheckpointTracker 用例。
- 门禁/文档：CI Python 3.11→3.10；gradlew 可执行位入库（100755）；README 端口/测试口径/项目结构修正；AGENTS.md 新增设备注册表原子写规范、前端复用单元约定、CI 3.10 与 gradlew 说明；docs/architecture.md 存储抽象段落改为现状描述。

## 遗留（P1/P2 待排期，需先补测试网）

- ChatPanel 抽 useChatStream / SyncEngine.sync() 拆方法（重构前先补核心测试）。
- FilePage/ChatPanel 主流程、SyncEngine 检查点集成与 4xx 映射、backend scheduler/skills/providers 测试缺口。
- FilePanel 单击进目录 vs FilePage 单击选中 的交互分叉（属产品决策，未擅动）。
- Android 注释语言中英混用统一。
