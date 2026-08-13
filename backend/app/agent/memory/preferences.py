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


class MemoryStore:
    def __init__(self, path: Path | str):
        """path: 原 memory.json 路径（兼容），同目录放 USER.md/MEMORY.md/memory/"""
        self.path = Path(path)
        self.dir = self.path.parent
        self.user_md = self.dir / "USER.md"
        self.memory_md = self.dir / "MEMORY.md"
        self.notes_dir = self.dir / "memory"
        self.dream_marker = self.dir / ".last_dream"
        self._data = {"preferences": {}, "rules": []}

        # 旧 JSON 迁移
        if self.path.exists():
            try:
                self._data.update(json.loads(self.path.read_text()))
            except Exception:
                pass
        had_json = self.path.exists()
        self._load_user_md()
        self._ensure_files()
        if had_json:
            # 旧 JSON 是权威数据 → 迁移后重写 USER.md
            self._write_user_md()

    # ---------- 文件初始化与迁移 ----------
    def _ensure_files(self) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        self.notes_dir.mkdir(parents=True, exist_ok=True)
        if not self.user_md.exists():
            self._write_user_md()
        if not self.memory_md.exists():
            self.memory_md.write_text("# 长期记忆\n\n(Agent 用 remember 工具记录持久事实与决策)\n")
        # 旧 JSON 已迁移则清理
        if self.path.exists() and self.user_md.exists():
            try:
                self.path.unlink()
            except OSError:
                pass

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
        # JSON 迁移值优先，USER.md 只补充缺失项
        for key, val in prefs.items():
            self._data["preferences"].setdefault(key, val)
        if rules and not self._data["rules"]:
            self._data["rules"] = rules

    def _write_user_md(self) -> None:
        """把 preferences/rules 写回 USER.md（指令式格式，带日期）"""
        today = date.today().isoformat()
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
        lines.append("")
        self.user_md.write_text("\n".join(lines))

    # ---------- 兼容旧 API ----------
    def set(self, key: str, value: str) -> None:
        self._data["preferences"][key] = value
        self._write_user_md()

    def get(self, key: str, default: str = "") -> str:
        return self._data["preferences"].get(key, default)

    def all(self) -> dict[str, str]:
        return dict(self._data["preferences"])

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

    def memory_text(self, max_chars: int = 3000) -> str:
        """读取 MEMORY.md 全文（截断）"""
        if not self.memory_md.exists():
            return ""
        return self.memory_md.read_text()[:max_chars]

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
