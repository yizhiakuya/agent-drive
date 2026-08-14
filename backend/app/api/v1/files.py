"""v1 文件路由"""
from __future__ import annotations

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse

from ..deps import get_container

router = APIRouter(prefix="/files", tags=["files"])


@router.get("")
async def list_files(container=Depends(get_container), path: str = ""):
    st = container.storage
    try:
        return {"path": path, "items": st.list_dir(path), "disk": st.disk_usage()}
    except Exception as e:
        raise HTTPException(400, str(e))


def _unique_path(st, rel: str) -> str:
    """同名冲突自动加序号：name.jpg → name-2.jpg → name-3.jpg …"""
    from pathlib import PurePosixPath

    p = PurePosixPath(rel)
    stem, suffix = p.stem, p.suffix
    candidate = rel
    i = 2
    while st.exists(candidate):
        candidate = str(p.with_name(f"{stem}-{i}{suffix}"))
        i += 1
    return candidate


@router.post("/upload")
async def upload(container=Depends(get_container), file: UploadFile = File(...),
                 path: str = "", md5: str = Form(""), noclobber: bool = Form(False)):
    """上传。path 走查询参数（web 同款）；md5/noclobber 为表单字段（相册同步专用）。

    - md5：内容去重（秒传）——命中且文件仍在则跳过传输与索引
    - noclobber：同名冲突自动加序号（不覆盖）；web 上传不传此字段，保持覆盖语义
    """
    st = container.storage
    filename = file.filename or "未命名"
    rel = f"{path.strip('/')}/{filename}".lstrip("/") if path else filename

    # 秒传：内容去重命中 → 直接返回已有文件
    if md5:
        hit = container.upload_index.lookup(md5)
        if hit:
            return {"uploaded": {"path": hit["path"], "size": hit["size"], "deduped": True}, "indexed": None}

    if noclobber and st.exists(rel):
        rel = _unique_path(st, rel)

    data = await file.read()
    info = st.save_bytes(rel, data)
    if md5:
        container.upload_index.record(md5, rel, info["size"])
    # M2a：上传即解析（尽力而为，失败不影响上传）
    indexed = None
    try:
        indexed = container.ingest.extract(rel)
    except Exception:
        pass
    return {"uploaded": info, "indexed": indexed}


@router.get("/download")
async def download(container=Depends(get_container), path: str = ""):
    st = container.storage
    try:
        p = st.resolve(path)
        if not p.is_file():
            raise HTTPException(404, "文件不存在")
        return FileResponse(p, filename=p.name)
    except PermissionError:
        raise HTTPException(403, "路径越界")
    except Exception as e:
        raise HTTPException(400, str(e))


@router.post("/upload-share")
async def upload_share(container=Depends(get_container), file: UploadFile = File(...)):
    """Web Share Target 入口：分享的文件存到根目录，303 回前端首页"""
    from fastapi.responses import RedirectResponse

    st = container.storage
    data = await file.read()
    # 同名处理：自动加序号
    rel = file.filename or "分享的文件"
    base, ext = (rel.rsplit(".", 1) + [""])[:2] if "." in rel else (rel, "")
    candidate, i = rel, 1
    while st.exists(candidate):
        candidate = f"{base}-{i}.{ext}" if ext else f"{base}-{i}"
        i += 1
    st.save_bytes(candidate, data)
    try:
        container.ingest.extract(candidate)
    except Exception:
        pass
    return RedirectResponse(url=f"/?shared={candidate}", status_code=303)


@router.post("/mkdir")
async def mkdir(container=Depends(get_container), path: str = ""):
    st = container.storage
    try:
        st.mkdir(path)
        return {"created": path}
    except Exception as e:
        raise HTTPException(400, str(e))


# ---- 业务页面补充：预览 + 信息 ----

TEXT_PREVIEW_SUFFIXES = (".txt", ".md", ".py", ".js", ".ts", ".jsx", ".tsx", ".json", ".yaml",
                         ".yml", ".toml", ".csv", ".html", ".css", ".xml", ".log", ".sh", ".ini", ".conf")
IMAGE_PREVIEW_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")
VIDEO_PREVIEW_SUFFIXES = (".mp4", ".webm", ".ogg", ".mov", ".m4v")
AUDIO_PREVIEW_SUFFIXES = (".mp3", ".wav", ".ogg", ".m4a", ".flac")
PDF_SUFFIX = ".pdf"


