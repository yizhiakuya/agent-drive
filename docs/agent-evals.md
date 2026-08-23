# Agent Eval Suite

> 现行 Agent 行为门禁。该套件测试产品结果，而不是只测试 HTTP 状态或模型是否返回文本。

## 运行

```powershell
pwsh -File scripts/agent-eval.ps1
```

报告写入 `backend/target/agent-eval-dashboard.md`，并在命令行输出摘要。脚本只运行本地 mock/provider 测试，不发送用户数据或调用外部模型。

## 评估维度

| Case | 业务行为 | 通过标准 |
|------|----------|----------|
| `count-files` | 统计目录文件 | 命中 `GET /api/v1/files/stats`，不依赖逐目录累加 |
| `complete-statistics` | 统计完整性 | `file_count/folder_count/complete/snapshot_at` 可验证 |
| `long-run` | 长任务自主循环 | 默认 `max-chat-steps=0` 可超过旧 100 步，不产生截断消息 |
| `restart-recovery` | 进程重启 | running 变 interrupted；用户主动继续，不自动重放写操作 |
| `truthful-errors` | 失败真实性 | dispatcher 失败提升为外层 `ok=false/status/code/detail` |
| `permission` | 高风险操作 | ask/auto 等待确认，full 仍经过 owner/allowlist/schema |
| `context-compile` | 上下文相关性 | 简单只读请求不注入 USER/MEMORY，整理/规则任务注入完整个人上下文 |
| `activity` | 用户可理解过程 | Activity 使用业务标题，技术 operation 仍可展开审计 |
| `plan` | 多步任务可视化 | `plan` 工具返回完整步骤，流式和历史 UI 显示计划进度，不创建后台任务 |
| `tool-progress` | 长工具可观察性 | running 工具显示业务阶段和 elapsed；长调用通过 `tool_progress` 心跳更新，不伪造百分比 |

## 解读

测试通过只代表行为契约满足，不代表任意模型都能完成任意任务。线上还应持续记录：目标完成率、错误后恢复率、无效工具调用率、重复调用率、工具调用数、首 token 延迟、总运行时长和用户取消率。
