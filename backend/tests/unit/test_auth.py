"""认证存储单测：设密/验密/会话令牌/设备令牌/限速。"""
from __future__ import annotations

import pytest

from app.auth.store import AuthStore


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


def test_rate_limit(tmp_path):
    auth = AuthStore(tmp_path / "auth.json")
    for i in range(5):
        assert auth.check_rate("1.2.3.4") is True
    assert auth.check_rate("1.2.3.4") is False  # 第 6 次超限
    assert auth.check_rate("5.6.7.8") is True  # 其他 IP 不受影响