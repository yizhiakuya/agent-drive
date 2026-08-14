"""写工具测试：write_file/append_file/copy_file + Critic 验证。"""
import asyncio
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
try:  # Windows 控制台 GBK：强制 UTF-8 输出，避免 ✅/中文打印崩溃
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from app.agent.tools.files import register_file_tools
from app.agent.tools.registry import ToolRegistry
from app.storage.local import LocalStorage


async def main():
    tmp = Path(tempfile.mkdtemp())
    storage = LocalStorage(tmp / "data")
    reg = ToolRegistry()
    register_file_tools(reg, storage)

    print("=" * 55)
    print("测试 1: write_file 创建新文件 + Critic 读回验证")
    r = await reg.execute("write_file", {"path": "笔记/会议.md", "content": "# 会议纪要\n- 讨论上下文管理"})
    d = json.loads(r)
    assert storage.exists("笔记/会议.md"), "文件应创建"
    assert d["action"] == "新建"
    print(f"  ✅ 创建成功: {d}")

    print()
    print("=" * 55)
    print("测试 2: write_file 覆盖已有文件")
    r = await reg.execute("write_file", {"path": "笔记/会议.md", "content": "# 会议纪要 v2\n- 增加自动压缩"})
    d = json.loads(r)
    assert d["action"] == "覆盖"
    assert "v2" in storage.read_text("笔记/会议.md")
    print(f"  ✅ 覆盖成功: {d}")

    print()
    print("=" * 55)
    print("测试 3: append_file 追加")
    await reg.execute("append_file", {"path": "笔记/会议.md", "content": "\n- 追加：工具检索"})
    text = storage.read_text("笔记/会议.md")
    assert "工具检索" in text and "v2" in text
    print("  ✅ 追加成功（新内容+旧内容都在）")

    print()
    print("=" * 55)
    print("测试 4: copy_file 复制")
    r = await reg.execute("copy_file", {"src": "笔记/会议.md", "dst": "备份/会议副本.md"})
    d = json.loads(r)
    assert storage.exists("备份/会议副本.md")
    print(f"  ✅ 复制成功: {d}")

    print()
    print("=" * 55)
    print("测试 5: get_storage_info")
    r = await reg.execute("get_storage_info", {})
    d = json.loads(r)
    assert "disk" in d and "root_files" in d
    print(f"  ✅ 存储概览: {d['root_files']} 文件, {d['root_dirs']} 目录")

    print()
    print("=" * 55)
    print("测试 6: 写路径穿越防护")
    r = await reg.execute("write_file", {"path": "../../etc/evil.txt", "content": "hack"})
    d = json.loads(r)
    assert d.get("ok") is False and "越界" in d.get("error", "")
    print(f"  ✅ 穿越拦截: {d['error'][:40]}")


if __name__ == "__main__":
    asyncio.run(main())
print("\n🎉 写工具测试全部通过！")
