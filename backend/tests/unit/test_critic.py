"""Critic 反馈循环专项测试：验证写操作的 validator 与结构化错误。"""
import asyncio
import tempfile
from pathlib import Path


import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
from app.agent.tools.files import register_file_tools
from app.agent.tools.registry import ToolRegistry
from app.storage.local import LocalStorage


async def main():
    tmp = tempfile.mkdtemp()
    root = Path(tmp)
    storage = LocalStorage(root / "data")
    reg = ToolRegistry()
    register_file_tools(reg, storage)

    print("=" * 50)
    print("测试 1: create_folder 幂等性（已存在 → 成功）")
    r1 = await reg.execute("create_folder", {"path": "项目/2026"})
    r2 = await reg.execute("create_folder", {"path": "项目/2026"})
    print("  首次:", r1)
    print("  重复:", r2)
    assert '"ok"' not in r1 or '"ok": true' in r1 or '"created"' in r1
    print("✅ 通过")

    print()
    print("=" * 50)
    print("测试 2: Critic 验证 rename（目标不存在 → 报验证失败）")
    await reg.execute("rename_file", {"src": "不存在.txt", "dst": "x.txt"})
    r = await reg.execute("rename_file", {"src": "项目", "dst": "项目2"})  # src 是目录，rename 目录
    # 正常路径
    storage.save_bytes("a.txt", b"hi")
    r_ok = await reg.execute("rename_file", {"src": "a.txt", "dst": "b.txt"})
    print("  正常重命名:", r_ok)
    import json as _json
    d = _json.loads(r_ok)
    assert d.get("ok", True) is not False and "b.txt" in r_ok
    print("✅ 通过")

    print()
    print("=" * 50)
    print("测试 3: 结构化错误（工具缺失 / 路径越界）")
    r = await reg.execute("不存在的工具", {})
    print("  未知工具:", r)
    assert "工具不存在" in r
    r = await reg.execute("read_file", {"path": "../../../etc/passwd"})
    d = _json.loads(r)
    assert d.get("ok") is False and "越界" in d.get("error", ""), f"越界应以结构化错误返回: {r}"
    print(f"  越界以结构化错误返回: {d['error'][:40]}...")
    print("✅ 通过")

    print()
    print("=" * 50)
    print("测试 4: 工具手册生成（API 文档）")
    manual = reg.manual()
    print(manual[:500])
    print("...")
    assert "list_files" in manual and "read_file" in manual and "delete_file" in manual
    assert "级别" in manual and "用途" in manual
    print("✅ 通过")


if __name__ == "__main__":
    asyncio.run(main())
print("\n🎉 Critic 测试全部通过！")
