"""存储安全与索引联动单测：穿越/符号链接/原子写/独占写/索引失效。"""
from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor

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


def test_internal_namespaces_are_not_public(storage):
    st, _ = storage
    for path in (
        ".index/secret.txt",
        ".trash/secret.txt",
        ".storage.lock",
        ".upload.guessed.tmp",
        ".copy.guessed.tmp/file.txt",
    ):
        with pytest.raises(PermissionError):
            st.resolve(path)
        with pytest.raises(PermissionError):
            st.save_bytes(path, b"x")


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
    with pytest.raises(PermissionError):
        st.list_dir("")


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


def test_publish_temp_does_not_report_failure_after_commit_fsync_error(storage, monkeypatch):
    st, _ = storage
    temp, stream = st.create_temp_file()
    with stream:
        stream.write(b"committed")
        stream.flush()
        os.fsync(stream.fileno())

    def fail_directory_fsync(_fd):
        raise OSError("simulated directory fsync failure")

    monkeypatch.setattr(os, "fsync", fail_directory_fsync)
    info = st.publish_temp("committed.txt", temp, exclusive=True)

    assert info["path"] == "committed.txt"
    assert info["size"] == len(b"committed")
    assert st.read_text("committed.txt") == "committed"
    assert not temp.exists()


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


def test_move_overwrite_type_mismatch_fails_cleanly(storage):
    """覆盖移动不允许文件↔目录混型，且非空目录整体覆盖报 409 语义，不落半程状态。"""
    st, _ = storage
    st.write_text("sub/a.txt", "file")
    st.write_text("a.txt/inside.txt", "dir")
    with pytest.raises(IsADirectoryError):
        st.move("sub/a.txt", "", overwrite=True)
    assert st.read_text("sub/a.txt") == "file"
    assert st.read_text("a.txt/inside.txt") == "dir"

    st.write_text("b", "file")
    st.write_text("sub/b/c.txt", "x")
    with pytest.raises(NotADirectoryError):
        st.move("sub/b", "", overwrite=True)
    assert st.read_text("b") == "file"
    assert st.read_text("sub/b/c.txt") == "x"

    st.write_text("sub/d1/x.txt", "1")
    st.write_text("d1/y.txt", "2")
    with pytest.raises(FileExistsError):
        st.move("sub/d1", "", overwrite=True)
    assert st.read_text("sub/d1/x.txt") == "1"
    assert st.read_text("d1/y.txt") == "2"


def test_write_text_and_append_are_atomic_lf(storage):
    st, _ = storage
    st.write_text("note.md", "line1\nline2\n")
    first_inode = (st.root / "note.md").stat().st_ino
    st.write_text("note.md", "new\n")
    second_inode = (st.root / "note.md").stat().st_ino
    st.append_text("note.md", "tail\n")
    raw = (st.root / "note.md").read_bytes()
    assert raw == b"new\ntail\n"
    assert b"\r\n" not in raw
    assert second_inode != first_inode
    assert not list(st.root.glob(".upload.*.tmp"))


def test_concurrent_append_does_not_lose_entries(storage):
    st, _ = storage
    st.write_text("concurrent.log", "")
    lines = [f"line-{i}\n" for i in range(20)]
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda line: st.append_text("concurrent.log", line), lines))
    assert sorted(st.read_text("concurrent.log").splitlines()) == sorted(line.strip() for line in lines)


def test_stale_copy_staging_cleanup_is_conservative(tmp_path):
    root = tmp_path / "data"
    root.mkdir()
    stale = root / ".copy.crashed.tmp"
    stale.mkdir()
    (stale / "partial.txt").write_text("partial", encoding="utf-8")
    backup = root / ".copy-old.unmarked.tmp"
    backup.mkdir()
    (backup / "old.txt").write_text("old", encoding="utf-8")
    LocalStorage(root)
    assert not stale.exists()
    assert backup.exists()  # 无事务 marker 时无法证明旧备份可删，必须保留。


def test_copy_transaction_recovery_restores_old_destination(tmp_path):
    root = tmp_path / "data"
    root.mkdir()
    stage = root / ".copy.crashed.tmp"
    stage.mkdir()
    (stage / "new.txt").write_text("new", encoding="utf-8")
    backup = root / ".copy-old.crashed.tmp"
    backup.mkdir()
    (backup / "old.txt").write_text("old", encoding="utf-8")
    marker = root / ".copy.crashed.txn.json"
    marker.write_text(
        '{"stage":".copy.crashed.tmp","backup":".copy-old.crashed.tmp","destination":"dest"}',
        encoding="utf-8",
    )
    storage = LocalStorage(root)
    assert storage.read_text("dest/old.txt") == "old"
    assert not stage.exists()
    assert not backup.exists()
    assert not marker.exists()


