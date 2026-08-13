"""意图路由：闲聊 vs 任务。

- chat：单次 LLM 回复（精简提示，无工具手册）→ 秒回省钱
- task：完整 Agentic Loop（工具 + 技能 + 记忆）
默认 task（保守：完整 loop 能处理一切，分类错误只是慢一点，不会错）。
"""
from __future__ import annotations

# 任务特征词（命中 → task）
TASK_KEYWORDS = (
    "文件", "文件夹", "目录", "网盘", "搜索", "查找", "找一下", "帮我找",
    "读一下", "看看", "查看", "列出", "有什么",
    "创建", "新建", "建一个", "删除", "删掉", "移除", "移动", "重命名",
    "整理", "归档", "分类", "上传", "下载",
    "周报", "总结", "汇总", "对比", "分析", "报告",
    "配置", "设置", "模型", "LLM", "规则", "偏好", "记住", "记一下",
    "审计", "失败", "诊断",
)

# 闲聊特征（问候/寒暄/无操作意图的短问句）
CHAT_GREETINGS = ("你好", "嗨", "hi", "hello", "在吗", "早上好", "晚上好", "谢谢", "你是谁", "你能做什么")


def classify(message: str) -> str:
    """返回 "chat" 或 "task"。"""
    text = message.strip()
    low = text.lower()
    # 明确问候 → chat
    for g in CHAT_GREETINGS:
        if low.startswith(g):
            return "chat"
    # 任务关键词 → task
    if any(k in low for k in TASK_KEYWORDS):
        return "task"
    # 短消息（<10 字）且无操作词 → chat
    if len(text) < 10:
        return "chat"
    # 默认 task
    return "task"
