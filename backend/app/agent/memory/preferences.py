"""记忆系统 L2：OpenClaw 式 Markdown 长期记忆。

v2 重构：
- USER.md   用户模型（偏好/规则，指令式，可 supersede）——全量注入
- MEMORY.md 策划层（持久事实/决策/摘要，紧凑）——全量注入（超预算截断）
- memory/YYYY-MM-DD.md 工作层每日笔记——不注入，memory_search 检索
- 兼容旧 memory.json（自动迁移，保留原 API：all/set/get/add_rule/...）
"""
from __future__ import annotations

import json
import re
from datetime import date
from pathlib import Path
from typing import Any


class MemoryStore:
    def __init__(self, path: Path | str, *, migrate_from: Path | str | None = None):
        """path: 工作空间目录（网盘内 Agent/ 目录）。

        记忆文件直接存在于网盘文件空间（用户可见可编辑）：
          Agent/USER.md     用户模型（角色/偏好/规则）
          Agent/MEMORY.md   长期记忆（持久事实/决策）
          Agent/notes/      每日笔记（工作层）
        migrate_from: 旧记忆目录（system/），初始化时自动迁移。
        """
        self.dir = Path(path)
        self.user_md = self.dir / "USER.md"
        self.memory_md = self.dir / "MEMORY.md"
        self.agent_md = self.dir / "AGENT.md"
        self.notes_dir = self.dir / "notes"
        self.dream_marker = self.dir / ".last_dream"
        self._data: dict[str, Any] = {"preferences": {}, "rules": []}

        # 旧位置迁移（system/ → Agent/）
        if migrate_from is not None:
            self._migrate_from(Path(migrate_from))
        self._load_user_md()
        self._ensure_files()

    def _migrate_from(self, old_dir: Path) -> None:
        """把旧记忆（system/ 下的 memory.json/USER.md/MEMORY.md/notes）迁移进工作空间。"""
        had_json = False
        old_json = old_dir / "memory.json"
        if old_json.exists():
            try:
                self._data.update(json.loads(old_json.read_text()))
                had_json = True
            except Exception:
                pass
        self.dir.mkdir(parents=True, exist_ok=True)
        # 旧 md 文件：目标不存在→复制；目标还是模板→替换；目标已有内容→合并
        for name in ("USER.md", "MEMORY.md", "AGENT.md"):
            old_f = old_dir / name
            if not old_f.exists():
                continue
            old_text = old_f.read_text()
            target = self.dir / name
            if target.exists():
                cur = target.read_text()
                if len(cur.strip()) > 120:  # 目标已有实质内容 → 合并
                    target.write_text(cur + "\n\n<!-- 迁移自旧记忆 -->\n" + old_text)
                else:  # 目标还是初始模板 → 替换
                    target.write_text(old_text)
            else:
                target.write_text(old_text)
        # 兼容旧笔记目录名（v2 用 "memory"，v3 起用 "notes"）
        for old_notes in (old_dir / "notes", old_dir / "memory"):
            if not old_notes.is_dir():
                continue
            for f in old_notes.glob("*.md"):
                target = self.notes_dir / f.name
                if not target.exists():
                    try:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_text(f.read_text())
                    except OSError:
                        pass
            if old_notes.name == "memory":
                try:
                    import shutil
                    shutil.rmtree(old_notes)
                except OSError:
                    pass
        # JSON 是权威数据 → 重写 USER.md
        if had_json:
            self._write_user_md()
        # 迁移后清理旧文件
        for f in (old_json, old_dir / "USER.md", old_dir / "MEMORY.md"):
            try:
                if f.exists():
                    f.unlink()
            except OSError:
                pass

    # ---------- 文件初始化与迁移 ----------
    def _ensure_files(self) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        self.notes_dir.mkdir(parents=True, exist_ok=True)
        if not self.user_md.exists():
            self._write_user_md()
        if not self.memory_md.exists():
            self.memory_md.write_text("# 长期记忆\n\n(Agent 用 remember 工具记录持久事实与决策)\n")
        if not self.agent_md.exists():
            self.agent_md.write_text(
                "# Agent 角色定义\n\n"
                "你是「Agent Drive」的主 Agent（File Concierge）—— 一个以 AI 为中心的私人网盘的管家。\n\n"
                "## 职责\n"
                "- 管理用户的所有文件：理解、组织、关联、创建\n"
                "- 管理自己的配置与记忆\n"
                "- 用用户偏好的语言沟通\n\n"
                "> 用户可以编辑本文件自定义 Agent 的角色与职责。\n"
            )

    # ---------- USER.md 读写 ----------
    def _user_sections(self) -> dict[str, list[tuple[str, str]]]:
        """解析 USER.md: section → [(line, date)]"""
        sections: dict[str, list[tuple[str, str]]] = {}
        current = None
        if not self.user_md.exists():
            return sections
        for line in self.user_md.read_text().splitlines():
            m = re.match(r"^## (.+)", line)
            if m:
                current = m.group(1).strip()
                sections.setdefault(current, [])
            elif current and line.startswith("- "):
                entry = line[2:].strip()
                # 提取尾部日期 "(YYYY-MM-DD)"
                dm = re.search(r"\((20\d\d-\d\d-\d\d)\)\s*$", entry)
                d = dm.group(1) if dm else ""
                text = re.sub(r"\(20\d\d-\d\d-\d\d\)\s*$", "", entry).strip()
                sections[current].append((text, d))
        return sections

    def _load_user_md(self) -> None:
        """从 USER.md 恢复 preferences/rules（兼容旧 API）"""
        sections = self._user_sections()
        # 偏好段映射回 _data
        prefs = {}
        rules = []
        for section, entries in sections.items():
            for text, d in entries:
                if section == "规则":
                    rules.append(text)
                else:
                    key_map = {
                        "语言": "language",
                        "整理偏好": "organize_style",
                        "命名规则": "naming_rule",
                    }
                    key = key_map.get(section)
                    if key:
                        prefs[key] = text
                    else:
                        prefs[section] = text
        # 文件为权威：用户手改 USER.md 的内容直接生效
        self._data["preferences"].update(prefs)
        if rules:
            self._data["rules"] = rules

    def _write_user_md(self) -> None:
        """把 preferences/rules 写回 USER.md。

        修复（memory-review #2）：保留用户手写的未知 section（合并而非重建），
        避免 set() 静默销毁用户编辑的内容。
        """
        today = date.today().isoformat()
        # 先解析当前磁盘上的 USER.md，保留未知 section（用户手写）
        existing = self._user_sections()
        known = {"语言", "整理偏好", "命名规则", "规则"}

        lines = [
            "# 用户模型",
            "",
            "> 稳定偏好与规则（指令式）。变更时原地替换，不追加矛盾条目。",
            "",
            "## 语言",
            f"- {self._data['preferences'].get('language', '中文')} ({today})",
            "",
            "## 整理偏好",
        ]
        if self._data["preferences"].get("organize_style"):
            lines.append(f"- {self._data['preferences']['organize_style']} ({today})")
        lines += ["", "## 命名规则"]
        if self._data["preferences"].get("naming_rule"):
            lines.append(f"- {self._data['preferences']['naming_rule']} ({today})")
        lines += ["", "## 规则"]
        lines += [f"- {r}" for r in self._data.get("rules", [])] or ["- (暂无)"]

        # 保留用户手写的未知 section
        for section, entries in existing.items():
            if section in known:
                continue
            lines += ["", f"## {section}"]
            for text, d in entries:
                suffix = f" ({d})" if d else ""
                lines.append(f"- {text}{suffix}")

        lines.append("")
        self.user_md.write_text("\n".join(lines))

    # ---------- 兼容旧 API ----------
    def set(self, key: str, value: str) -> None:
        # 写前刷新磁盘状态：用户运行期手改 USER.md 不被陈旧数据覆盖
        self._load_user_md()
        self._data["preferences"][key] = value
        self._write_user_md()

    def get(self, key: str, default: str = "") -> str:
        return self._data["preferences"].get(key, default)

    def all(self) -> dict[str, str]:
        return dict(self._data["preferences"])

    def rules(self) -> list[str]:
        return list(self._data["rules"])

    def add_rule(self, rule: str) -> None:
        self._data["rules"].append(rule)
        self._write_user_md()

    def remove_rule(self, index: int) -> bool:
        try:
            self._data["rules"].pop(index)
            self._write_user_md()
            return True
        except IndexError:
            return False

    def list_rules(self) -> list[str]:
        return list(self._data["rules"])

    # ---------- MEMORY.md（策划层） ----------
    def remember(self, content: str) -> dict:
        """Agent 记录持久事实/决策（追加到 MEMORY.md，带日期）"""
        today = date.today().isoformat()
        entry = f"\n## {today}\n- {content.strip()}\n"
        with open(self.memory_md, "a") as f:
            f.write(entry)
        return {"saved": True, "memory": self.memory_md.name, "entry": content.strip()}

    def agent_role(self, max_chars: int = 1500) -> str:
        """Agent 角色定义（AGENT.md，用户可编辑自定义人设）"""
        if not self.agent_md.exists():
            return ""
        return self.agent_md.read_text()[:max_chars]

    def memory_text(self, max_chars: int = 3000) -> str:
        """读取 MEMORY.md（修复：从尾部截断，保留最新记忆条目）。

        新记忆用 remember() 追加在文件尾部，从头部截断会丢掉最新条目。
        头部引导语较短，从尾部取可同时保留标题与最近内容。
        """
        if not self.memory_md.exists():
            return ""
        text = self.memory_md.read_text()
        if len(text) <= max_chars:
            return text
        return "...（更早的记忆已省略）...\n" + text[-max_chars:]

    def search_memory(self, query: str, limit: int = 10) -> list[dict]:
        """全文搜索 MEMORY.md + memory/*.md 每日笔记"""
        results = []
        files = [self.memory_md] + sorted(self.notes_dir.glob("*.md"))
        for f in files:
            if not f.exists():
                continue
            for line in f.read_text().splitlines():
                if query.lower() in line.lower() and line.strip():
                    results.append({"file": f.name, "line": line.strip()[:200]})
                    if len(results) >= limit:
                        return results
        return results

    def get_memory_file(self, name: str, max_chars: int = 4000) -> str:
        """读具体记忆文件（MEMORY.md 或 memory/xxx.md）"""
        candidates = [self.memory_md] + list(self.notes_dir.glob("*.md"))
        for f in candidates:
            if f.name == name:
                return f.read_text()[:max_chars]
        return f"(记忆文件不存在: {name})"

    # ---------- 每日笔记（工作层） ----------
    def daily_note(self, line: str) -> None:
        """追加一行到今日笔记（会话活动记录）"""
        today = date.today().isoformat()
        note = self.notes_dir / f"{today}.md"
        if not note.exists():
            note.write_text(f"# {today}\n\n")
        with open(note, "a") as f:
            f.write(f"- {line}\n")

    def yesterday_notes(self) -> list[str]:
        """昨天笔记内容（dreaming 巩固用）"""
        from datetime import timedelta
        y = (date.today() - timedelta(days=1)).isoformat()
        note = self.notes_dir / f"{y}.md"
        if note.exists():
            return note.read_text().splitlines()
        return []

    def last_dream(self) -> str:
        """最近一次 dreaming 巩固日期（独立标记文件，不受 MEMORY.md 干扰）"""
        if self.dream_marker.exists():
            return self.dream_marker.read_text().strip()
        return ""

    def mark_dreamed(self, day: str) -> None:
        self.dream_marker.write_text(day)
