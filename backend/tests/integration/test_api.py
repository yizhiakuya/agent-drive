"""API 集成测试（pytest 风格）：验证 v1 路由 + Container 组装。"""
from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

import app.main as main_module
from app.core.config import Settings
from app.core.container import Container
from app.main import create_app


@pytest.fixture
def client(tmp_path: Path):
    """用临时目录 + 测试容器启动应用（不污染真实数据），并完成设密+登录。"""
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        # 认证：首设密码 → 登录（cookie 自动保持，覆盖全部受保护端点）
        assert c.post("/api/v1/auth/setup", json={"password": "test-password-123"}).status_code == 200
        yield c, container


def test_unauthorized_without_login(tmp_path: Path):
    """未登录访问受保护端点 → 401。"""
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        assert c.get("/api/v1/files").status_code == 401
        assert c.get("/api/v1/status").status_code == 401
        assert c.post("/api/v1/chat", json={"message": "hi", "history": []}).status_code == 401


def test_auth_flow(tmp_path: Path):
    """完整认证流：未初始化 → 设密 → 登录 → 设备令牌 → 吊销。"""
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        assert c.get("/api/v1/auth/status").json()["initialized"] is False
        r = c.post("/api/v1/auth/setup", json={"password": "password-abc-123"})
        assert r.status_code == 200
        # 设置密码后直接已登录（cookie 已下发）
        assert c.get("/api/v1/auth/me").status_code == 200
        # 登出
        assert c.post("/api/v1/auth/logout").status_code == 200
        assert c.get("/api/v1/auth/me").status_code == 401
        # 错误密码 → 401；正确 → 200
        assert c.post("/api/v1/auth/login", json={"password": "wrong-password"}).status_code == 401
        assert c.post("/api/v1/auth/login", json={"password": "password-abc-123"}).status_code == 200
        # 颁发设备令牌 → Bearer 可用 → 吊销后失效
        tok = c.post("/api/v1/auth/device-token", json={"device_id": "dev-x"}).json()["token"]
        assert c.get("/api/v1/files", headers={"Authorization": f"Bearer {tok}"}).status_code == 200
        container.auth.revoke_device("dev-x")
        c.cookies.clear()  # 去掉登录 cookie，单独验证 Bearer 吊销生效
        assert c.get("/api/v1/files", headers={"Authorization": f"Bearer {tok}"}).status_code == 401


def test_pairing_flow(tmp_path: Path):
    """扫码配对流：web 登录 → 生成配对码 → App 无凭据兑换 → Bearer 可用。"""
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        c.post("/api/v1/auth/setup", json={"password": "password-abc-123"})
        # 未登录（无 cookie）不能生成配对码
        with TestClient(app) as anon:
            assert anon.post("/api/v1/auth/pairing").status_code == 401
        # 登录后生成
        info = c.post("/api/v1/auth/pairing").json()
        assert info["expires_in"] == 300
        # 新客户端（无任何凭据）兑换
        with TestClient(app) as phone:
            r = phone.post("/api/v1/auth/pair-exchange", json={
                "code": info["code"], "device_id": "phone-1", "name": "Xiaomi 14",
            })
            assert r.status_code == 200
            tok = r.json()["token"]
            # Bearer 全通
            assert phone.get("/api/v1/files", headers={"Authorization": f"Bearer {tok}"}).status_code == 200
            # 重放 → 已被使用
            r2 = phone.post("/api/v1/auth/pair-exchange", json={
                "code": info["code"], "device_id": "phone-2",
            })
            assert r2.status_code == 400
            assert "已被使用" in r2.json()["detail"]


def test_health_public(tmp_path: Path):
    """健康检查公开豁免：无任何凭据也返回 200（探活用）。"""
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        r = c.get("/api/v1/health")
        assert r.status_code == 200
        assert r.json()["ok"] is True


def test_upload_too_large_413(tmp_path: Path):
    """超过后端大小上限 → 413（直连 8000 的滥用兜底）。"""
    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
        max_upload_mb=1,
    )
    container = Container(settings)
    app = create_app(container)
    with TestClient(app) as c:
        c.post("/api/v1/auth/setup", json={"password": "password-abc-123"})
        big = b"x" * (1024 * 1024 + 10)
        r = c.post("/api/v1/files/upload", files={"file": ("big.bin", big, "application/octet-stream")})
        assert r.status_code == 413
        ok = c.post("/api/v1/files/upload", files={"file": ("small.bin", b"hi", "application/octet-stream")})
        assert ok.status_code == 200


def test_root_status(client):
    c, _ = client
    r = c.get("/")
    assert r.status_code == 200
    # SPA 部署模式（frontend/out 存在）返回 index.html；否则返回 JSON 根信息
    body = r.text
    assert "Agent Drive" in body or "<!doctype html>" in body.lower() or "app" in body.lower()


def test_status_not_configured(client):
    c, _ = client
    r = c.get("/api/v1/status")
    assert r.status_code == 200
    assert r.json()["configured"] is False


