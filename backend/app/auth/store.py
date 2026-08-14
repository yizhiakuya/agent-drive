"""认证存储与安全原语（纯标准库，零新依赖）。

system/auth.json 结构：
{
  "password_hash": "pbkdf2$<iterations>$<salt_hex>$<digest_hex>",
  "secret": "<hex>",                  # 会话令牌签名密钥（首次生成）
  "device_tokens": {
    "<sha256(token)>": {"device_id": "...", "name": "...", "created_at": ..., "last_used": ...}
  }
}

- 密码：PBKDF2-SHA256 60 万次迭代 + 随机盐，只存哈希
- 会话令牌：HMAC-SHA256 签名（无状态，30 天有效），web 走 HttpOnly Cookie
- 设备令牌：随机 43 字符（只存 SHA-256 哈希），App 后台同步/媒体预览用，可按设备吊销
- 登录/设密限速：每 IP 每分钟 5 次
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import threading
import time
from pathlib import Path
from typing import Any

PBKDF2_ITERATIONS = 600_000
SESSION_TTL_SECONDS = 30 * 86400  # 30 天
SESSION_COOKIE = "agentdrive_session"
RATE_LIMIT = 5
RATE_WINDOW = 60.0


def hash_password(password: str, salt: str | None = None, iterations: int = PBKDF2_ITERATIONS) -> str:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt), iterations).hex()
    return "pbkdf2$" + str(iterations) + "$" + salt + "$" + digest


class AuthStore:
    """Container 持有单实例；写操作加锁。"""

    def __init__(self, path: Path):
        self._path = Path(path)
        self._lock = threading.RLock()
        self._data: dict[str, Any] = {"device_tokens": {}}
        self._rate: dict[str, list[float]] = {}
        self._load()
        if not self._data.get("secret"):
            self._data["secret"] = secrets.token_hex(32)
            self._save()

    # ---- 持久化 ----
    def _load(self) -> None:
        if not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                self._data = {"device_tokens": {}, **data}
        except (json.JSONDecodeError, OSError):
            pass  # 坏文件：按未初始化处理，不阻塞启动

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self._path)

    # ---- 密码 ----
    def is_initialized(self) -> bool:
        with self._lock:
            return bool(self._data.get("password_hash"))

    def setup(self, password: str) -> None:
        """首次设置密码（已初始化则抛错）。"""
        if len(password) < 8:
            raise ValueError("密码至少 8 位")
        with self._lock:
            if self._data.get("password_hash"):
                raise ValueError("密码已设置")
            self._data["password_hash"] = hash_password(password)
            self._save()

    def verify_password(self, password: str) -> bool:
        with self._lock:
            stored = self._data.get("password_hash")
        if not stored:
            return False
        try:
            parts = stored.split("$")
            if len(parts) != 4 or parts[0] != "pbkdf2":
                return False
            iterations = int(parts[1])
            salt, digest = parts[2], parts[3]
            recomputed = hash_password(password, salt=salt, iterations=iterations).split("$")[3]
            return hmac.compare_digest(recomputed, digest)
        except (ValueError, TypeError):
            return False

    # ---- 会话令牌（HMAC 签名，无状态） ----
    def issue_session(self) -> str:
        payload = {"v": 1, "exp": int(time.time()) + SESSION_TTL_SECONDS}
        body = base64.urlsafe_b64encode(json.dumps(payload).encode("utf-8")).decode("ascii").rstrip("=")
        return "s1." + body + "." + self._sign(body)

    def verify_session(self, token: str) -> bool:
        try:
            version, body, sig = token.split(".")
            if version != "s1":
                return False
            if not hmac.compare_digest(sig, self._sign(body)):
                return False
            padded = body + "=" * (-len(body) % 4)
            payload = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
            return int(payload.get("exp", 0)) > time.time()
        except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
            return False

    def _sign(self, body: str) -> str:
        secret = str(self._data.get("secret", ""))
        return hmac.new(secret.encode("utf-8"), body.encode("utf-8"), hashlib.sha256).hexdigest()

    # ---- 设备令牌 ----
    def issue_device_token(self, device_id: str, name: str = "") -> str:
        """颁发设备令牌（只存哈希，明文仅返回一次）。"""
        token = secrets.token_urlsafe(32)
        with self._lock:
            self._data.setdefault("device_tokens", {})[self._hash_token(token)] = {
                "device_id": device_id,
                "name": name,
                "created_at": time.time(),
                "last_used": time.time(),
            }
            self._save()
        return token

    def verify_device_token(self, token: str) -> bool:
        if not token:
            return False
        key = self._hash_token(token)
        with self._lock:
            entry = self._data.get("device_tokens", {}).get(key)
            if entry is None:
                return False
            now = time.time()
            if now - entry.get("last_used", 0) > 60:  # 限频写入
                entry["last_used"] = now
                self._save()
            return True

    def revoke_device(self, device_id: str) -> int:
        """吊销某设备全部令牌，返回吊销数量。"""
        with self._lock:
            tokens = self._data.get("device_tokens", {})
            keys = [k for k, v in tokens.items() if v.get("device_id") == device_id]
            for k in keys:
                del tokens[k]
            if keys:
                self._save()
            return len(keys)

    @staticmethod
    def _hash_token(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()

    # ---- 限速（登录/设密，内存级） ----
    def check_rate(self, key: str, limit: int = RATE_LIMIT, window: float = RATE_WINDOW) -> bool:
        """返回 True 表示放行，False 表示超限。"""
        now = time.time()
        with self._lock:
            hits = [t for t in self._rate.get(key, []) if now - t < window]
            if len(hits) >= limit:
                self._rate[key] = hits
                return False
            hits.append(now)
            self._rate[key] = hits
            return True