def test_copy_transaction_recovery_cleans_committed_backup(tmp_path):
    root = tmp_path / "data"
    destination = root / "dest"
    destination.mkdir(parents=True)
    (destination / "new.txt").write_text("new", encoding="utf-8")
    backup = root / ".copy-old.crashed.tmp"
    backup.mkdir()
    (backup / "old.txt").write_text("old", encoding="utf-8")
    marker = root / ".copy.crashed.txn.json"
    marker.write_text(
        '{"stage":".copy.crashed.tmp","backup":".copy-old.crashed.tmp","destination":"dest"}',
        encoding="utf-8",
    )
    storage = LocalStorage(root)
    assert storage.read_text("dest/new.txt") == "new"
    assert not backup.exists()
    assert not marker.exists()


def test_copy_file_and_directory_overwrite_are_atomic(storage):
    st, _ = storage
    st.save_bytes("src.txt", b"new")
    st.save_bytes("dst.txt", b"old")
    st.copy("src.txt", "dst.txt", overwrite=True)
    assert st.read_text("dst.txt") == "new"

    st.save_bytes("tree/a.txt", b"a")
    st.save_bytes("tree/sub/b.txt", b"b")
    st.save_bytes("copy/a.txt", b"old-a")
    st.save_bytes("copy/stale.txt", b"must-disappear")
    st.copy("tree", "copy", overwrite=True)
    assert st.read_text("copy/a.txt") == "a"
    assert st.read_text("copy/sub/b.txt") == "b"
    assert not st.exists("copy/stale.txt")
    assert not list(st.root.glob(".upload.*.tmp"))


def test_copy_rejects_symlink_inside_source(storage, tmp_path):
    st, _ = storage
    st.mkdir("tree")
    st.save_bytes("copied/keep.txt", b"unchanged")
    outside = tmp_path / "outside.txt"
    outside.write_text("secret", encoding="utf-8")
    link = st.root / "tree" / "link.txt"
    try:
        link.symlink_to(outside)
    except (OSError, NotImplementedError):
        pytest.skip("当前环境不支持创建符号链接")
    with pytest.raises(PermissionError):
        st.copy("tree", "copied", overwrite=True)
    assert not (st.root / "copied" / "link.txt").exists()
    assert st.read_text("copied/keep.txt") == "unchanged"
    assert not list(st.root.glob(".copy.*.tmp"))


def test_trash_restore_and_purge_block_traversal(storage):
    st, _ = storage
    st.save_bytes("a.txt", b"x")
    st.move_to_trash("a.txt")
    with pytest.raises(PermissionError):
        st.restore_from_trash("../outside.txt")
    with pytest.raises(PermissionError):
        st.purge_trash("../../outside.txt")
    assert st.restore_from_trash("a.txt") == {"restored": "a.txt"}
    assert st.read_text("a.txt") == "x"


def test_trash_preserves_multiple_versions_of_same_path(storage):
    st, _ = storage
    st.save_bytes("same.txt", b"first")
    first = st.move_to_trash("same.txt")
    st.save_bytes("same.txt", b"second")
    second = st.move_to_trash("same.txt")
    assert first["trash_id"] != second["trash_id"]
    assert len(st.list_trash()) == 2

    assert st.restore_from_trash(second["trash_id"]) == {"restored": "same.txt"}
    assert st.read_text("same.txt") == "second"
    st.delete("same.txt")
    assert st.restore_from_trash(first["trash_id"]) == {"restored": "same.txt"}
    assert st.read_text("same.txt") == "first"


def test_trash_rejects_symlink_entry(storage, tmp_path):
    st, _ = storage
    outside = tmp_path / "outside.txt"
    outside.write_text("secret", encoding="utf-8")
    st.trash_root.mkdir()
    link = st.trash_root / "link.txt"
    try:
        link.symlink_to(outside)
    except (OSError, NotImplementedError):
        pytest.skip("当前环境不支持创建符号链接")
    with pytest.raises(PermissionError):
        st.restore_from_trash("link.txt")
    with pytest.raises(PermissionError):
        st.purge_trash("link.txt")
    assert outside.read_text(encoding="utf-8") == "secret"