def test_upload_dedup_and_noclobber(client):
    """秒传（md5 去重）与同名冲突自动加序号。"""
    c, _ = client
    # 首次上传（带 md5 + noclobber）
    r = c.post("/api/v1/files/upload?path=相册同步/2026-08-14",
               files={"file": ("IMG_0001.jpg", b"photo-bytes", "image/jpeg")},
               data={"md5": "d41d8cd98f00b204e9800998ecf8427e", "noclobber": "true"})
    assert r.status_code == 200
    assert r.json()["uploaded"]["path"] == "相册同步/2026-08-14/IMG_0001.jpg"
    # 同内容再传（不同名）→ 秒传命中，不落新文件
    r2 = c.post("/api/v1/files/upload?path=相册同步/2026-08-14",
                files={"file": ("别的名字.jpg", b"photo-bytes", "image/jpeg")},
                data={"md5": "d41d8cd98f00b204e9800998ecf8427e"})
    assert r2.status_code == 200
    assert r2.json()["uploaded"].get("deduped") is True
    # 同名不同内容 + noclobber → 自动 -2 序号，不覆盖
    r3 = c.post("/api/v1/files/upload?path=相册同步/2026-08-14",
                files={"file": ("IMG_0001.jpg", b"other-bytes", "image/jpeg")},
                data={"md5": "aabbccdd11223344556677889900aabb", "noclobber": "true"})
    assert r3.status_code == 200
    assert r3.json()["uploaded"]["path"] == "相册同步/2026-08-14/IMG_0001-2.jpg"


def test_files_upload_and_list(client):
    c, _ = client
    # 上传
    r = c.post("/api/v1/files/upload", files={"file": ("测试.txt", b"hello", "text/plain")})
    assert r.status_code == 200
    # 列表
    r = c.get("/api/v1/files")
    assert r.status_code == 200
    items = r.json()["items"]
    assert any(i["name"] == "测试.txt" for i in items)


def test_error_semantics(client):
    """错误语义化：不存在→404，同名冲突→409，不再一律 400。"""
    c, _ = client
    assert c.get("/api/v1/files/info", params={"path": "不存在.txt"}).status_code == 404
    assert c.get("/api/v1/files/download", params={"path": "不存在.txt"}).status_code == 404
    c.post("/api/v1/files/upload", files={"file": ("a.txt", b"1", "text/plain")})
    c.post("/api/v1/files/upload", files={"file": ("b.txt", b"2", "text/plain")})
    r = c.post("/api/v1/files/move", params={"src": "b.txt", "dst_dir": ""})
    assert r.status_code == 409


def test_api_404_returns_json(client):
    """未匹配的 /api 路径返回 JSON 404（SPA fallback 不再吐 HTML）。"""
    c, _ = client
    r = c.get("/api/v1/definitely-not-exists")
    assert r.status_code == 404
    assert isinstance(r.json(), dict)


def test_spa_fallback_blocks_path_traversal(tmp_path: Path, monkeypatch):
    """静态资源回退不得通过编码路径逃出 frontend/out。"""
    dist = tmp_path / "frontend" / "out"
    dist.mkdir(parents=True)
    (dist / "index.html").write_text("<html>spa</html>", encoding="utf-8", newline="\n")
    (dist / "asset.txt").write_text("public", encoding="utf-8", newline="\n")
    (tmp_path / "secret.txt").write_text("private", encoding="utf-8", newline="\n")
    monkeypatch.setattr(main_module, "_DIST", dist)

    settings = Settings(
        app_env="test",
        backend_dir=tmp_path,
        system_dir=Path("system"),
        data_dir=Path("data"),
    )
    app = main_module.create_app(Container(settings))
    with TestClient(app) as c:
        assert c.get("/asset.txt").text == "public"
        assert c.get("/client/route").text == "<html>spa</html>"
        assert c.get("/api").status_code == 404
        assert c.get("/%2e%2e/%2e%2e/secret.txt").status_code == 404
        assert c.get("/..%2f..%2fsecret.txt").status_code == 404
        assert c.get("/..%5c..%5csecret.txt").status_code == 404


def test_files_path_traversal_blocked(client):
    c, _ = client
    r = c.get("/api/v1/files/download", params={"path": "../../../etc/passwd"})
    assert r.status_code == 403


def test_chat_requires_config(client):
    c, _ = client
    r = c.post("/api/v1/chat", json={"message": "你好", "history": []})
    assert r.status_code == 400  # 未配置 LLM
    assert "LLM" in r.json()["detail"] or "Onboarding" in r.json()["detail"]


def test_sessions_crud(client):
    c, container = client
    # 创建（通过 chat 自动创建需要 LLM；直接调 SessionStore）
    meta = container.sessions.create()
    sid = meta["id"]
    container.sessions.append(sid, {"role": "user", "content": "hi"})
    # 列表
    r = c.get("/api/v1/sessions")
    assert r.status_code == 200
    assert any(s["id"] == sid for s in r.json()["sessions"])
    # 详情
    r = c.get(f"/api/v1/sessions/{sid}")
    assert r.status_code == 200
    assert len(r.json()["messages"]) == 1
    # 删除
    r = c.delete(f"/api/v1/sessions/{sid}")
    assert r.status_code == 200
    r = c.get(f"/api/v1/sessions/{sid}")
    assert r.status_code == 404
