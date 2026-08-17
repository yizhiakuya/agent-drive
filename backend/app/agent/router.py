"""意图路由：闲聊 vs 任务 + 工具组检索。

- chat：单次 LLM 回复（无工具）
- task：完整 Agentic Loop；按意图选择工具子集（工具检索，省 token）
- 无法确定意图 → 全量工具（保守，保证能力完整）
"""
from __future__ import annotations

# 任务特征词 → (模式, 工具组)
FILE_KEYWORDS = (
    "文件", "文件夹", "目录", "网盘", "搜索", "查找", "找一下", "帮我找",
    "读一下", "看看", "查看", "列出", "有什么", "上传", "下载",
    "创建", "新建", "建一个", "删除", "删掉", "移除", "移动", "重命名",
    "整理", "归档", "分类",
    "周报", "总结", "汇总",
)
SYSTEM_KEYWORDS = (
    "配置", "设置", "模型", "llm", "规则", "偏好", "记住", "记一下",
    "审计", "失败", "诊断", "状态", "换一个",
)

# 通用任务动词（短句也视为任务，走全量工具）
TASK_VERBS = ("分析", "生成", "设计", "处理", "实现", "开发", "比较", "对比", "翻译")

CHAT_GREETINGS = ("你好", "嗨", "hi", "hello", "在吗", "早上好", "晚上好", "谢谢", "你是谁", "你能做什么")

# 意图 → 工具组（工具检索）
GROUPS_FILES = ["backend_api", "plan", "skills"]
GROUPS_SYSTEM = ["backend_api", "plan", "skills"]
GROUPS_ALL = None  # 全量


def classify(message: str) -> tuple[str, list[str] | None]:
    """返回 (mode, tool_groups)。mode: chat|task；groups: None=全量工具。

    修复 R1：问候词只在"纯问候"时触发——带任务关键词的句子绝不降级为 chat。
    原则：chat 误判 = 能力归零（后果严重）；task 误判只是多花 token（无害）。
    """
    text = message.strip()
    low = text.lower()
    has_task = (
        any(k in low for k in FILE_KEYWORDS)
        or any(k in low for k in SYSTEM_KEYWORDS)
        or any(v in low for v in TASK_VERBS)
    )
    if has_task:
        # 任务优先：即使以问候开头也走任务路径（"你好，帮我整理文件"）
        if any(k in low for k in FILE_KEYWORDS):
            return "task", GROUPS_FILES
        if any(k in low for k in SYSTEM_KEYWORDS):
            return "task", GROUPS_SYSTEM
        return "task", GROUPS_ALL
    # 纯问候才走 chat（无任务关键词）
    for g in CHAT_GREETINGS:
        if low.startswith(g) or g in low:
            return "chat", None
    if len(text) < 10:
        return "chat", None
    return "task", GROUPS_ALL
