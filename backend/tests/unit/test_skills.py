"""skills.py 测试：SKILL.md frontmatter 解析、重载、索引、未知技能错误、规则上限（系统工具）。"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.agent.skills import Skill, SkillsRegistry


def _write_skill(skills_dir: Path, name: str, frontmatter: str, body: str = "正文") -> Path:
    d = skills_dir / name
    d.mkdir(parents=True, exist_ok=True)
    md = d / "SKILL.md"
    md.write_text(frontmatter + "\n" + body + "\n", encoding="utf-8")
    return md


FOO = "---\nname: foo\ndescription: 处理 foo 任务\ntriggers: foo, 处理, bar\n---"


def test_parse_extracts_frontmatter():
    skills_dir = Path("/tmp/nonexistent-skills")
    md = Path("/tmp/fake.md")  # 仅做静态 parse，不进真实注册表
    # 直接写一个临时文件交给 _parse
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "foo" / "SKILL.md"
        p.parent.mkdir()
        p.write_text(FOO + "\n\n这是 foo 的正文\n", encoding="utf-8")
        skill = SkillsRegistry._parse(p)
    assert skill is not None
    assert skill.name == "foo"
    assert skill.description == "处理 foo 任务"
    assert skill.triggers == ["foo", "处理", "bar"]


def test_parse_name_falls_back_to_dirname():
    body = "---\ndescription: 无 name 字段\ntriggers: x, y\n---\n正文"
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "skill-dir" / "SKILL.md"
        p.parent.mkdir()
        p.write_text(body, encoding="utf-8")
        skill = SkillsRegistry._parse(p)
    assert skill is not None
    assert skill.name == "skill-dir"


def test_parse_without_frontmatter_returns_none():
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "bad" / "SKILL.md"
        p.parent.mkdir()
        p.write_text("没有 frontmatter\n", encoding="utf-8")
        assert SkillsRegistry._parse(p) is None


def test_reload_ignores_dirs_without_skill_md(tmp_path):
    (tmp_path / "no-md-dir").mkdir()
    reg = SkillsRegistry(tmp_path)
    assert reg.list() == []


def test_list_get_and_index(tmp_path):
    _write_skill(tmp_path, "alpha", "---\nname: alpha\ndescription: A 技能\ntriggers: a\n---")
    _write_skill(tmp_path, "beta", "---\nname: beta\ndescription: B 技能\n---")
    reg = SkillsRegistry(tmp_path)
    names = [s.name for s in reg.list()]
    assert names == ["alpha", "beta"]

    assert reg.get("alpha") is not None
    assert reg.get("missing") is None

    index = reg.index()
    assert "**alpha**" in index
    assert "**beta**" in index


def test_index_empty():
    reg = SkillsRegistry(Path("/tmp/nonexistent-dir-xyz"))
    assert reg.index() == "(暂无技能)"


def test_full_text_missing_skill(tmp_path):
    _write_skill(tmp_path, "with-body", "---\nname: with-body\ndescription: d\n---", body="完整指令正文")
    reg = SkillsRegistry(tmp_path)
    assert "完整指令正文" in reg.get("with-body").full_text()


def test_full_text_when_file_deleted(tmp_path):
    _write_skill(tmp_path, "gone", "---\nname: gone\ndescription: d\n---")
    reg = SkillsRegistry(tmp_path)
    (tmp_path / "gone" / "SKILL.md").unlink()
    text = reg.get("gone").full_text()
    assert "技能内容缺失" in text
