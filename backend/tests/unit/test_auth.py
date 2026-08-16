"""认证存储单测：持久化/设密/验密/会话令牌/设备令牌/限速。"""
from __future__ import annotations

import os
import stat

import pytest
from starlette.requests import Request

from app.api.v1.auth import _client_ip
from app.auth.store import AuthStore, AuthStoreLoadError


def test_auth_file_is_secure_and_stably_formatted(tmp_path):
    path = tmp_path / "auth.json"
    AuthStore(path)

    assert path.read_bytes().endswith(b"\n")
    assert list(tmp_path.glob(".auth.json.*.tmp")) == []
    if os.name == "posix":
        assert stat.S_IMODE(path.stat().st_mode) == 0o600


@pytest.mark.parametrize("contents", [b"{broken", b"[]", b'"text"', b"\xff"])
def test_corrupt_auth_file_fails_closed_and_is_preserved(tmp_path, contents):
    path = tmp_path / "auth.json"
    path.write_bytes(contents)

    with pytest.raises(AuthStoreLoadError, match="拒绝按未初始化状态启动"):
        AuthStore(path)

    assert path.read_bytes() == contents


def test_corrupt_auth_collection_fails_closed(tmp_path):
    path = tmp_path / "auth.json"
    path.write_text('{"secret":"abc","revoked_sessions":[]}', encoding="utf-8")
    with pytest.raises(AuthStoreLoadError, match="revoked_sessions"):
        AuthStore(path)

    path.write_text('{"secret":"abc","device_tokens":{"bad":[]}}', encoding="utf-8")
    with pytest.raises(AuthStoreLoadError, match="device_tokens"):
        AuthStore(path)

    path.write_text(
        '{"secret":"abc","device_tokens":{"bad":{"last_used":"not-a-time"}}}',
        encoding="utf-8",
    )
    with pytest.raises(AuthStoreLoadError, match="设备或配对"):
        AuthStore(path)

    path.write_text(
        '{"secret":"abc","device_tokens":{"bad":{"created_at":NaN}}}',
        encoding="utf-8",
    )
    with pytest.raises(AuthStoreLoadError, match="设备或配对"):
        AuthStore(path)

    path.write_text(
        '{"secret":"abc","revoked_sessions":{"hash":"9999999999"}}',
        encoding="utf-8",
    )
    with pytest.raises(AuthStoreLoadError, match="revoked_sessions"):
        AuthStore(path)


