"""文件工具集：Agent 管理用户知识资产。

每个工具按 API 文档标准编写 doc：
  用途 / 参数含义 / 输出格式 / 前置条件 / 错误情况
写操作带 Critic validator：幂等性 + 结果校验。
"""
from __future__ import annotations

import fnmatch
from typing import Any

from ...llm.base import ToolSpec
from ...storage.local import LocalStorage
from .registry import ToolRegistry


def register_file_tools(reg: ToolRegistry, storage: LocalStorage) -> None:
    # ============ 🟢 查询 ============
    async def list_files(path: str = "") -> list[dict[str, Any]]:
        return storage.list_dir(path)

    reg.register(
        ToolSpec(
            "list_files",
            "列出目录中的文件和文件夹",
            {"type": "object", "properties": {"path": {"type": "string", "description": "相对路径，空=根目录"}}},
            doc=(
                "用途：查看网盘目录内容。\n"
                "参数：path（可选）——目录相对路径，空字符串表示根目录。\n"
                "输出：JSON 数组，每项 {name, path, is_dir, size, mtime}。\n"
                "错误：路径不存在或不是目录时返回 {ok:false, error}。"
            ),
        ),
        list_files,
        group="files",
    )

    async def search_files(query: str, path: str = "") -> list[dict[str, Any]]:
        """M1: 按文件名模糊匹配（M2 升级为语义+全文搜索）"""
        matches = []

        def walk(d: str):
            for item in storage.list_dir(d):
                if item["is_dir"]:
                    walk(item["path"])
                elif query.lower() in item["name"].lower() or fnmatch.fnmatch(item["name"].lower(), f"*{query.lower()}*"):
                    matches.append(item)

        walk(path)
        return matches[:20]

    reg.register(
        ToolSpec(
            "search_files",
            "按文件名搜索文件",
            {"type": "object", "properties": {
                "query": {"type": "string", "description": "搜索关键词"},
                "path": {"type": "string", "description": "起始目录，空=全盘"},
            }, "required": ["query"]},
            doc=(
                "用途：根据文件名模糊查找文件（大小写不敏感）。\n"
                "参数：query（必填）搜索关键词；path（可选）限定搜索目录。\n"
                "输出：JSON 数组，最多 20 条 {name, path, is_dir, size, mtime}。\n"
                "注意：这是文件名搜索，不是内容搜索。"
            ),
        ),
        search_files,
        group="files",
    )

    async def read_file(path: str, max_chars: int = 4000) -> str:
        return storage.read_text(path, max_chars)

    reg.register(
        ToolSpec(
            "read_file",
            "读取文本文件内容",
            {"type": "object", "properties": {
                "path": {"type": "string", "description": "文件相对路径"},
                "max_chars": {"type": "integer", "description": "最多读取字符数，默认 4000"},
            }, "required": ["path"]},
            doc=(
                "用途：读取文本类文件（文档/代码/配置/日志）。\n"
                "参数：path（必填）；max_chars（可选，1-20000）。\n"
                "输出：文件前 max_chars 字符的文本。二进制文件返回提示信息。\n"
                "错误：文件不存在返回 {ok:false, error}。"
            ),
        ),
        read_file,
        group="files",
    )

    # ============ 🟡 低风险写 ============
    async def create_folder(path: str) -> dict[str, Any]:
        storage.mkdir(path)
        return {"created": path}

    def _validate_created(args: dict, result: Any) -> str | None:
        # Critic：幂等性验证 —— 创建后目录必须存在
        p = args.get("path", "")
        if not storage.exists(p) or not storage.resolve(p).is_dir():
            return f"目录创建后不存在: {p}"
        return None

    reg.register(
        ToolSpec(
            "create_folder",
            "创建文件夹（已存在则成功返回，幂等）",
            {"type": "object", "properties": {"path": {"type": "string", "description": "要创建的目录相对路径，可含多级"}}, "required": ["path"]},
            doc=(
                "用途：创建文件夹。若目录已存在则视为成功（幂等）。\n"
                "参数：path（必填）如 '项目/2026'，自动创建多级。\n"
                "输出：{created: path}。\n"
                "错误：路径越界返回 {ok:false, error}。"
            ),
        ),
        create_folder,
        level="yellow",
        validator=_validate_created,
        group="files",
    )

    async def rename_file(src: str, dst: str) -> dict[str, Any]:
        storage.rename(src, dst)
        return {"renamed": f"{src} → {dst}"}

    def _validate_renamed(args: dict, result: Any) -> str | None:
        # Critic：重命名后目标必须存在、源必须消失
        src, dst = args.get("src", ""), args.get("dst", "")
        if not storage.exists(dst):
            return f"重命名后目标不存在: {dst}"
        if storage.exists(src):
            return f"重命名后源仍存在: {src}"
        return None

    reg.register(
        ToolSpec(
            "rename_file",
            "重命名文件或文件夹",
            {"type": "object", "properties": {
                "src": {"type": "string", "description": "原路径"},
                "dst": {"type": "string", "description": "新路径（仅名称，不含父目录）"},
            }, "required": ["src", "dst"]},
            doc=(
                "用途：重命名文件或文件夹。\n"
                "参数：src（必填）原相对路径；dst（必填）新名称。\n"
                "输出：{renamed: 'src → dst'}。\n"
                "错误：源不存在或目标已存在返回 {ok:false, error}。"
            ),
        ),
        rename_file,
        level="yellow",
        validator=_validate_renamed,
        group="files",
    )

    async def move_file(src: str, dst_dir: str) -> dict[str, Any]:
        storage.move(src, dst_dir)
        return {"moved": f"{src} → {dst_dir}/"}

    def _validate_moved(args: dict, result: Any) -> str | None:
        src, dst_dir = args.get("src", ""), args.get("dst_dir", "")
        target = (storage.resolve(dst_dir) / storage.resolve(src).name).relative_to(storage.root).as_posix()
        if not storage.exists(target):
            return f"移动后目标不存在: {target}"
        if storage.exists(src):
            return f"移动后源仍存在: {src}"
        return None

    reg.register(
        ToolSpec(
            "move_file",
            "移动文件到目标目录",
            {"type": "object", "properties": {
                "src": {"type": "string", "description": "文件相对路径"},
                "dst_dir": {"type": "string", "description": "目标目录相对路径"},
            }, "required": ["src", "dst_dir"]},
            doc=(
                "用途：把文件移动到另一个目录（保持文件名）。\n"
                "参数：src（必填）；dst_dir（必填）目标目录。\n"
                "输出：{moved: 'src → dst/'}。\n"
                "错误：源不存在或目标目录不存在返回 {ok:false, error}。"
            ),
        ),
        move_file,
        level="yellow",
        validator=_validate_moved,
        group="files",
    )

    async def delete_file(path: str) -> dict[str, Any]:
        storage.delete(path)
        return {"deleted": path}

    def _validate_deleted(args: dict, result: Any) -> str | None:
        p = args.get("path", "")
        if storage.exists(p):
            return f"删除后仍存在: {p}"
        return None

    reg.register(
        ToolSpec(
            "delete_file",
            "永久删除文件或文件夹（高危操作，需用户确认）",
            {"type": "object", "properties": {"path": {"type": "string", "description": "要删除的文件/目录相对路径"}}, "required": ["path"]},
            doc=(
                "用途：永久删除文件或文件夹。⚠️ 不可恢复，执行前必须向用户确认。\n"
                "参数：path（必填）。\n"
                "输出：{deleted: path}。\n"
                "错误：路径不存在返回 {ok:false, error}。"
            ),
        ),
        delete_file,
        level="red",
        validator=_validate_deleted,
        group="files",
    )
