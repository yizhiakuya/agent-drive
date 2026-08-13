"""记忆系统 L2：用户偏好 + 自动化规则（持久化为 JSON 文件）。

L1 会话上下文在 Agent 循环内；L3 文件理解层在 M2 引入（向量库）。
"""
from __future__ import annotations

import json
from pathlib import Path


class MemoryStore:
    def __init__(self, path: Path | str):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._data = {"preferences": {}, "rules": []}
        if self.path.exists():
            try:
                self._data.update(json.loads(self.path.read_text()))
            except Exception:
                pass

    def _persist(self) -> None:
        self.path.write_text(json.dumps(self._data, indent=2, ensure_ascii=False))

    # ---- 偏好 ----
    def set(self, key: str, value: str) -> None:
        self._data["preferences"][key] = value
        self._persist()

    def get(self, key: str, default: str = "") -> str:
        return self._data["preferences"].get(key, default)

    def all(self) -> dict[str, str]:
        return dict(self._data["preferences"])

    # ---- 规则 ----
    def add_rule(self, rule: str) -> None:
        self._data["rules"].append(rule)
        self._persist()

    def remove_rule(self, index: int) -> bool:
        try:
            self._data["rules"].pop(index)
            self._persist()
            return True
        except IndexError:
            return False

    def list_rules(self) -> list[str]:
        return list(self._data["rules"])
