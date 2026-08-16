"""多会话存储：L1 会话记忆持久化 + 跨会话摘要。

目录结构:
  system/sessions/
    {id}.jsonl           # 消息流（user/assistant/tool_trace）
    {id}.meta.json       # 元数据（标题/摘要/创建时间/消息数）
"""
from __future__ import annotations

import builtins
import json
import time
import uuid
from pathlib import Path
from typing import Any


class SessionStore:
    def __init__(self, dir_path: Path | str):
        self.dir = Path(dir_path)
        self.dir.mkdir(parents=True, exist_ok=True)

    # ---------- 会话管理 ----------
    def create(self) -> dict[str, Any]:
        sid = uuid.uuid4().hex[:12]
        meta = {
            "id": sid,
            "title": "新会话",
            "summary": "",
            "created_at": time.time(),
            "updated_at": time.time(),
            "message_count": 0,
        }
        (self.dir / f"{sid}.meta.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
        (self.dir / f"{sid}.jsonl").write_text("", encoding="utf-8")
        return meta

    def list(self) -> builtins.list[dict[str, Any]]:
        sessions = []
        for f in self.dir.glob("*.meta.json"):
            try:
                meta = json.loads(f.read_text(encoding="utf-8"))
                sessions.append(meta)
            except Exception:
                continue
        sessions.sort(key=lambda m: m.get("updated_at", 0), reverse=True)
        return sessions

    def get(self, sid: str) -> dict[str, Any] | None:
        p = self.dir / f"{sid}.meta.json"
        if not p.exists():
            return None
        return json.loads(p.read_text(encoding="utf-8"))

    def delete(self, sid: str) -> bool:
        ok = True
        for suffix in (".meta.json", ".jsonl"):
            p = self.dir / f"{sid}{suffix}"
            if p.exists():
                p.unlink()
            else:
                ok = False
        return ok

    # ---------- 消息追加 ----------
    def append(self, sid: str, entry: dict[str, Any]) -> None:
        p = self.dir / f"{sid}.jsonl"
        with open(p, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        meta = self.get(sid)
        if meta:
            meta["message_count"] = meta.get("message_count", 0) + 1
            meta["updated_at"] = time.time()
            (self.dir / f"{sid}.meta.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")

    def messages(self, sid: str, limit: int = 100) -> builtins.list[dict[str, Any]]:
        p = self.dir / f"{sid}.jsonl"
        if not p.exists():
            return []
        lines = p.read_text(encoding="utf-8").splitlines()
        return [json.loads(l) for l in lines[-limit:]]

    def update_summary(self, sid: str, summary: str | None, title: str | None = None) -> None:
        meta = self.get(sid)
        if not meta:
            return
        if summary:
            meta["summary"] = summary
        if title:
            meta["title"] = title[:40]
        meta["updated_at"] = time.time()
        (self.dir / f"{sid}.meta.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")

    def update_meta(self, sid: str, **fields: Any) -> None:
        """更新会话元数据任意字段（如滚动摘要 rolling_summary）。"""
        meta = self.get(sid)
        if not meta:
            return
        meta.update(fields)
        meta["updated_at"] = time.time()
        (self.dir / f"{sid}.meta.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")

    # ---------- 跨会话记忆 ----------
    def recent_summaries(self, limit: int = 5) -> str:
        """最近 N 个会话的摘要（注入系统提示，实现跨会话记忆）"""
        lines = []
        for m in self.list()[:limit]:
            if m.get("summary"):
                lines.append(f"- [{m['title']}] {m['summary']}")
        return "\n".join(lines) or "(无历史会话)"
