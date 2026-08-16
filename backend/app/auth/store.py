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
- 配对码（扫码即授权）：已登录 web 生成 → 二维码携带 → App 兑换设备令牌。
  一次性、5 分钟有效、最多 3 个未使用；只存 SHA-256 哈希
- 登录/设密限速：每 IP 每分钟 5 次；配对码兑换限速：每 IP 每分钟 10 次
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import math
import os
import secrets
import stat
import tempfile
import threading
import time
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

try:
    import fcntl
except ImportError:  # pragma: no cover - Windows fallback uses the process lock.
    fcntl = None  # type: ignore[assignment]

PBKDF2_ITERATIONS = 600_000
SESSION_TTL_SECONDS = 30 * 86400  # 30 天
SESSION_COOKIE = "agentdrive_session"
RATE_LIMIT = 5
RATE_WINDOW = 60.0
PAIRING_TTL_SECONDS = 300  # 配对码 5 分钟有效
PAIRING_MAX_OUTSTANDING = 3  # 最多 3 个未使用配对码

_AUTH_PATH_LOCKS_GUARD = threading.Lock()
_AUTH_PATH_LOCKS: dict[str, threading.RLock] = {}


def _shared_path_lock(path: Path) -> threading.RLock:
    """同一进程内针对同一 auth 文件共享锁；POSIX 上再叠加 flock。"""
    key = os.path.normcase(str(path.parent.resolve() / path.name))
    with _AUTH_PATH_LOCKS_GUARD:
        return _AUTH_PATH_LOCKS.setdefault(key, threading.RLock())


def _is_finite_number(value: Any) -> bool:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return False
    try:
        return math.isfinite(float(value))
    except (OverflowError, ValueError):
        return False


class AuthStoreLoadError(RuntimeError):
    """认证配置存在但无法安全加载。"""


def hash_password(password: str, salt: str | None = None, iterations: int = PBKDF2_ITERATIONS) -> str:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt), iterations).hex()
    return "pbkdf2$" + str(iterations) + "$" + salt + "$" + digest


