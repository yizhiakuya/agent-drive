"""技能包系统（Anthropic Agent Skills 模式）。

Skill = skills/<name>/SKILL.md（frontmatter: name/description/triggers + 正文指令）

- 索引注入系统提示（轻量：名称+一句话+触发词）
- read_skill 工具按需加载完整指令（拆分上下文原则）
- 用户/Agent 可新增技能目录实现"能力自进化"
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Skill:
    name: str
    description: str
    triggers: list[str] = field(default_factory=list)
    path: Path | None = None

    def index_line(self) -> str:
        trig = "、".join(self.triggers) if self.triggers else "随时可用"
        return f"- **{self.name}**（触发词: {trig}）{self.description}"

    def full_text(self) -> str:
        if self.path is None or not self.path.exists():
            return f"(技能内容缺失: {self.name})"
        return self.path.read_text(encoding="utf-8")


class SkillsRegistry:
    def __init__(self, skills_dir: Path | str):
        self.dir = Path(skills_dir)
        self._skills: dict[str, Skill] = {}
        self.reload()

    def reload(self) -> None:
        self._skills = {}
        if not self.dir.exists():
            return
        for d in sorted(self.dir.iterdir()):
            if not d.is_dir():
                continue
            skill_md = d / "SKILL.md"
            if not skill_md.exists():
                continue
            skill = self._parse(skill_md)
            if skill:
                self._skills[skill.name] = skill

    @staticmethod
    def _parse(path: Path) -> Skill | None:
        try:
            text = path.read_text(encoding="utf-8")
            m = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
            if not m:
                return None
            meta = {}
            for line in m.group(1).splitlines():
                if ":" in line:
                    k, v = line.split(":", 1)
                    meta[k.strip()] = v.strip()
            name = meta.get("name", path.parent.name)
            triggers = [t.strip() for t in meta.get("triggers", "").split(",") if t.strip()]
            return Skill(
                name=name,
                description=meta.get("description", ""),
                triggers=triggers,
                path=path,
            )
        except Exception:
            return None

    def list(self) -> list[Skill]:
        return list(self._skills.values())

    def get(self, name: str) -> Skill | None:
        return self._skills.get(name)

    def index(self) -> str:
        """技能索引（注入系统提示）"""
        if not self._skills:
            return "(暂无技能)"
        return "\n".join(s.index_line() for s in self._skills.values())
