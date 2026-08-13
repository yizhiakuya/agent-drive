"""v1 文件路由"""
from __future__ import annotations

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
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


@router.post("/upload")
async def upload(container=Depends(get_container), file: UploadFile = File(...), path: str = ""):
    st = container.storage
    data = await file.read()
    rel = f"{path.strip('/')}/{file.filename}".lstrip("/") if path else file.filename
    info = st.save_bytes(rel, data)
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


@router.post("/mkdir")
async def mkdir(container=Depends(get_container), path: str = ""):
    st = container.storage
    try:
        st.mkdir(path)
        return {"created": path}
    except Exception as e:
        raise HTTPException(400, str(e))
