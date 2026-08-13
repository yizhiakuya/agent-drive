"""配置管理：env 优先 + 默认值兜底。

设计：
- Settings 为唯一配置入口（pydantic-settings）
- 敏感凭据（LLM API Key）由 agent 自管理存 system/agent-config.json，不进 env
- APP_ENV 区分 dev/test/prod
"""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="AGENT_DRIVE_",
        env_file=".env",
        extra="ignore",
    )

    # 运行环境
    app_env: str = "dev"  # dev | test | prod
    host: str = "0.0.0.0"
    port: int = 8000

    # 路径（相对 backend/）
    backend_dir: Path = Path(__file__).resolve().parent.parent.parent
    system_dir: Path = Path("system")
    data_dir: Path = Path("data")

    # Agent
    max_steps: int = 10
    context_window: int = 262144  # 模型上下文窗口（256K，前端进度条总量）
    context_budget: int = 24000
    compress_threshold: float = 0.6  # 历史 token 超预算此比例时自动压缩
    compress_keep_recent: int = 8  # 压缩时保留最近消息条数
    roundtrip_compress_threshold: float = 0.9  # 轮内工具往返超预算此比例时压缩
    max_tool_output: int = 2000
    summarize_threshold: int = 12

    # CORS
    cors_origins: list[str] = ["*"]

    @property
    def system_path(self) -> Path:
        return self.backend_dir / self.system_dir

    @property
    def data_path(self) -> Path:
        return self.backend_dir / self.data_dir

    @property
    def sessions_path(self) -> Path:
        return self.system_path / "sessions"


@lru_cache
def get_settings() -> Settings:
    return Settings()
