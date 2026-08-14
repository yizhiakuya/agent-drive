"""Automation trigger and executor.

Schedules enqueue durable jobs. The LLM work itself runs only inside a task
worker, so API restarts do not lose execution state.
"""
from __future__ import annotations

import time
from datetime import date
from typing import Any

AUTO_GROUPS = ("files", "analytics")


class AutomationScheduler:
    def __init__(self, container) -> None:
        self.container = container

    @property
    def last_run(self) -> dict[str, Any]:
        job = self.container.job_store.latest_by_type("automation.run", terminal_only=True)
        if job is None:
            return {}
        if job.result:
            return job.result
        return {
            "ts": job.finished_at or job.updated_at,
            "ok": False,
            "error": job.error or job.status,
        }

    async def run_once(self) -> dict[str, Any]:
        """Compatibility entry point used by the Agent tool: enqueue, do not block."""
        job, created = self.container.tasks.enqueue_automation(origin="agent")
        return {
            "ok": True,
            "queued": created,
            "task_id": job.id,
            "status": job.status,
        }

    async def execute_once(self, context=None) -> dict[str, Any]:
        """Execute automation rules inside a task worker."""
        try:
            cleaned = self.container.storage.cleanup_trash(days=30)
        except Exception:
            cleaned = 0
        rules = self.container.memory.rules()
        if not rules:
            return {"ok": True, "skipped": "no automation rules", "rules": 0, "ts": time.time()}
        if context is not None:
            context.check_cancelled()

        from .loop import AgentLoop

        today = date.today().isoformat()
        rules_text = "\n".join(f"- {rule}" for rule in rules)
        task_prompt = (
            "你是网盘的自动化执行器，现在执行用户设定的自动化规则。\n"
            f"规则清单:\n{rules_text}\n\n"
            "任务:\n"
            "1. 用工具查看网盘现状\n"
            "2. 执行每条规则能完成的部分（只做整理类动作：移动/重命名/复制/"
            "创建文件夹/写文件；严禁删除任何文件）\n"
            "3. 无法完成的部分说明原因\n"
            f"4. 写执行报告到 Agent/notes/自动化报告-{today}.md（用 write_file），"
            "内容: 每条规则的执行情况、做了什么、为什么没做\n"
            "5. 用一句话总结本次执行结果"
        )
        loop = AgentLoop(
            self.container.llm.get_provider(),
            self.container.build_tool_registry(),
            self.container.memory,
            audit=lambda message: self.container.audit.record(message),
            sessions=self.container.sessions,
            skills=self.container.skills,
            max_steps=self.container.settings.max_steps,
            context_budget=self.container.settings.context_budget,
        )
        started = time.time()
        result = await loop.run(task_prompt, session_id=None, tool_groups=AUTO_GROUPS)
        if context is not None:
            context.check_cancelled()
        summary = {
            "ts": time.time(),
            "elapsed_ms": int((time.time() - started) * 1000),
            "rules": len(rules),
            "steps": len(result.get("tool_trace", [])),
            "trash_removed": cleaned,
            "ok": True,
        }
        self.container.audit.record("automation_run", result=summary)
        return summary