def test_setup_and_verify(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    assert auth.is_initialized() is False
    auth.setup("hunter2-hunter2")
    assert auth.is_initialized() is True
    assert auth.verify_password("hunter2-hunter2") is True
    assert auth.verify_password("wrong-password") is False


def test_setup_twice_rejected(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    auth.setup("password-123")
    with pytest.raises(ValueError):
        auth.setup("another-pass-123")


def test_setup_requires_length(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    with pytest.raises(ValueError):
        auth.setup("short")


def test_session_token_roundtrip_and_tamper(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    token = auth.issue_session()
    assert auth.verify_session(token) is True
    # 篡改签名或载荷 → 拒绝
    assert auth.verify_session(token + "x") is False
    version, body, sig = token.split(".")
    forged = version + "." + body[:-2] + "AA" + "." + sig
    assert auth.verify_session(forged) is False
    assert auth.verify_session("not-a-token") is False
    assert auth.verify_session("s1.%%%%.bad") is False


def test_session_revocation_persists_until_expiry(tmp_path):
    path = tmp_path / "auth.json"
    auth = AuthStore(path)
    token = auth.issue_session()
    assert auth.revoke_session(token) is True
    assert auth.verify_session(token) is False
    assert AuthStore(path).verify_session(token) is False
    assert auth.revoke_session("not-a-token") is False


def test_multiple_store_instances_reload_before_mutation(tmp_path):
    path = tmp_path / "auth.json"
    first = AuthStore(path)
    second = AuthStore(path)
    assert first._lock is second._lock  # 无 fcntl 平台也必须共享同路径的进程锁。
    session = first.issue_session()
    device = second.issue_device_token("dev-1")
    assert first.revoke_session(session) is True
    # second instance must reload the revocation before its next write; neither state is lost.
    second.revoke_device_token(device)
    assert AuthStore(path).verify_session(session) is False
    assert AuthStore(path).verify_device_token(device) is False


def test_device_token_roundtrip_and_revoke(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    tok = auth.issue_device_token("dev-1", name="Xiaomi")
    assert auth.verify_device_token(tok) is True
    assert auth.verify_device_token("bogus") is False
    # 持久化重载后仍有效（只存哈希）
    auth2 = AuthStore(tmp_path / "auth.json")
    assert auth2.verify_device_token(tok) is True
    # 吊销
    assert auth2.revoke_device("dev-1") == 1
    assert auth2.verify_device_token(tok) is False


def test_forwarded_ip_only_trusted_from_loopback_proxy():
    def request(peer: str) -> Request:
        return Request({
            "type": "http",
            "method": "POST",
            "path": "/api/v1/auth/login",
            "headers": [(b"x-forwarded-for", b"spoofed, 198.51.100.9")],
            "client": (peer, 12345),
            "server": ("test", 80),
            "scheme": "http",
            "query_string": b"",
        })

    assert _client_ip(request("127.0.0.1")) == "198.51.100.9"
    assert _client_ip(request("203.0.113.7")) == "203.0.113.7"


def test_rate_limit(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    for i in range(5):
        assert auth.check_rate("1.2.3.4") is True
    assert auth.check_rate("1.2.3.4") is False  # 第 6 次超限
    assert auth.check_rate("5.6.7.8") is True  # 其他 IP 不受影响


def test_pairing_exchange_and_single_use(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    info = auth.issue_pairing()
    assert info["expires_in"] == 300
    # 兑换成功 → 设备令牌可用
    tok = auth.exchange_pairing(info["code"], "dev-1", name="Xiaomi")
    assert auth.verify_device_token(tok) is True
    # 二次兑换 → 已被使用
    import pytest as _pytest
    with _pytest.raises(ValueError, match="已被使用"):
        auth.exchange_pairing(info["code"], "dev-2")


def test_pairing_expired_and_invalid(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    expired = auth.issue_pairing(ttl=-1)  # 立即过期
    import pytest as _pytest
    with _pytest.raises(ValueError, match="无效或已过期"):
        auth.exchange_pairing(expired["code"], "dev-1")
    with _pytest.raises(ValueError, match="无效或已过期"):
        auth.exchange_pairing("bogus-code-123", "dev-1")


def test_pairing_repair_revokes_old_token(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    old = auth.issue_device_token("dev-1", name="old")
    info = auth.issue_pairing()
    new = auth.exchange_pairing(info["code"], "dev-1", name="new")
    assert auth.verify_device_token(new) is True
    assert auth.verify_device_token(old) is False  # 旧令牌已吊销


def test_rate_keys_pruned(tmp_path):
    """限速 key 生命周期：过期时间戳不累积；海量过期 key 被整体清扫。"""
    import time

    auth = AuthStore(tmp_path / "auth.json")
    auth._rate["old-ip"] = [time.time() - 120]  # 已过窗口
    assert auth.check_rate("old-ip") is True
    assert len(auth._rate["old-ip"]) == 1  # 过期时间戳被丢弃，只记新命中
    # 攻击者伪造大量 IP 场景：过期 key 不无限增长（1000 阈值触发清扫）
    for i in range(1001):
        auth._rate[f"stale-{i}"] = [time.time() - 120]
    auth._rate["active-ip"] = [time.time()]
    assert auth.check_rate("new-ip") is True
    assert len(auth._rate) <= 3  # 只剩 active-ip + new-ip（+old-ip 重入）
    assert "active-ip" in auth._rate


def test_pairing_max_outstanding(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    codes = [auth.issue_pairing()["code"] for _ in range(4)]
    # 第 4 个生成时最旧的第 1 个已被清理
    import pytest as _pytest
    with _pytest.raises(ValueError, match="无效或已过期"):
        auth.exchange_pairing(codes[0], "dev-1")
    # 后 3 个仍有效
    for c in codes[1:]:
        auth.exchange_pairing(c, "dev-1")  # 不抛错即有效
