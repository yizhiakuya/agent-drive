"""长期记忆系统测试：Markdown 记忆层 + 检索 + 迁移兼容。"""
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from app.agent.memory.preferences import MemoryStore


def main():
    tmp = Path(tempfile.mkdtemp())
    store = MemoryStore(tmp / "memory.json")

    print("=" * 55)
    print("测试 1: 旧 JSON 迁移 → USER.md/MEMORY.md")
    # 造旧数据
    import json
    (tmp / "memory.json").write_text(json.dumps({
        "preferences": {"language": "zh", "organize_style": "按项目分类"},
        "rules": ["下载的文件自动归档"],
    }, ensure_ascii=False))
    store2 = MemoryStore(tmp / "memory.json")
    assert store2.user_md.exists() and store2.memory_md.exists()
    assert store2.get("language") == "zh"
    assert "按项目分类" in store2.user_md.read_text()
    assert not (tmp / "memory.json").exists(), "旧 JSON 应已清理"
    print("  ✅ 迁移成功: USER.md 含偏好, 旧 JSON 已删除")

    print()
    print("=" * 55)
    print("测试 2: set_preference 写回 USER.md（supersede 语义）")
    store2.set("language", "en")
    text = store2.user_md.read_text()
    assert "en" in text
    # 原地替换（不追加矛盾条目）
    assert text.count("## 语言") == 1
    print("  ✅ 偏好更新写入 USER.md（无重复条目）")

    print()
    print("=" * 55)
    print("测试 3: remember 记录持久事实到 MEMORY.md")
    r = store2.remember("用户毕业论文主题是量子计算")
    assert r["saved"]
    text = store2.memory_md.read_text()
    assert "量子计算" in text
    print(f"  ✅ 记录成功: {r['entry']}")

    print()
    print("=" * 55)
    print("测试 4: memory_search 全文检索")
    store2.daily_note("整理网盘文件：把预算报告移入文档/")
    results = store2.search_memory("量子计算")
    assert any("量子计算" in x["line"] for x in results)
    results2 = store2.search_memory("预算")
    assert any("预算" in x["line"] for x in results2), "应搜到每日笔记"
    print(f"  ✅ 检索成功: 长期记忆 {len(results)} 条, 笔记 {len(results2)} 条")

    print()
    print("=" * 55)
    print("测试 5: 每日笔记 + 昨日笔记提取")
    from datetime import date, timedelta
    yesterday = (date.today() - timedelta(days=1)).isoformat()
    note = store2.notes_dir / f"{yesterday}.md"
    note.write_text(f"# {yesterday}\n\n- 会话: 用户要求支持流式输出\n- 会话: 完成上下文压缩\n- 会话: 用户偏好深色改浅色\n")
    ynotes = store2.yesterday_notes()
    assert len(ynotes) >= 3
    assert store2.last_dream() == "" or store2.last_dream() < yesterday
    print(f"  ✅ 昨日笔记 {len(ynotes)} 行，可被 dreaming 巩固")

    print()
    print("=" * 55)
    print("测试 6: 记忆文件读取")
    content = store2.get_memory_file("MEMORY.md")
    assert "量子计算" in content
    missing = store2.get_memory_file("不存在.md")
    assert "不存在" in missing
    print("  ✅ memory_get 正常")


main()
print("\n🎉 长期记忆系统测试全部通过！")
