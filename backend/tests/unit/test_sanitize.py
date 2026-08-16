"""工具调用标记清洗（DSML/XML 泄露防护）单测。"""
from __future__ import annotations

from app.agent.sanitize import ToolMarkupStripper, sanitize_tool_markup

LEAKED = """我已经收到你提供的 JinaAI 密钥（已注意保密，不会外发回应全文）。

先看下根目录有没有可直接编辑的配置文件：

<|DSML|tool_calls>
<|DSML|invoke name="list_files">
<|DSML|parameter name="path" string="true"></|DSML|parameter>
</|DSML|invoke>
</|DSML|tool_calls>
"""

CLEAN = """我已经收到你提供的 JinaAI 密钥（已注意保密，不会外发回应全文）。

先看下根目录有没有可直接编辑的配置文件：

"""


def test_sanitize_removes_line_based_dsml_block():
    out = sanitize_tool_markup(LEAKED)
    assert "DSML" not in out
    assert "list_files" not in out
    assert "先看下根目录" in out
    assert out == CLEAN


def test_sanitize_removes_xml_tool_calls():
    text = "结论如下：\n<tool_calls>\n<invoke name=\"x\"/>\n</tool_calls>\n结束"
    out = sanitize_tool_markup(text)
    assert out == "结论如下：\n结束"


def test_sanitize_removes_inline_paired_block():
    text = "正文 <|DSML|tool_calls><|DSML|invoke name=\"x\"/></|DSML|tool_calls> 结尾"
    out = sanitize_tool_markup(text)
    assert out == "正文  结尾"


def test_sanitize_keeps_plain_angle_brackets():
    text = "a < b，且 <3，还有 <div>普通 HTML</div> 片段"
    assert sanitize_tool_markup(text) == text


def test_streaming_splits_across_chunks():
    s = ToolMarkupStripper()
    out = ""
    for chunk in ["我已经收到", "密钥。\n\n<|DSML|tool_", "calls>\n<|DSML|invoke name=\"", "list_files\">\n<|DSML|parameter name=\"path\" string=\"true\"></|DSML|parameter>\n</|DSML|invoke>\n</|DSML|tool_calls>\n收尾"]:
        out += s.feed(chunk)
    out += s.flush()
    assert "DSML" not in out
    assert out == "我已经收到密钥。\n\n收尾"


def test_streaming_holds_incomplete_tag_line_until_flush():
    s = ToolMarkupStripper()
    out = s.feed("前半段。\n<|DSML|tool_")  # 疑似标记行未闭合 → 扣留
    assert "DSML" not in out
    out += s.feed("calls>\n<|DSML|invoke name=\"x\"/>\n</|DSML|tool_calls>")
    out += s.flush()
    assert out == "前半段。\n"


def test_streaming_keeps_partial_tag_then_normal_text():
    """扣留的疑似标记行最终证明是正常文本 → flush 时原样放行。"""
    s = ToolMarkupStripper()
    out = s.feed("正常文本\n<|DSML|to")  # 疑似标记开头 → 流式期间先扣着
    assert "DSML" not in out
    out += s.feed(" 不是标记，只是比较字符串")
    out += s.flush()
    assert out == "正常文本\n<|DSML|to 不是标记，只是比较字符串"


def test_streaming_releases_non_tag_angle_text_immediately():
    """< 后是中文/非标签字符 → 不是标记，立即放行。"""
    s = ToolMarkupStripper()
    out = s.feed("正常文本\n<我还没写完")
    assert out == "正常文本\n<我还没写完"
    assert s.flush() == ""


def test_max_hold_releases_oversized_pending():
    s = ToolMarkupStripper()
    out = s.feed("<" + "长" * 3000)  # 疑似标记开头但超长（无换行）→ 放弃识别
    assert out == "<" + "长" * 3000
    assert s.flush() == ""
