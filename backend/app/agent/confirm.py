"""高风险操作确认判定（安全维度）。

修复 A2（确认机制可伪造 + 重放双执行）：
- pending_confirmation 携带服务端签名 nonce（HMAC），伪造/篡改无效
- 确认校验：签名匹配 + 工具/参数哈希一致 + 一次性消费（存于会话 meta）
- 防重放：同一 (tool, args) 已确认过则本会话内不再执行第二次
"""
from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import time
from typing import Any

# 签名密钥（每次进程启动随机生成；确认只在本进程生命周期内有效）
_SIGNING_KEY = secrets.token_bytes(32)
_NONCE_TTL = 600  # 确认 token 有效期 10 分钟


def _args_hash(arguments: dict[str, Any]) -> str:
    return hashlib.sha256(
        json.dumps(arguments, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()[:16]


def _sign(nonce: str, tool: str, args_hash: str, ts: int) -> str:
    msg = f"{nonce}:{tool}:{args_hash}:{ts}".encode()
    return hmac.new(_SIGNING_KEY, msg, hashlib.sha256).hexdigest()


def issue_confirmation(tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
    """签发确认请求（服务端签名，含一次性 nonce）。"""
    nonce = secrets.token_hex(16)
    ts = int(time.time())
    return {
        "tool": tool,
        "arguments": arguments,
        "nonce": nonce,
        "ts": ts,
        "signature": _sign(nonce, tool, _args_hash(arguments), ts),
        "message": f"Agent 请求执行高风险操作：{tool}({json.dumps(arguments, ensure_ascii=False)})",
    }


def verify_confirmation(
    pending: dict[str, Any],
    confirmation: dict[str, Any],
    consumed: set[str],
) -> tuple[bool, str]:
    """校验用户提交的确认是否与签发的 pending 匹配且未被消费。

    返回 (ok, error)。ok 时调用方应把 nonce 加入 consumed 集合。
    """
    try:
        tool = confirmation.get("tool")
        args = confirmation.get("arguments", {})
        if tool != pending.get("tool") or _args_hash(args) != _args_hash(pending.get("arguments", {})):
            return False, "确认内容与待确认操作不匹配"
        nonce = confirmation.get("nonce", "")
        sig = confirmation.get("signature", "")
        ts = confirmation.get("ts", 0)
        if nonce != pending.get("nonce"):
            return False, "nonce 不匹配（伪造或过期确认）"
        if int(time.time()) - int(ts) > _NONCE_TTL:
            return False, "确认已过期（超过 10 分钟），请重新发起操作"
        expected = _sign(nonce, tool, _args_hash(args), ts)
        if not hmac.compare_digest(sig, expected):
            return False, "签名校验失败（确认被伪造）"
        if nonce in consumed:
            return False, "该确认已被消费（禁止重放）"
        return True, ""
    except Exception as e:
        return False, f"确认格式非法: {e}"


def needs_confirmation(tool, tool_call, confirmed: list[dict[str, Any]]) -> bool:
    """red 级工具且不在已确认列表 → 需要确认。"""
    if tool is None or tool.level != "red":
        return False
    already = any(
        c.get("tool") == tool_call.name and c.get("arguments") == tool_call.arguments
        for c in confirmed
    )
    return not already
