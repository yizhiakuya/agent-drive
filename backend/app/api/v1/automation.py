"""自动化汇报 API（M3 主动服务：打开网盘 Agent 主动汇报）"""
from __future__ import annotations

from fastapi import APIRouter, Depends

from ..deps import get_container

router = APIRouter(prefix="/automation", tags=["automation"])


@router.get("/latest")
async def latest(container=Depends(get_container)):
    """最近一次规则自动执行的报告（前端空会话时主动展示）"""
    last_run = container.scheduler.last_run
    report = None
    try:
        notes = container.storage.resolve("Agent/notes")
        reports = sorted(notes.glob("自动化报告-*.md"), reverse=True)
        if reports:
            report = {
                "date": reports[0].stem.replace("自动化报告-", ""),
                "text": reports[0].read_text()[:2000],
            }
    except Exception:
        pass
    return {"last_run": last_run or None, "report": report}
