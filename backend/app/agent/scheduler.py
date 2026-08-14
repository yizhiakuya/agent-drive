"""规则自动执行调度器（M3 L2 主动服务）。

每天 03:30 自动运行：把用户记录的自动化规则喂给 LLM，
在【受限工具组】下静默执行整理类任务（禁删除/系统配置），
执行报告写入 data/Agent/notes/，用户下次对话可查看。

原则：自动化只做"整理类"动作（移动/重命名/复制/创建文件夹/写文件），
删除与配置类工具对自动执行不可见。
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

logger = logging.getLogger("agent-drive.scheduler")

AUTO_GROUPS = ("files", "analytics")


class AutomationScheduler:
    def __init__(self, container) -> None:
        self.container = container
        self._task: asyncio.Task | None = None
        self.last_run: dict[str, Any] = {}

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._run_loop(), name="automation-scheduler")
            logger.info("规则自动执行调度器已启动（每天 03:30）")

    def stop(self) -> None:
        if self._task:
            self._task.cancel()

    async def _run_loop(self) -> None:
        while True:
            now = time.localtime()
            target = 3 * 3600 + 30 * 60  # 03:30
            current = now.tm_hour * 3600 + now.tm_min * 60 + now.tm_sec
            wait = (target - current) % 86400 or 86400
            await asyncio.sleep(wait)
            try:
                await self.run_once()
            except Exception as e:  # 调度器自身永不崩
                logger.warning("自动执行失败: %s", e)

    async def run_once(self) -> dict[str, Any]:
        """执行一轮规则自动执行（定时/手动共用）。"""
        # 回收站清理（30 天前彻底删除）
        try:
            cleaned = self.container.storage.cleanup_trash(days=30)
            if cleaned:
                logger.info("回收站清理: %s 项", cleaned)
        except Exception:
            pass
        rules = self.container.memory.rules()
        if not rules:
            return {"ok": True, "skipped": "无自动化规则", "rules": 0}
        t0 = time.time()
        try:
            from datetime import date

            from .loop import AgentLoop

            today = date.today().isoformat()
            rules_text = "\n".join(f"- {r}" for r in rules)
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
                audit=lambda msg: self.container.audit.record(msg),
                sessions=self.container.sessions,
                skills=self.container.skills,
                max_steps=self.container.settings.max_steps,
                context_budget=self.container.settings.context_budget,
            )
            result = await loop.run(task_prompt, session_id=None, tool_groups=AUTO_GROUPS)
            elapsed = int((time.time() - t0) * 1000)
            self.last_run = {
                "ts": time.time(),
                "elapsed_ms": elapsed,
                "rules": len(rules),
                "steps": len(result.get("tool_trace", [])),
                "ok": True,
            }
            self.container.audit.record("automation_run", result=self.last_run)
            return self.last_run
        except Exception as e:
            self.last_run = {"ts": time.time(), "ok": False, "error": str(e)}
            return self.last_run
