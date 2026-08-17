"""scheduler.py 测试：execute_once 的 GC（cleanup_trash）与无规则短路路径、last_run 派生。"""
from __future__ import annotations

from typing import ClassVar

from app.agent.scheduler import AUTO_GROUPS, AutomationScheduler


class _FakeTasks:
    def enqueue_automation(self, origin):
        class _J:
            id = "job-1"
            status = "queued"
        return _J(), True


class _FakeJobStore:
    def __init__(self, latest=None):
        self.latest = latest

    def latest_by_type(self, type_, terminal_only=True):
        return self.latest


class _FakeContainer:
    def __init__(self, *, rules=None, job=None, cleanup_result=7):
        self.rules = rules
        self.job = job
        self._cleanup_result = cleanup_result

        class _Storage:
            def cleanup_trash(self, days=30):
                return self._c
        self.storage = _Storage()
        self.storage._c = cleanup_result

        class _Memory:
            def rules(self):
                return list(rules)
        self.memory = _Memory()

        class _Tasks:
            def enqueue_automation(self, origin):
                return _FakeTasks().enqueue_automation(origin)
        self.tasks = _Tasks()

        self.job_store = _FakeJobStore(job or None)

        class _Audit:
            def __init__(self):
                self.records = []
            def record(self, msg, result=None):
                self.records.append((msg, result))
        self.audit = _Audit()

        # placeholders referenced only when rules present (not exercised here)
        self.llm = None
        self.build_tool_registry = None
        self.sessions = None
        self.skills = None

        class _Settings:
            max_steps = 10
            context_budget = 1000
        self.settings = _Settings()


def test_AUTO_GROUPS_only_organizing_files():
    """自动执行只暴露通用 API、计划和技能工具，不含删除专用工具。"""
    assert AUTO_GROUPS == ("backend_api", "plan", "skills")


async def test_execute_once_gc_and_no_rules_short_circuit():
    """无规则时：仍先跑 GC（cleanup_trash 调用一次），随后短路返回 skipped。"""
    container = _FakeContainer(rules=[], cleanup_result=7)
    sched = AutomationScheduler(container)

    # 替换 storage.cleanup_trash 记录调用次数
    calls = []
    original = container.storage.cleanup_trash
    def counting_trash(days=30):
        calls.append(days)
        return original(days)
    container.storage.cleanup_trash = counting_trash

    result = await sched.execute_once()

    assert calls == [30], "即使无规则也必须先执行垃圾回收"
    assert result["ok"] is True
    assert result["skipped"] == "no automation rules"
    assert result["rules"] == 0
    assert "ts" in result


async def test_execute_once_gc_swallows_error():
    """cleanup_trash 抛异常时被吞掉，不影响主流程（短路返回）。"""
    container = _FakeContainer(rules=[])

    def boom(days=30):
        raise OSError("gc failed")
    container.storage.cleanup_trash = boom

    sched = AutomationScheduler(container)
    result = await sched.execute_once()
    assert result["ok"] is True
    assert result["skipped"] == "no automation rules"


def test_run_once_enqueues_durable_job():
    """run_once 是排程入口：入队持久任务、不阻塞执行。"""
    container = _FakeContainer(rules=[])
    sched = AutomationScheduler(container)
    result = asyncio_run(sched.run_once())
    assert result["ok"] is True
    assert result["queued"] is True
    assert result["task_id"] == "job-1"
    assert result["status"] == "queued"


def test_last_run_empty_when_no_job():
    container = _FakeContainer(job=None)
    sched = AutomationScheduler(container)
    assert sched.last_run == {}


def test_last_run_uses_job_result_when_present():
    class _Job:
        result: ClassVar = {"ts": 123, "rules": 2}
        finished_at = None
        updated_at = None
        error = None
        status = "succeeded"
    sched = AutomationScheduler(_FakeContainer(job=_Job()))
    assert sched.last_run == {"ts": 123, "rules": 2}


def test_last_run_fallback_to_ts_and_ok_false():
    class _Job:
        result = None
        finished_at = 999
        updated_at = 998
        error = "boom"
        status = "failed"
    sched = AutomationScheduler(_FakeContainer(job=_Job()))
    assert sched.last_run == {"ts": 999, "ok": False, "error": "boom"}


def asyncio_run(coro):
    import asyncio
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return asyncio.run(coro)
    # 已在事件循环内：新建线程不够安全，这里仅用于简单场景
    import concurrent.futures
    with concurrent.futures.ThreadPoolExecutor(1) as ex:
        return ex.submit(lambda: asyncio.run(coro)).result()
