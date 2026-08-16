"""上传去重索引单测：登记/命中/陈旧清理/覆盖/遗忘。"""
from __future__ import annotations

from app.storage.upload_index import UploadIndex


class _FakeStorage:
    """最小 storage 替身：按路径集合判断存在性。"""

    def __init__(self):
        self.files: set[str] = set()
        self.revisions: dict[str, str] = {}

    def exists(self, rel_path: str) -> bool:
        return rel_path in self.files

    def current_revision(self, rel_path: str) -> str | None:
        return self.revisions.get(rel_path)


def test_record_and_lookup(tmp_path):
    st = _FakeStorage()
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    idx.record("abc123", "相册同步/2026-08-14/a.jpg", 100)
    st.files.add("相册同步/2026-08-14/a.jpg")
    hit = idx.lookup("abc123")
    assert hit is not None and hit["path"].endswith("a.jpg") and hit["size"] == 100
    assert idx.lookup("missing") is None


def test_verified_only_rejects_legacy_client_declared_entries(tmp_path):
    st = _FakeStorage()
    st.files.add("legacy.jpg")
    st.revisions["legacy.jpg"] = "verified-v1"
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    idx.record("abc123", "legacy.jpg", 100, verified=False)
    assert idx.lookup("abc123") is not None
    assert idx.lookup("abc123", verified_only=True) is None
    assert idx.record("abc123", "legacy.jpg", 100, verified=True, revision="verified-v1") is True
    assert idx.lookup("abc123", verified_only=True) is not None


def test_revision_mismatch_rejects_record_and_invalidates_lookup(tmp_path):
    st = _FakeStorage()
    st.files.add("a.jpg")
    st.revisions["a.jpg"] = "v2"
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    assert idx.record("old", "a.jpg", 10, revision="v1") is False
    assert idx.lookup("old", verified_only=True) is None
    assert idx.record("new", "a.jpg", 10, revision="v2") is True
    st.revisions["a.jpg"] = "v3"
    assert idx.lookup("new", verified_only=True) is None


def test_two_instances_reload_before_writing(tmp_path):
    st = _FakeStorage()
    st.files.update({"a.jpg", "b.jpg"})
    path = tmp_path / "index.json"
    first = UploadIndex(path, storage=st)
    second = UploadIndex(path, storage=st)
    first.record("md5-a", "a.jpg", 1)
    second.record("md5-b", "b.jpg", 1)
    reloaded = UploadIndex(path, storage=st)
    assert reloaded.lookup("md5-a") is not None
    assert reloaded.lookup("md5-b") is not None


def test_stale_entry_cleaned(tmp_path):
    st = _FakeStorage()
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    idx.record("abc123", "a.jpg", 100)
    # 文件没登记（已被外部删除）→ 命中即清理
    assert idx.lookup("abc123") is None
    # 清理已持久化
    idx2 = UploadIndex(tmp_path / "index.json", storage=st)
    assert idx2.lookup("abc123") is None


def test_overwrite_updates_index(tmp_path):
    st = _FakeStorage()
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    idx.record("old-md5", "a.jpg", 100)
    idx.record("new-md5", "a.jpg", 200)  # 同路径覆盖
    st.files.add("a.jpg")  # 文件实际存在
    assert idx.lookup("old-md5") is None  # 旧内容索引已清
    hit = idx.lookup("new-md5")
    assert hit is not None and hit["size"] == 200


def test_forget_path(tmp_path):
    st = _FakeStorage()
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    idx.record("abc123", "a.jpg", 100)
    idx.forget_path("a.jpg")
    assert idx.lookup("abc123") is None


def test_forget_directory_recursively(tmp_path):
    st = _FakeStorage()
    st.files.update({"dir/a.jpg", "dir/sub/b.jpg", "other.jpg"})
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    idx.record("md5-a", "dir/a.jpg", 1)
    idx.record("md5-b", "dir/sub/b.jpg", 1)
    idx.record("md5-other", "other.jpg", 1)
    idx.forget_path("dir", recursive=True)
    assert idx.lookup("md5-a") is None
    assert idx.lookup("md5-b") is None
    assert idx.lookup("md5-other") is not None


def test_record_same_md5_other_path_cleanup(tmp_path):
    """同一内容并发登记到两个路径：只保留最新，旧路径条目清理。"""
    st = _FakeStorage()
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    idx.record("abc123", "a.jpg", 100)
    idx.record("abc123", "b.jpg", 100)
    st.files.add("b.jpg")
    hit = idx.lookup("abc123")
    assert hit is not None and hit["path"] == "b.jpg"
    # 持久化后再读：无旧路径歧义
    idx2 = UploadIndex(tmp_path / "index.json", storage=st)
    assert idx2.lookup("abc123")["path"] == "b.jpg"