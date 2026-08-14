"""摄入管线（M2a）：上传文件 → 内容提取 → 全文索引。

支持类型:
- 文本类(.txt/.md/.py/.json/.yaml/.csv/.html...) 直接读
- PDF(PyMuPDF) 逐页提取
- 图片(.png/.jpg/.jpeg/.gif/.bmp/.webp) OCR(tesseract, chi_sim+eng)
- 其他二进制: 标记为不可解析

索引: storage 根的 .index/ 目录（隐藏，文件列表不显示）
  .index/{rel_path}.txt   提取的全文
  .index/{rel_path}.meta.json  提取元数据(类型/方法/字符数/时间)
"""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

from ..storage.local import LocalStorage

TEXT_SUFFIXES = (
    ".txt", ".md", ".py", ".js", ".ts", ".jsx", ".tsx", ".json", ".yaml", ".yml",
    ".toml", ".csv", ".html", ".css", ".xml", ".log", ".sh", ".ini", ".conf",
)
PDF_SUFFIXES = (".pdf",)
IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tiff")


class IngestPipeline:
    def __init__(self, storage: LocalStorage, embedder=None):
        self.storage = storage
        self.embedder = embedder  # EmbeddingProvider | None（None 时仅全文搜索）
        self.index_dir = storage.resolve(".index")
        self.index_dir.mkdir(parents=True, exist_ok=True)

    # ---------- 索引路径 ----------
    def _index_paths(self, rel_path: str) -> tuple[Path, Path]:
        txt = self.index_dir / f"{rel_path}.txt"
        meta = self.index_dir / f"{rel_path}.meta.json"
        txt.parent.mkdir(parents=True, exist_ok=True)
        return txt, meta

    # ---------- 提取 ----------
    def extract(self, rel_path: str) -> dict[str, Any]:
        """提取文件内容并写入索引。返回元数据。"""
        p = self.storage.resolve(rel_path)
        if not p.is_file():
            raise FileNotFoundError(rel_path)
        suffix = p.suffix.lower()
        t0 = time.time()

        if suffix in PDF_SUFFIXES:
            text, method = self._extract_pdf(p)
        elif suffix in IMAGE_SUFFIXES:
            text, method = self._extract_image(p)
        elif suffix in TEXT_SUFFIXES or suffix == "":
            text, method = self.storage.read_text(rel_path, max_chars=200_000), "text"
        else:
            text, method = "", "unsupported"

        meta = {
            "path": rel_path,
            "type": suffix or "(无扩展名)",
            "method": method,
            "chars": len(text),
            "extracted_at": time.time(),
            "elapsed_ms": int((time.time() - t0) * 1000),
        }
        txt_path, meta_path = self._index_paths(rel_path)
        txt_path.write_text(text, encoding="utf-8")
        meta_path.write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
        return meta

    # ---------- 各类型解析器 ----------
    @staticmethod
    def _extract_pdf(path: Path) -> tuple[str, str]:
        import pymupdf as fitz  # PyMuPDF（新 API，fitz 已弃用）

        doc = fitz.open(str(path))
        try:
            parts = [page.get_text() for page in doc]
            return "\n".join(parts)[:200_000], "pdf"
        finally:
            doc.close()

    @staticmethod
    def _extract_image(path: Path) -> tuple[str, str]:
        import pytesseract
        from PIL import Image

        img = Image.open(str(path))
        text = pytesseract.image_to_string(img, lang="chi_sim+eng")
        return text[:50_000], "ocr"

    # ---------- 读取索引 ----------
    def get_text(self, rel_path: str, max_chars: int = 8000) -> str | None:
        txt_path = self._index_paths(rel_path)[0]
        if not txt_path.exists():
            return None
        return txt_path.read_text(encoding="utf-8")[:max_chars]

    def get_meta(self, rel_path: str) -> dict[str, Any] | None:
        meta_path = self._index_paths(rel_path)[1]
        if not meta_path.exists():
            return None
        try:
            return json.loads(meta_path.read_text(encoding="utf-8"))
        except Exception:
            return None

    # ---------- 全文搜索（M2a 简化版，M2b 升级向量） ----------
    def search(self, query: str, limit: int = 10) -> list[dict[str, Any]]:
        """在全部索引文本中做大小写不敏感子串搜索。"""
        results = []
        for txt_path in self.index_dir.rglob("*.txt"):
            try:
                text = txt_path.read_text(encoding="utf-8")
            except Exception:
                continue
            q = query.lower()
            idx = text.lower().find(q)
            if idx < 0:
                continue
            rel = txt_path.relative_to(self.index_dir).as_posix()[:-4]  # 去掉 .txt
            snippet_start = max(0, idx - 40)
            snippet = text[snippet_start:snippet_start + 160].replace("\n", " ")
            meta = self.get_meta(rel) or {}
            results.append({
                "path": rel,
                "type": meta.get("type", ""),
                "method": meta.get("method", ""),
                "snippet": f"...{snippet}...",
            })
            if len(results) >= limit:
                break
        return results

    def stats(self) -> dict[str, Any]:
        """索引统计"""
        metas = list(self.index_dir.rglob("*.meta.json"))
        total_chars = 0
        by_method: dict[str, int] = {}
        for m in metas:
            try:
                meta = json.loads(m.read_text(encoding="utf-8"))
                total_chars += meta.get("chars", 0)
                method = meta.get("method", "unknown")
                by_method[method] = by_method.get(method, 0) + 1
            except Exception:
                continue
        return {"indexed_files": len(metas), "total_chars": total_chars, "by_method": by_method}


    # ---------- 向量化（M2b：Jina 云 embedding） ----------
    async def embed_file(self, rel_path: str) -> dict[str, Any]:
        """文件文本分块向量化，存 .index/{rel_path}.npy。返回 {chunks, dim}"""
        import numpy as np

        if self.embedder is None:
            return {"ok": False, "error": "未配置 embedding（agent-config.json 缺 embeddings 节）"}
        text = self.get_text(rel_path, max_chars=200_000)
        if not text:
            self.extract(rel_path)
            text = self.get_text(rel_path, max_chars=200_000) or ""
        if not text.strip():
            return {"ok": False, "error": "文件无可提取文本"}

        # 分块（每块 ≤2000 字符，适配 8K token 模型限制）
        chunks = [text[i:i + 2000] for i in range(0, len(text), 2000)][:50]
        vectors = await self.embedder.embed(chunks)
        np_path = self.index_dir / f"{rel_path}.npy"
        np_path.parent.mkdir(parents=True, exist_ok=True)
        np.save(np_path, np.array(vectors, dtype=np.float32))
        return {"ok": True, "path": rel_path, "chunks": len(chunks), "dim": len(vectors[0])}

    async def semantic_search(self, query: str, limit: int = 5) -> list[dict[str, Any]]:
        """语义搜索：query 向量化 → 与所有已向量化文件余弦相似度 top-k"""
        import numpy as np

        if self.embedder is None:
            return [{"error": "未配置 embedding"}]
        qv = (await self.embedder.embed([query]))[0]
        results = []
        for np_path in self.index_dir.rglob("*.npy"):
            try:
                vecs = np.load(np_path)
                qv_np = np.array(qv, dtype=np.float32)
                best = float(np.max(np.dot(vecs, qv_np) /
                                    (np.linalg.norm(vecs, axis=1) * np.linalg.norm(qv_np) + 1e-9)))
                rel = np_path.relative_to(self.index_dir).as_posix()[:-4]
                results.append({"path": rel, "score": round(best, 4)})
            except Exception:
                continue
        results.sort(key=lambda r: r["score"], reverse=True)
        return results[:limit]
