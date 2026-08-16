"""系统工具集：Agent 管理自己的配置、规则、偏好（Agent 自管理核心）。"""
from __future__ import annotations

from typing import Any

from ...llm.base import ToolSpec
from ...llm.manager import PROVIDER_LABELS, LLMConfig, LLMManager, ProviderType
from .registry import ToolRegistry


def register_system_tools(
    reg: ToolRegistry,
    llm: LLMManager,
    memory,
    rules_path=None,
    audit_fn=None,
    scheduler=None,
    tasks=None,
) -> None:
    # ---------- 查看系统状态 ----------
    async def get_system_status() -> dict[str, Any]:
        cfg = llm.load()
        emb = cfg.embeddings if cfg else None
        return {
            "llm_configured": cfg is not None and bool(cfg.api_key),
            "llm": {
                "type": cfg.type if cfg else None,
                "base_url": cfg.base_url if cfg else None,
                "model": cfg.model if cfg else None,
            } if cfg else None,
            # 向量服务状态：Agent 必须能直接看到 embedding 是否已配置，
            # 不能靠全盘扫描的 index_stats 猜（生产会话曾因此幻觉"已配置完成"）
            "embeddings": {
                "configured": bool(emb and emb.api_key),
                "provider": emb.provider if emb else None,
                "base_url": emb.base_url if emb else None,
                "model": emb.model if emb else None,
                "api_key_masked": (emb.api_key[:6] + "…") if emb and emb.api_key else "",
            } if emb else None,
            "provider_options": PROVIDER_LABELS,
            "preferences": memory.all(),
            "rules": memory.list_rules(),
            "available_tools": [t["name"] for t in reg.list()],
        }

    reg.register(
        ToolSpec(
            "get_system_status",
            "查看系统当前状态：LLM/向量服务配置、偏好、规则、可用工具",
            {},
            doc=(
                "用途：查看 Agent Drive 系统全貌。\n"
                "参数：无。\n"
                "输出：{llm_configured, llm, embeddings(configured/provider/model/api_key_masked), provider_options, preferences, rules, available_tools}。\n"
                "注意：不暴露 API Key 明文（仅掩码前缀）。"
            ),
        ),
        get_system_status,
        group="system",
    )

    # ============ 持久后台任务 ============
    async def task_status(task_id: str) -> dict[str, Any]:
        if tasks is None:
            return {"ok": False, "error": "后台任务系统未启用"}
        job = tasks.store.get(task_id)
        if job is None:
            return {"ok": False, "error": "任务不存在"}
        return {"ok": True, "task": job.to_dict()}

    reg.register(
        ToolSpec(
            "task_status",
            "查看后台任务状态和进度",
            {
                "type": "object",
                "properties": {"task_id": {"type": "string", "description": "任务 ID"}},
                "required": ["task_id"],
            },
        ),
        task_status,
        group="system",
    )

    async def cancel_task(task_id: str) -> dict[str, Any]:
        if tasks is None:
            return {"ok": False, "error": "后台任务系统未启用"}
        job = tasks.store.cancel(task_id)
        if job is not None and job.type == "index.rebuild":
            tasks.store.cancel_children(job.id)
        return {"ok": job is not None, "task": job.to_dict() if job else None}

    reg.register(
        ToolSpec(
            "cancel_task",
            "取消一个仍在排队或执行中的后台任务",
            {
                "type": "object",
                "properties": {"task_id": {"type": "string", "description": "任务 ID"}},
                "required": ["task_id"],
            },
        ),
        cancel_task,
        level="yellow",
        group="system",
    )

    async def rebuild_search_index(force: bool = False) -> dict[str, Any]:
        if tasks is None:
            return {"ok": False, "error": "后台任务系统未启用"}
        job, created = tasks.enqueue_rebuild(force=force, origin="agent")
        return {"ok": True, "queued": created, "task_id": job.id, "status": job.status}

    reg.register(
        ToolSpec(
            "rebuild_search_index",
            "后台重建全文与向量搜索索引",
            {
                "type": "object",
                "properties": {
                    "force": {"type": "boolean", "description": "是否强制重建已是最新的索引"},
                },
            },
        ),
        rebuild_search_index,
        level="yellow",
        group="system",
    )

    # ---------- 配置 LLM（Agent 自管理核心） ----------
    async def set_llm_provider(
        type: ProviderType,
        base_url: str,
        api_key: str,
        model: str,
    ) -> dict[str, Any]:
        cfg = LLMConfig(type=type, base_url=base_url, api_key=api_key, model=model)
        diag = await llm.test(cfg)  # 先测试再保存
        if not diag.get("ok"):
            return {"saved": False, "test": diag, "message": "连接测试失败，配置未保存"}
        llm.save(cfg)
        return {"saved": True, "test": diag, "message": f"已保存并验证通过: {PROVIDER_LABELS[type]}"}

    reg.register(
        ToolSpec(
            "set_llm_provider",
            "修改并测试 LLM 提供方配置",
            {
                "type": "object",
                "properties": {
                    "type": {"type": "string", "enum": ["openai_compat", "openai_responses", "anthropic"]},
                    "base_url": {"type": "string", "description": "API 地址，如 https://api.deepseek.com/v1"},
                    "api_key": {"type": "string"},
                    "model": {"type": "string", "description": "模型名，如 deepseek-chat"},
                },
                "required": ["type", "base_url", "api_key", "model"],
            },
        ),
        set_llm_provider,
        level="red",
        group="system",
    )

    # ---------- 配置向量服务（Agent-first 配置入口） ----------
    async def configure_embeddings(
        provider: str,
        base_url: str,
        api_key: str,
        model: str,
    ) -> dict[str, Any]:
        from ...llm.manager import EmbeddingConfig
        cfg = llm.load()
        if cfg is None:
            return {"ok": False, "error": "LLM 未配置，请先完成 Onboarding"}
        if provider != "jina":
            return {"ok": False, "error": "当前仅支持 Jina embedding provider"}
        # key 留空沿用语义（与设置页 PUT /config/embeddings 一致）：
        # 仅当 provider/base_url/model 与已存配置一致才回退已存 key
        incoming_key = api_key.strip()
        base = base_url.strip() or "https://api.jina.ai/v1"
        name = model.strip() or "jina-embeddings-v3"
        if not incoming_key:
            old = cfg.embeddings
            if old is not None and old.provider == provider and old.base_url == base and old.model == name:
                incoming_key = old.api_key
            else:
                return {"ok": False, "error": "api_key 留空仅当 provider/base_url/model 与已存配置一致时才沿用已存 key；改配置必须重填"}
        new_emb = EmbeddingConfig(provider=provider, base_url=base, api_key=incoming_key, model=name)
        cfg.embeddings = new_emb
        llm.save(cfg)
        # 测试连接（不阻塞保存，与设置页行为一致）
        diag = await llm.get_embedding_provider().test_connection()
        task = None
        if diag.get("ok") and tasks is not None:
            tasks.refresh_embedder()
            job, _ = tasks.enqueue_rebuild(force=True, origin="embedding.config")
            task = job.to_dict()
        return {
            "ok": True,
            "saved": {"provider": provider, "base_url": base, "model": name},
            "test": diag,
            "rebuild_task": task,
        }

    reg.register(
        ToolSpec(
            "configure_embeddings",
            "配置向量化（语义搜索）服务并测试连接",
            {
                "type": "object",
                "properties": {
                    "provider": {"type": "string", "description": "服务商，当前仅支持 jina"},
                    "base_url": {"type": "string", "description": "API 地址，如 https://api.jina.ai/v1"},
                    "api_key": {"type": "string", "description": "API Key；留空且 provider/base_url/model 与已存配置一致时沿用已存 key"},
                    "model": {"type": "string", "description": "模型名，如 jina-embeddings-v3"},
                },
                "required": ["provider", "base_url", "api_key", "model"],
            },
            doc=(
                "用途：配置语义搜索用的 embedding（向量化）服务（如 Jina AI）。\n"
                "参数：provider/base_url/api_key/model；api_key 留空仅在其余三项与已存配置一致时沿用已存 key。\n"
                "输出：{ok, saved, test(连接诊断), rebuild_task(连接成功时自动重建向量索引的任务)}。\n"
                "注意：配置成功后系统会自动入队向量索引重建；连接测试失败也会保存配置（与设置页一致），test.ok=false 时告知用户并建议复核 key。"
            ),
        ),
        configure_embeddings,
        level="red",
        group="system",
    )

    # ---------- 测试 LLM 连接 ----------
    async def test_llm_connection() -> dict[str, Any]:
        if not llm.is_configured():
            return {"ok": False, "message": "尚未配置 LLM"}
        cfg = llm.load()
        if cfg is None:
            return {"ok": False, "message": "尚未配置 LLM"}
        return await llm.test(cfg)

    reg.register(
        ToolSpec(
            "test_llm_connection",
            "测试当前 LLM 配置是否可用",
            {},
            doc=(
                "用途：诊断当前 LLM 连接（延迟/模型/工具支持）。\n"
                "参数：无。\n"
                "输出：{ok, model, latency_ms, supports_tools, error?}。"
            ),
        ),
        test_llm_connection,
        group="system",
    )

    # ---------- 偏好管理 ----------
    async def set_preference(key: str, value: str) -> dict[str, Any]:
        # 注入防护：拒绝指令式文本 + 长度上限
        if any(m in value for m in ("忽略", "无视", "ignore", "绕过")):
            return {"ok": False, "error": "偏好内容含指令式文本，已拒绝（注入防护）"}
        if len(value) > 200:
            return {"ok": False, "error": "偏好值过长（上限 200 字符）"}
        memory.set(key, value)
        return {"saved": True, "preferences": memory.all()}

    reg.register(
        ToolSpec(
            "set_preference",
            "设置用户偏好（语言/整理风格/命名规则等），永久保存",
            {
                "type": "object",
                "properties": {
                    "key": {"type": "string", "description": "偏好名，如 language / organize_style / naming_rule"},
                    "value": {"type": "string"},
                },
                "required": ["key", "value"],
            },
            doc=(
                "用途：保存用户长期偏好。用户表达习惯（语言、整理风格、命名规则）时调用。\n"
                "参数：key（必填）偏好名；value（必填）偏好值。\n"
                "输出：{saved, preferences} 当前全部偏好。\n"
                "注意：系统提示会加载这些偏好，直接影响 Agent 行为。"
            ),
        ),
        set_preference,
        level="yellow",
        group="system",
    )

    # ---------- 规则管理（自动化） ----------
    async def add_rule(rule: str) -> dict[str, Any]:
        # 注入防护：拒绝指令式文本 + 数量/长度上限
        if any(m in rule for m in ("忽略", "无视", "ignore", "绕过")):
            return {"ok": False, "error": "规则内容含指令式文本，已拒绝（注入防护）"}
        if len(rule) > 300:
            return {"ok": False, "error": "规则过长（上限 300 字符）"}
        if len(memory.list_rules()) >= 20:
            return {"ok": False, "error": "规则数量已达上限（20 条），请先删除旧规则"}
        memory.add_rule(rule)
        return {"saved": True, "rules": memory.list_rules()}

    reg.register(
        ToolSpec(
            "add_rule",
            "添加一条自动化规则",
            {
                "type": "object",
                "properties": {"rule": {"type": "string", "description": "规则的自然语言描述"}},
                "required": ["rule"],
            },
            doc=(
                "用途：添加自动化规则（如'下载的文件自动归档到 Downloads'）。\n"
                "参数：rule（必填）规则描述。\n"
                "输出：{saved, rules} 当前全部规则。\n"
                "注意：M3 起规则会被定时执行，当前仅记录。"
            ),
        ),
        add_rule,
        level="yellow",
        group="system",
    )

    async def remove_rule(index: int) -> dict[str, Any]:
        ok = memory.remove_rule(index)
        return {"removed": ok, "rules": memory.list_rules()}

    reg.register(
        ToolSpec(
            "remove_rule",
            "删除一条规则",
            {"type": "object", "properties": {"index": {"type": "integer", "description": "规则序号（从0开始）"}}, "required": ["index"]},
            doc=(
                "用途：删除指定序号的规则。\n"
                "参数：index（必填）规则序号。\n"
                "输出：{removed, rules}。序号无效时 removed=false。"
            ),
        ),
        remove_rule,
        level="yellow",
        group="system",
    )

    # ---------- 审计 ----------
    async def view_audit_log(limit: int = 20) -> str:
        if audit_fn is None:
            return "审计功能未启用"
        return audit_fn(limit)

    reg.register(
        ToolSpec(
            "view_audit_log",
            "查看最近的操作审计日志",
            {"type": "object", "properties": {"limit": {"type": "integer", "description": "查看条数，默认20"}}},
            doc=(
                "用途：查看 Agent 最近执行过的所有操作记录（透明性）。\n"
                "参数：limit（可选）。\n"
                "输出：最近 N 条审计事件文本。"
            ),
        ),
        view_audit_log,
        group="system",
    )

    # ============ M3 规则自动执行 ============
    async def run_automation_now() -> dict[str, Any]:
        """立即执行一轮规则自动执行（默认每天 03:30 自动跑）"""
        if scheduler is None:
            return {"ok": False, "error": "自动化调度器未启用"}
        return await scheduler.run_once()

    reg.register(
        ToolSpec(
            "run_automation_now",
            "立即执行一轮自动化规则",
            {},
            doc=(
                "用途：手动触发规则自动执行（平时每天 03:30 自动运行）。\n"
                "参数：无。\n"
                "输出：{ok, queued, task_id, status}，实际执行在后台任务中完成。\n"
                "注意：自动执行只做整理类动作（移动/重命名/复制/建文件夹/写文件），"
                "严禁删除，执行报告写入 notes/自动化报告-*.md。"
            ),
        ),
        run_automation_now,
        group="system",
    )

    async def automation_status() -> dict[str, Any]:
        """查看最近一次自动执行结果"""
        if scheduler is None:
            return {"enabled": False}
        latest = tasks.store.latest_by_type("automation.run") if tasks is not None else None
        return {
            "enabled": True,
            "last_run": scheduler.last_run,
            "current_task": latest.to_dict() if latest and latest.status not in {"succeeded", "failed", "cancelled"} else None,
        }

    reg.register(
        ToolSpec(
            "automation_status",
            "查看规则自动执行状态",
            {},
            doc=(
                "用途：查看自动执行的最近结果（enabled/最后执行时间/步数）。\n"
                "参数：无。\n"
                "输出：{enabled, last_run}。"
            ),
        ),
        automation_status,
        group="system",
    )

