"""存储安全与索引联动单测：穿越/符号链接/原子写/独占写/索引失效。"""
from __future__ import annotations

import pytest

from app.storage.local import LocalStorage
from app.storage.upload_index import UploadIndex


@pytest.fixture
def storage(tmp_path):
    st = LocalStorage(tmp_path / "data")
    idx = UploadIndex(tmp_path / "upload-index.json", storage=st)
    st.attach_index(idx)
    return st, idx


def test_resolve_blocks_traversal(storage):
    st, _ = storage
    with pytest.raises(PermissionError):
        st.resolve("../../etc/passwd")
    with pytest.raises(PermissionError):
        st.resolve("../outside.txt")


def test_resolve_rejects_symlink(storage, tmp_path):
    st, _ = storage
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "secret.txt").write_text("secret", encoding="utf-8")
    link = st.root / "link"
    try:
        link.symlink_to(outside, target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("当前环境不支持创建符号链接")
    with pytest.raises(PermissionError):
        st.resolve("link/secret.txt")


def test_save_bytes_atomic_overwrite(storage):
    st, _ = storage
    st.save_bytes("a.txt", b"v1")
    st.save_bytes("a.txt", b"v2-new")
    assert st.read_text("a.txt") == "v2-new"
    assert not list(st.root.glob(".*.tmp"))  # 无残留临时文件


def test_save_bytes_exclusive(storage):
    st, _ = storage
    st.save_bytes("a.txt", b"v1", exclusive=True)
    with pytest.raises(FileExistsError):
        st.save_bytes("a.txt", b"v2", exclusive=True)
    assert st.read_text("a.txt") == "v1"


def test_overwrite_save_forgets_index(storage):
    st, idx = storage
    st.save_bytes("a.jpg", b"photo-v1")
    idx.record("md5-v1", "a.jpg", 9)
    assert idx.lookup("md5-v1") is not None
    st.save_bytes("a.jpg", b"photo-v2-longer")  # 覆盖写：旧 md5 条目失效
    assert idx.lookup("md5-v1") is None


def test_rename_forgets_index(storage):
    st, idx = storage
    st.save_bytes("a.jpg", b"x")
    idx.record("md5-x", "a.jpg", 1)
    st.rename("a.jpg", "b.jpg")
    assert idx.lookup("md5-x") is None


def test_move_forgets_index(storage):
    st, idx = storage
    st.save_bytes("a.jpg", b"x")
    idx.record("md5-x", "a.jpg", 1)
    st.mkdir("dir")
    st.move("a.jpg", "dir")
    assert idx.lookup("md5-x") is None


def test_move_to_trash_forgets_index(storage):
    st, idx = storage
    st.save_bytes("a.jpg", b"x")
    idx.record("md5-x", "a.jpg", 1)
    st.move_to_trash("a.jpg")
    assert idx.lookup("md5-x") is None


def test_write_text_uses_lf_newlines(storage):
    st, _ = storage
    st.write_text("note.md", "line1\nline2\n")
    raw = (st.root / "note.md").read_bytes()
    assert b"\r\n" not in raw
