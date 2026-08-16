"""流式工具调用标记清洗（DSML/XML 泄露防护）。

背景：DSH 训练的模型偶尔不在 function calling 里调工具，而是把
<|DSML|tool_calls>… 之类的标记直接写进回复正文（常见于模型想调一个
不在 schema 里的工具，如 list_files/search_files）。后端不识别 DSML，
原文会流到前端气泡里。本模块做流式安全的剔除：

- 整行独占的标记（常见形态，每个 tag 一行）→ 直接删
- 内联配对块（<|DSML|tool_calls>…</|DSML|tool_calls> 等）→ 整块删
- 疑似块开头先扣下（流式期间标记可能跨 chunk），流结束 flush 放行

只删"模拟工具调用"的结构化标记，普通文本不受影响（"a < b"、"<3"、
分享 HTML 片段等原样保留）。
"""
from __future__ import annotations

import re

# 整行独占的标记行：DSML 任意标签名 + 常见 XML 工具调用族。
# 一行可含多个 tag（如 <|DSML|parameter ...></|DSML|parameter>），用 + 重复匹配
_TAG_LINE = re.compile(
    r"(?m)^[ \t]*((?:</?\|?DSML\|?[a-zA-Z_]+[^>]*/?>"
    r"|</?(?:tool_calls|invoke|parameter|result|function_calls|function_results)[^>]*/?>)"
    r"[ \t]*)+[ \t]*(?:\r?\n|\Z)"  # \Z：流末尾无换行的标记行也要删
)

# 内联配对块（贪婪：外层闭合在最后，嵌套内容一起删除）
_PAIRED_BLOCK = re.compile(
    r"<\|?DSML\|?tool_calls>[\s\S]*</\|?DSML\|?tool_calls>"
    r"|<tool_calls>[\s\S]*</tool_calls>"
    r"|<function_calls>[\s\S]*</function_calls>"
)

# 疑似标记行起点：行首 < 且后随 |、/ 或字母（用于流式扣留判断）。
# 注意 \Z：chunk 恰好以单独的 '<' 结尾时也必须扣留（否则后续 'tool_calls>' 泄漏）
_TAG_START = re.compile(r"(?m)^[ \t]*<(?=[/|]?[a-zA-Z_]|\Z)")


def sanitize_tool_markup(text: str) -> str:
    """一次性清洗完整文本（非流式路径用）。"""
    while True:
        new, n = _TAG_LINE.subn("", text)
        if n == 0:
            break
        text = new
    while True:
        new, n = _PAIRED_BLOCK.subn("", text)
        if n == 0:
            break
        text = new
    return text


class ToolMarkupStripper:
    """流式清洗：feed() 喂 chunk 返回可安全输出部分；流结束必须 flush()。"""

    MAX_HOLD = 2048  # 疑似块开头最多扣留字节数，超限放弃识别（防吞正常文本）

    def __init__(self) -> None:
        self._pending = ""

    def feed(self, chunk: str) -> str:
        self._pending += chunk
        return self._drain(final=False)

    def flush(self) -> str:
        out = self._drain(final=True)
        out += self._pending  # flush 时残留（非标记）全部放行
        self._pending = ""
        return out

    def _drain(self, final: bool) -> str:
        # 1) 整行标记直接删（反复直到稳定）
        while True:
            new, n = _TAG_LINE.subn("", self._pending)
            if n == 0:
                break
            self._pending = new
        # 2) 内联配对块兜底
        while True:
            new, n = _PAIRED_BLOCK.subn("", self._pending)
            if n == 0:
                break
            self._pending = new
        cut = self._safe_cut(final)
        out = self._pending[:cut]
        self._pending = self._pending[cut:]
        return out

    def _safe_cut(self, final: bool) -> int:
        if final:
            return len(self._pending)
        starts = [m.start() for m in _TAG_START.finditer(self._pending)]
        # chunk 边界可能把标签从 '<' / '<|' 后切断：单独 '<' 或 '<|' 落在行尾时，
        # lookahead（< 后须跟字母或 |）匹配不到，不扣留会把 '<' 提前放行——
        # 后续 chunk 的 'tool_calls>' 无法再组成标签，标记原文泄漏
        # （生产实测：会话正文出现 <tool_calls> 原文；fuzz 半数分块方式可触发）
        if self._pending.endswith("<|") or self._pending.endswith("</"):
            starts.append(len(self._pending) - 2)
        elif self._pending.endswith("<"):
            starts.append(len(self._pending) - 1)
        if not starts:
            return len(self._pending)
        cut = starts[-1]
        if len(self._pending) - cut > self.MAX_HOLD:
            return len(self._pending)  # 扣留超限：放弃识别，全部放行
        return cut
