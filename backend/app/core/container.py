"""依赖注入容器：所有服务的组装点。

- 显式声明依赖，避免模块级全局单例
- 便于测试替身（传 settings 覆盖）
- main.py 只做：创建 Container → 启动
"""
from __future__ import annotations

from ..agent.loop import AgentLoop
from ..agent.memory.preferences import MemoryStore
from ..agent.memory.sessions import SessionStore
from ..agent.onboarding import Onboarding
from ..agent.skills import SkillsRegistry
from ..agent.tools.analytics import register_analytics_tools
from ..agent.tools.files import register_file_tools
from ..agent.tools.memory import register_memory_tools
from ..agent.tools.registry import ToolRegistry
from ..agent.tools.system import register_system_tools
from ..llm.manager import LLMManager
from ..storage.local import LocalStorage
from .config import Settings, get_settings
from .logging import AuditLogger, setup_logging


class Container:
    """Agent Drive 服务容器（组合根）"""

    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()
        self.logger = setup_logging(self.settings.app_env)

        # ---- 基础设施 ----
        self.audit = AuditLogger(self.settings.system_path / "audit.log")

        # ---- 领域服务 ----
        self.llm = LLMManager(self.settings.system_path / "agent-config.json")
        self.storage = LocalStorage(self.settings.data_path)
        # Agent 工作空间：网盘内的 Agent/ 目录（记忆/角色/笔记都在文件空间，用户可见可编辑）
        agent_ws = self.storage.resolve("Agent")
        agent_ws.mkdir(parents=True, exist_ok=True)
        self.memory = MemoryStore(agent_ws, migrate_from=self.settings.system_path)
        self.sessions = SessionStore(self.settings.sessions_path)
        self.onboarding = Onboarding(self.llm, self.memory)
        self.skills = SkillsRegistry(self.settings.backend_dir / "skills")

    # ---- 工厂 ----
    def build_tool_registry(self) -> ToolRegistry:
        reg = ToolRegistry()
        register_system_tools(reg, self.llm, self.memory, audit_fn=self.audit.tail)
        register_file_tools(reg, self.storage)
        register_memory_tools(reg, self.memory)
        register_analytics_tools(reg, self.llm.get_provider, self.audit, self.sessions)
        return reg

    def build_agent(self) -> AgentLoop:
        """每次对话构造一个新 Agent（历史由会话持久化）"""
        self.skills.reload()  # 技能热加载：新增 SKILL.md 无需重启
        provider = self.llm.get_provider()  # 未配置时抛 ConfigError
        reg = self.build_tool_registry()
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
