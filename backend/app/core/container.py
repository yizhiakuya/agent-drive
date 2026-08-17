"""依赖注入容器：所有服务的组装点。

- 显式声明依赖，避免模块级全局单例
- 便于测试替身（传 settings 覆盖）
- main.py 只做：创建 Container → 启动
"""
from __future__ import annotations

import secrets
from typing import Any

from ..agent.loop import AgentLoop
from ..agent.memory.preferences import MemoryStore
from ..agent.memory.sessions import SessionStore
from ..agent.onboarding import Onboarding
from ..agent.skills import SkillsRegistry
from ..agent.tools.analytics import register_analytics_tools
from ..agent.tools.api import BackendApiClient, build_internal_app, register_backend_api_tool
from ..agent.tools.files import register_file_tools
from ..agent.tools.memory import register_memory_tools
from ..agent.tools.registry import ToolRegistry
from ..agent.tools.system import register_system_tools
from ..auth.store import AuthStore
from ..devices.registry import DeviceRegistry
from ..ingest.pipeline import IngestPipeline
from ..llm.manager import LLMManager
from ..storage.local import LocalStorage
from ..storage.upload_index import UploadIndex
from ..tasks.handlers import build_job_registry
from ..tasks.runner import JobRunner
from ..tasks.service import TaskService
from ..tasks.store import JobStore
from .config import Settings, get_settings
from .logging import AuditLogger, setup_logging


class Container:
    """Agent Drive 服务容器（组合根）"""

    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()
        self.app: Any | None = None
        self.internal_api_token = secrets.token_urlsafe(32)
        self.logger = setup_logging(self.settings.app_env)
        self.logger.info(
            "container init: env=%s data=%s system=%s",
            self.settings.app_env,
            self.settings.data_path,
            self.settings.system_path,
        )

        # ---- 基础设施 ----
        self.audit = AuditLogger(self.settings.system_path / "audit.log")

        # ---- 领域服务 ----
        self.llm = LLMManager(self.settings.system_path / "agent-config.json")
        self.storage = LocalStorage(self.settings.data_path)
        # 上传去重索引（秒传）：挂 storage 以便校验条目是否过期
        self.upload_index = UploadIndex(self.settings.system_path / "upload-index.json", storage=self.storage)
        # 反向注入：storage 的内容变更（改名/移动/删除/覆盖）自动失效索引条目
        self.storage.attach_index(self.upload_index)
        # Agent 工作空间：网盘内的 Agent/ 目录（记忆/角色/笔记都在文件空间，用户可见可编辑）
        agent_ws = self.storage.resolve("Agent")
        agent_ws.mkdir(parents=True, exist_ok=True)
        self.memory = MemoryStore(agent_ws, migrate_from=self.settings.system_path)
        self.sessions = SessionStore(self.settings.sessions_path)
        self.devices = DeviceRegistry(self.settings.system_path / "devices.json")
        self.auth = AuthStore(self.settings.system_path / "auth.json")
        self.onboarding = Onboarding(self.llm, self.memory)
        self.skills = SkillsRegistry(self.settings.backend_dir / "skills")
        self.ingest = IngestPipeline(self.storage, embedder=self.llm.get_embedding_provider())
        from ..agent.scheduler import AutomationScheduler

        self.job_store = JobStore(self.settings.system_path / "tasks.sqlite3")
        self.tasks = TaskService(
            self.job_store,
            self.storage,
            self.ingest,
            self.llm,
            timezone=self.settings.task_timezone,
        )
        self.storage.attach_change_listener(self.tasks.handle_storage_change)
        self.scheduler = AutomationScheduler(self)
        self.task_registry = build_job_registry(self)
        self.task_runner = JobRunner(
            self.job_store,
            self.task_registry,
            poll_seconds=self.settings.task_poll_seconds,
            lease_seconds=self.settings.task_lease_seconds,
        )
        self.tasks.ensure_default_schedules()

    # ---- 工厂 ----
    def build_tool_registry(self, request: Any | None = None) -> ToolRegistry:
        if self.app is None:
            self.app = build_internal_app(self)
        reg = ToolRegistry()
        self.ingest.embedder = self.llm.get_embedding_provider()  # 热刷新（对话改配置后生效）

        # 兼容尚未暴露为 HTTP 路由的内部能力；只把统一 backend_api 注册给模型。
        legacy = ToolRegistry()
        register_file_tools(legacy, self.storage, self.ingest, tasks=self.tasks)
        register_system_tools(
            legacy,
            self.llm,
            self.memory,
            audit_fn=self.audit.record,
            scheduler=self.scheduler,
            tasks=self.tasks,
        )
        register_memory_tools(legacy, self.memory)
        register_analytics_tools(legacy, self.llm.get_provider, self.audit, self.sessions)

        register_backend_api_tool(
            reg,
            BackendApiClient(
                self.app,
                request,
                self.storage,
                internal=request is None,
                legacy_registry=legacy,
            ),
        )
        return reg

    def build_agent(self, request: Any | None = None) -> AgentLoop:
        """每次对话构造一个新 Agent（历史由会话持久化）"""
        self.skills.reload()  # 技能热加载：新增 SKILL.md 无需重启
        provider = self.llm.get_provider()  # 未配置时抛 ConfigError
        reg = self.build_tool_registry(request)
        return AgentLoop(
            provider, reg, self.memory,
            audit=lambda msg: self.audit.record(msg),
            sessions=self.sessions,
            skills=self.skills,
            max_steps=self.settings.max_steps,
            context_budget=self.settings.context_budget,
            max_tool_output=self.settings.max_tool_output,
            summarize_threshold=self.settings.summarize_threshold,
            context_window=self.settings.context_window,
            compress_threshold=self.settings.compress_threshold,
            compress_keep_recent=self.settings.compress_keep_recent,
            roundtrip_compress_threshold=self.settings.roundtrip_compress_threshold,
        )

    def close(self) -> None:
        """清理资源（M2 接入 db/queue 时使用）"""
        return
