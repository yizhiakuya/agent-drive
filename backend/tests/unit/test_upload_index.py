"""上传去重索引单测：登记/命中/陈旧清理/覆盖/遗忘。"""
from __future__ import annotations

from app.storage.upload_index import UploadIndex


class _FakeStorage:
    """最小 storage 替身：按路径集合判断存在性。"""

    def __init__(self):
        self.files: set[str] = set()

    def exists(self, rel_path: str) -> bool:
        return rel_path in self.files


def test_record_and_lookup(tmp_path):
    st = _FakeStorage()
    idx = UploadIndex(tmp_path / "index.json", storage=st)
    idx.record("abc123", "相册同步/2026-08-14/a.jpg", 100)
    st.files.add("相册同步/2026-08-14/a.jpg")
    hit = idx.lookup("abc123")
    assert hit is not None and hit["path"].endswith("a.jpg") and hit["size"] == 100
    assert idx.lookup("missing") is None


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