class AuthStore:
    """认证状态存储；同路径共享线程锁，POSIX 再加 sidecar flock 支持多进程。"""

    def __init__(self, path: Path):
        self._path = Path(path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._lock_path = self._path.with_name(self._path.name + ".lock")
        self._lock = _shared_path_lock(self._path)
        self._state_local = threading.local()
        self._data: dict[str, Any] = {"device_tokens": {}, "pairings": {}, "revoked_sessions": {}}
        self._rate: dict[str, list[float]] = {}
        with self._state_lock(reload=True):
            if not self._data.get("secret"):
                self._data["secret"] = secrets.token_hex(32)
                self._save()

    @contextmanager
    def _state_lock(self, *, reload: bool = False) -> Iterator[None]:
        """获取线程锁和跨进程 flock；外层事务重新加载磁盘快照。"""
        with self._lock:
            depth = getattr(self._state_local, "depth", 0)
            lock_fd: int | None = None
            if depth == 0 and fcntl is not None:
                lock_fd = os.open(
                    self._lock_path,
                    os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0),
                    0o600,
                )
                try:
                    os.fchmod(lock_fd, 0o600)
                    fcntl.flock(lock_fd, fcntl.LOCK_EX)
                except Exception:
                    os.close(lock_fd)
                    raise
            self._state_local.depth = depth + 1
            try:
                if reload and depth == 0:
                    self._load_unlocked()
                yield
            finally:
                self._state_local.depth = depth
                if lock_fd is not None:
                    fcntl.flock(lock_fd, fcntl.LOCK_UN)
                    os.close(lock_fd)

    # ---- 持久化 ----
    def _load_unlocked(self) -> None:
        try:
            fd = os.open(
                self._path,
                os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
            )
        except FileNotFoundError:
            self._data = {"device_tokens": {}, "pairings": {}, "revoked_sessions": {}}
            return
        except OSError as exc:
            raise AuthStoreLoadError(
                f"认证配置损坏或不可读，拒绝按未初始化状态启动: {self._path}"
            ) from exc
        try:
            value = os.fstat(fd)
            if not stat.S_ISREG(value.st_mode):
                raise OSError("认证配置不是普通文件")
            with os.fdopen(fd, "r", encoding="utf-8") as stream:
                fd = -1
                data = json.load(stream)
        except (json.JSONDecodeError, OSError, UnicodeDecodeError) as exc:
            raise AuthStoreLoadError(f"认证配置损坏或不可读，拒绝按未初始化状态启动: {self._path}") from exc
        finally:
            if fd >= 0:
                os.close(fd)
        if not isinstance(data, dict):
            raise AuthStoreLoadError(f"认证配置必须是 JSON 对象，拒绝按未初始化状态启动: {self._path}")
        self._data = {"device_tokens": {}, "pairings": {}, "revoked_sessions": {}, **data}
        for field in ("device_tokens", "pairings", "revoked_sessions"):
            if not isinstance(self._data.get(field), dict):
                raise AuthStoreLoadError(f"认证配置字段 {field} 必须是 JSON 对象: {self._path}")
        if self._data.get("secret") is not None and (
            not isinstance(self._data["secret"], str) or not self._data["secret"]
        ):
            raise AuthStoreLoadError(f"认证配置字段 secret 必须是非空字符串: {self._path}")
        if self._data.get("password_hash") is not None and not isinstance(self._data["password_hash"], str):
            raise AuthStoreLoadError(f"认证配置字段 password_hash 必须是字符串: {self._path}")
        for field in ("device_tokens", "pairings"):
            if not all(
                isinstance(key, str) and isinstance(value, dict)
                for key, value in self._data[field].items()
            ):
                raise AuthStoreLoadError(f"认证配置字段 {field} 的条目必须是 JSON 对象: {self._path}")
        try:
            for entry in self._data["device_tokens"].values():
                if not isinstance(entry.get("device_id", ""), str):
                    raise TypeError
                if not isinstance(entry.get("name", ""), str):
                    raise TypeError
                if not _is_finite_number(entry.get("created_at", 0)):
                    raise ValueError
                if not _is_finite_number(entry.get("last_used", 0)):
                    raise ValueError
            for entry in self._data["pairings"].values():
                if not _is_finite_number(entry.get("created_at", 0)):
                    raise ValueError
                if not _is_finite_number(entry.get("expires_at", 0)):
                    raise ValueError
                used_at = entry.get("used_at")
                device_id = entry.get("device_id")
                if used_at is not None and not _is_finite_number(used_at):
                    raise ValueError
                if device_id is not None and not isinstance(device_id, str):
                    raise TypeError
        except (TypeError, ValueError) as exc:
            raise AuthStoreLoadError(f"认证配置设备或配对条目无效: {self._path}") from exc
        try:
            for key, expiry in self._data["revoked_sessions"].items():
                if not isinstance(key, str) or not _is_finite_number(expiry):
                    raise TypeError
                int(expiry)
        except (TypeError, ValueError, OverflowError) as exc:
            raise AuthStoreLoadError(f"认证配置 revoked_sessions 条目无效: {self._path}") from exc

    def _save(self) -> None:
        """在已持有 state lock 时安全写入，并持久化父目录目录项。"""
        self._path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(
            prefix=f".{self._path.name}.", suffix=".tmp", dir=self._path.parent,
        )
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
                fd = -1
                json.dump(self._data, stream, ensure_ascii=False, indent=2)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            tmp.chmod(0o600)
            tmp.replace(self._path)
            if os.name == "posix":
                directory_fd = os.open(
                    self._path.parent,
                    os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
                )
                try:
                    os.fsync(directory_fd)
                finally:
                    os.close(directory_fd)
        except Exception:
            if fd >= 0:
                os.close(fd)
            tmp.unlink(missing_ok=True)
            raise

    # ---- 密码 ----
    def is_initialized(self) -> bool:
        with self._state_lock(reload=True):
            return bool(self._data.get("password_hash"))

    def setup(self, password: str) -> None:
        """首次设置密码（已初始化则抛错）。"""
        if len(password) < 8:
            raise ValueError("密码至少 8 位")
        with self._state_lock(reload=True):
            if self._data.get("password_hash"):
                raise ValueError("密码已设置")
            self._data["password_hash"] = hash_password(password)
            self._save()

    def verify_password(self, password: str) -> bool:
        with self._state_lock(reload=True):
            stored = self._data.get("password_hash")
        if not stored:
            return False
        try:
            parts = stored.split("$")
            if len(parts) != 4 or parts[0] != "pbkdf2":
                return False
            iterations = int(parts[1])
            salt, digest = parts[2], parts[3]
            if not 100_000 <= iterations <= 2_000_000:
                return False
            if len(salt) != 32 or len(digest) != 64:
                return False
            bytes.fromhex(salt)
            bytes.fromhex(digest)
            recomputed = hash_password(password, salt=salt, iterations=iterations).split("$")[3]
            return hmac.compare_digest(recomputed, digest)
        except (ValueError, TypeError, OverflowError):
            return False

    # ---- 会话令牌（HMAC 签名 + 持久撤销表） ----
    def _prune_revoked_sessions_locked(self, now: float) -> bool:
        revoked = self._data.setdefault("revoked_sessions", {})
        if not isinstance(revoked, dict):
            raise AuthStoreLoadError("认证配置 revoked_sessions 必须是 JSON 对象")
        active: dict[str, int] = {}
        for key, expiry in revoked.items():
            try:
                parsed = int(expiry)
            except (TypeError, ValueError):
                continue
            if parsed > now:
                active[str(key)] = parsed
        if active == revoked:
            return False
        self._data["revoked_sessions"] = active
        return True

    def issue_session(self) -> str:
        payload = {
            "v": 1,
            "exp": int(time.time()) + SESSION_TTL_SECONDS,
            "jti": secrets.token_hex(16),
        }
        body = base64.urlsafe_b64encode(json.dumps(payload).encode("utf-8")).decode("ascii").rstrip("=")
        with self._state_lock(reload=True):
            if self._prune_revoked_sessions_locked(time.time()):
                self._save()
            signature = self._sign(body)
        return "s1." + body + "." + signature

    @staticmethod
    def _session_payload(token: str) -> dict[str, Any] | None:
        try:
            version, body, _sig = token.split(".")
            if version != "s1":
                return None
            padded = body + "=" * (-len(body) % 4)
            payload = json.loads(base64.urlsafe_b64decode(padded).decode("utf-8"))
            return payload if isinstance(payload, dict) else None
        except (ValueError, json.JSONDecodeError, UnicodeDecodeError, binascii.Error, OverflowError):
            return None

    def _valid_session_locked(self, token: str, now: float) -> tuple[bool, int]:
        try:
            version, body, sig = token.split(".")
            if version != "s1" or not hmac.compare_digest(sig, self._sign(body)):
                return False, 0
            payload = self._session_payload(token)
            expiry = int((payload or {}).get("exp", 0))
            if expiry <= now:
                return False, expiry
            revoked = self._data.get("revoked_sessions", {})
            if not isinstance(revoked, dict) or self._hash_token(token) in revoked:
                return False, expiry
            return True, expiry
        except (ValueError, TypeError, OverflowError):
            return False, 0

    def verify_session(self, token: str) -> bool:
        now = time.time()
        with self._state_lock(reload=True):
            pruned = self._prune_revoked_sessions_locked(now)
            valid, _expiry = self._valid_session_locked(token, now)
            if pruned:
                self._save()
            return valid

    def revoke_session(self, token: str) -> bool:
        """在同一临界区内验证并吊销当前会话，记录只保留到原始到期时间。"""
        now = time.time()
        with self._state_lock(reload=True):
            changed = self._prune_revoked_sessions_locked(now)
            valid, expiry = self._valid_session_locked(token, now)
            if not valid:
                if changed:
                    self._save()
                return False
            self._data["revoked_sessions"][self._hash_token(token)] = expiry
            self._save()
            return True

    def _sign(self, body: str) -> str:
        secret = str(self._data.get("secret", ""))
        return hmac.new(secret.encode("utf-8"), body.encode("utf-8"), hashlib.sha256).hexdigest()

    # ---- 设备令牌 ----
    def issue_device_token(self, device_id: str, name: str = "") -> str:
        """颁发设备令牌（只存哈希，明文仅返回一次）。"""
        token = secrets.token_urlsafe(32)
        with self._state_lock(reload=True):
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
        with self._state_lock(reload=True):
            entry = self._data.get("device_tokens", {}).get(key)
            if entry is None:
                return False
            now = time.time()
            if now - entry.get("last_used", 0) > 60:  # 限频写入
                entry["last_used"] = now
                self._save()
            return True

    def revoke_device_token(self, token: str) -> bool:
        """吊销一个设备令牌（登出当前 App 用）。"""
        if not token:
            return False
        with self._state_lock(reload=True):
            removed = self._data.get("device_tokens", {}).pop(self._hash_token(token), None)
            if removed is not None:
                self._save()
            return removed is not None

    def revoke_device(self, device_id: str) -> int:
        """吊销某设备全部令牌，返回吊销数量。"""
        with self._state_lock(reload=True):
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

    # ---- 配对码（扫码即授权） ----
    def issue_pairing(self, ttl: int = PAIRING_TTL_SECONDS) -> dict[str, Any]:
        """已登录 web 生成配对码（二维码携带），返回 {code, expires_in}。"""
        code = secrets.token_urlsafe(24)
        with self._state_lock(reload=True):
            self._prune_pairings_locked(time.time())
            now = time.time()
            self._data.setdefault("pairings", {})[self._hash_token(code)] = {
                "created_at": now,
                "expires_at": now + ttl,
                "used_at": None,
                "device_id": None,
            }
            self._save()
        return {"code": code, "expires_in": ttl}

    def exchange_pairing(self, code: str, device_id: str, name: str = "") -> str:
        """App 扫码兑换：一次性、限时、重扫吊销旧令牌。返回设备令牌。

        失败抛 ValueError（消息面向 App 展示）：
        - "配对码无效或已过期"
        - "配对码已被使用"
        """
        token = secrets.token_urlsafe(32)
        key = self._hash_token(code)
        with self._state_lock(reload=True):
            self._prune_pairings_locked(time.time())
            entry = self._data.get("pairings", {}).get(key)
            if entry is None:
                raise ValueError("配对码无效或已过期")
            if entry.get("used_at") is not None:
                raise ValueError("配对码已被使用")
            if entry.get("expires_at", 0) < time.time():
                raise ValueError("配对码无效或已过期")
            now = time.time()
            entry["used_at"] = now
            entry["device_id"] = device_id
            # 重扫 = 换新令牌并吊销旧令牌；配对消费与新令牌同一事务发布。
            tokens = self._data.setdefault("device_tokens", {})
            stale = [k for k, v in tokens.items() if v.get("device_id") == device_id]
            for stale_key in stale:
                del tokens[stale_key]
            tokens[self._hash_token(token)] = {
                "device_id": device_id,
                "name": name,
                "created_at": now,
                "last_used": now,
            }
            self._save()
        return token

    def _prune_pairings_locked(self, now: float) -> bool:
        """在已持有 state lock 时清理过期/超限配对码。"""
        pairings = self._data.get("pairings", {})
        fresh = {k: v for k, v in pairings.items() if v.get("expires_at", 0) >= now}
        unused = {k: v for k, v in fresh.items() if v.get("used_at") is None}
        if len(unused) > PAIRING_MAX_OUTSTANDING:
            keep = sorted(
                unused.items(), key=lambda kv: kv[1].get("created_at", 0), reverse=True,
            )
            drop = {k for k, _ in keep[PAIRING_MAX_OUTSTANDING:]}
            fresh = {k: v for k, v in fresh.items() if k not in drop}
        if len(fresh) != len(pairings):
            self._data["pairings"] = fresh
            return True
        return False

    def _prune_pairings(self) -> None:
        """清理过期配对码；保留已使用码直到过期以便报告重放。"""
        with self._state_lock(reload=True):
            if self._prune_pairings_locked(time.time()):
                self._save()

    # ---- 限速（登录/设密/兑换，内存级，单 worker 部署） ----
    def check_rate(self, key: str, limit: int = RATE_LIMIT, window: float = RATE_WINDOW) -> bool:
        """返回 True 表示放行，False 表示超限。

        内存态约束：仅适用于单 worker 部署（deploy/agent-drive.service 即单进程）；
        同时清理过期 key，防止攻击者伪造 IP 让 _rate 无限增长。
        """
        now = time.time()
        with self._lock:
            if len(self._rate) > 1000:  # 兜底：整体清扫过期条目（防伪造 IP 撑爆内存）
                self._rate = {k: [t for t in v if now - t < window] for k, v in self._rate.items()}
                self._rate = {k: v for k, v in self._rate.items() if v}
            hits = [t for t in self._rate.get(key, []) if now - t < window]
            if not hits:
                self._rate.pop(key, None)  # 过期 key 即时清理
            if len(hits) >= limit:
                self._rate[key] = hits
                return False
            hits.append(now)
            self._rate[key] = hits
            return True