@router.get("/raw")
async def raw(container=Depends(get_container), path: str = ""):
    """预览：返回文件原始内容（前端按类型渲染 text/img/iframe）"""
    st = container.storage
    try:
        p_ = st.resolve(path)
        if not p_.is_file():
            raise HTTPException(404, "文件不存在")
        suffix = p_.suffix.lower()
        media_type = {
            ".pdf": "application/pdf", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
            ".gif": "image/gif", ".webp": "image/webp", ".bmp": "image/bmp",
            ".mp4": "video/mp4", ".webm": "video/webm", ".ogg": "video/ogg", ".mov": "video/quicktime", ".m4v": "video/mp4",
            ".mp3": "audio/mpeg", ".wav": "audio/wav", ".m4a": "audio/mp4", ".flac": "audio/flac",
        }.get(suffix, "application/octet-stream")
        return FileResponse(p_, media_type=media_type)
    except PermissionError:
        raise HTTPException(403, "路径越界")
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(400, str(e))


@router.get("/info")
async def info(container=Depends(get_container), path: str = ""):
    """文件信息 + 内容预览片段（PDF/图片自动走索引解析文本）"""
    st = container.storage
    try:
        p_ = st.resolve(path)
        if not p_.is_file():
            raise HTTPException(404, "文件不存在")
        suffix = p_.suffix.lower()
        stat = p_.stat()
        indexed_meta = None
        snippet = None
        if suffix in TEXT_PREVIEW_SUFFIXES or suffix == "":
            snippet = st.read_text(path, max_chars=4000)
        elif suffix in IMAGE_PREVIEW_SUFFIXES or suffix == PDF_SUFFIX:
            indexed_meta = container.ingest.get_meta(path)
            snippet = container.ingest.get_text(path, max_chars=4000)
        return {
            "path": path,
            "name": p_.name,
            "size": stat.st_size,
            "modified": stat.st_mtime,
            "preview_kind": "image" if suffix in IMAGE_PREVIEW_SUFFIXES
                           else "video" if suffix in VIDEO_PREVIEW_SUFFIXES
                           else "audio" if suffix in AUDIO_PREVIEW_SUFFIXES
                           else "pdf" if suffix == PDF_SUFFIX
                           else "text" if suffix in TEXT_PREVIEW_SUFFIXES or suffix == "" else "binary",
            "snippet": snippet,
            "indexed": indexed_meta,
        }
    except PermissionError:
        raise HTTPException(403, "路径越界")
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(400, str(e))


# ---- 文件操作（文件页人工入口） ----

@router.post("/rename")
async def rename(container=Depends(get_container), src: str = "", dst: str = ""):
    try:
        container.storage.rename(src, dst)
        return {"renamed": f"{src} → {dst}"}
    except Exception as e:
        raise HTTPException(400, str(e))


@router.post("/move")
async def move(container=Depends(get_container), src: str = "", dst_dir: str = "", overwrite: bool = False):
    try:
        container.storage.move(src, dst_dir, overwrite=overwrite)
        return {"moved": f"{src} → {dst_dir}/"}
    except Exception as e:
        raise HTTPException(400, str(e))


@router.post("/copy")
async def copy(container=Depends(get_container), src: str = "", dst: str = "", overwrite: bool = False):
    try:
        container.storage.copy(src, dst, overwrite=overwrite)
        return {"copied": f"{src} → {dst}"}
    except Exception as e:
        raise HTTPException(400, str(e))


@router.post("/delete")
async def delete(container=Depends(get_container), path: str = ""):
    """删除（移入回收站）"""
    try:
        return container.storage.move_to_trash(path)
    except Exception as e:
        raise HTTPException(400, str(e))


# ---- 回收站 ----

@router.get("/trash")
async def trash_list(container=Depends(get_container)):
    return {"items": container.storage.list_trash()}


@router.post("/trash/restore")
async def trash_restore(container=Depends(get_container), path: str = ""):
    try:
        return container.storage.restore_from_trash(path)
    except Exception as e:
        raise HTTPException(400, str(e))


@router.post("/trash/empty")
async def trash_empty(container=Depends(get_container)):
    try:
        return container.storage.purge_trash()
    except Exception as e:
        raise HTTPException(400, str(e))
