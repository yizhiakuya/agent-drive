"""File extraction, full-text indexing, and embedding sidecars.

Index files live under ``data/.index`` and are rebuildable. Every vector has a
source revision and embedding fingerprint so stale/model-mismatched data is
never returned by semantic search.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import shutil
import time
import uuid
from pathlib import Path
from typing import Any

from ..storage.local import LocalStorage

TEXT_SUFFIXES = (
    ".txt", ".md", ".py", ".js", ".ts", ".jsx", ".tsx", ".json", ".yaml", ".yml",
    ".toml", ".csv", ".html", ".css", ".xml", ".log", ".sh", ".ini", ".conf",
)
PDF_SUFFIXES = (".pdf",)
IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tiff")
INDEXABLE_SUFFIXES = frozenset(TEXT_SUFFIXES + PDF_SUFFIXES + IMAGE_SUFFIXES)
EXTRACTOR_VERSION = 2
CHUNK_VERSION = "chars-2000-v2"


class IngestPipeline:
    def __init__(self, storage: LocalStorage, embedder=None):
        self.storage = storage
        self.embedder = embedder
        self.index_dir = storage.resolve(".index")
        self.index_dir.mkdir(parents=True, exist_ok=True)

    def _safe_rel(self, rel_path: str) -> str:
        return self.storage.resolve(rel_path).relative_to(self.storage.root).as_posix()

    def _index_paths(self, rel_path: str) -> tuple[Path, Path]:
        rel = self._safe_rel(rel_path)
        txt = self.index_dir / f"{rel}.txt"
        meta = self.index_dir / f"{rel}.meta.json"
        return txt, meta

    def _vector_paths(self, rel_path: str) -> tuple[Path, Path]:
        rel = self._safe_rel(rel_path)
        vector = self.index_dir / f"{rel}.npy"
        meta = self.index_dir / f"{rel}.vector.json"
        return vector, meta

    @staticmethod
    def _atomic_text(path: Path, text: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex[:8]}.tmp")
        try:
            tmp.write_text(text, encoding="utf-8", newline="\n")
            os.replace(tmp, path)
        finally:
            tmp.unlink(missing_ok=True)

    @staticmethod
    def _atomic_vectors(path: Path, vectors: list[list[float]]) -> None:
        import numpy as np

        tmp = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex[:8]}.tmp")
        try:
            with open(tmp, "wb") as handle:
                np.save(handle, np.asarray(vectors, dtype=np.float32))
            os.replace(tmp, path)
        finally:
            tmp.unlink(missing_ok=True)

    def source_revision(self, rel_path: str) -> str:
        source = self.storage.resolve(rel_path)
        stat = source.stat()
        return f"{stat.st_size}:{stat.st_mtime_ns}"

    def is_indexable(self, rel_path: str) -> bool:
        source = self.storage.resolve(rel_path)
        return source.is_file() and (source.suffix.lower() in INDEXABLE_SUFFIXES or source.suffix == "")

    def iter_indexable_files(self, prefix: str = "") -> list[str]:
        root = self.storage.resolve(prefix)
        if root.is_file():
            rel = root.relative_to(self.storage.root).as_posix()
            return [rel] if self.is_indexable(rel) else []
        if not root.is_dir():
            return []
        result: list[str] = []
        for current, dirs, files in os.walk(root, followlinks=False):
            current_path = Path(current)
            dirs[:] = [
                name for name in dirs
                if name not in {".index", ".trash"} and not (current_path / name).is_symlink()
            ]
            for name in files:
                source = current_path / name
                if source.is_symlink():
                    continue
                rel = source.relative_to(self.storage.root).as_posix()
                if self.is_indexable(rel):
                    result.append(rel)
        return sorted(result)

    def extract(self, rel_path: str) -> dict[str, Any]:
        """Extract file content and atomically publish its full-text index."""
        source = self.storage.resolve(rel_path)
        if not source.is_file():
            raise FileNotFoundError(rel_path)
        revision = self.source_revision(rel_path)
        suffix = source.suffix.lower()
        started = time.time()

        if suffix in PDF_SUFFIXES:
            text, method = self._extract_pdf(source)
        elif suffix in IMAGE_SUFFIXES:
            text, method = self._extract_image(source)
        elif suffix in TEXT_SUFFIXES or suffix == "":
            text, method = self.storage.read_text(rel_path, max_chars=200_000), "text"
        else:
            text, method = "", "unsupported"

        meta = {
            "path": self._safe_rel(rel_path),
            "type": suffix or "(no extension)",
            "method": method,
            "chars": len(text),
            "source_revision": revision,
            "extractor_version": EXTRACTOR_VERSION,
            "extracted_at": time.time(),
            "elapsed_ms": int((time.time() - started) * 1000),
        }
        text_path, meta_path = self._index_paths(rel_path)
        self._atomic_text(text_path, text)
        self._atomic_text(meta_path, json.dumps(meta, ensure_ascii=False))
        return meta

    @staticmethod
    def _extract_pdf(path: Path) -> tuple[str, str]:
        import pymupdf as fitz

        doc = fitz.open(str(path))
        try:
            return "\n".join(page.get_text() for page in doc)[:200_000], "pdf"
        finally:
            doc.close()

    @staticmethod
    def _extract_image(path: Path) -> tuple[str, str]:
        import pytesseract
        from PIL import Image

        with Image.open(str(path)) as image:
            text = pytesseract.image_to_string(image, lang="chi_sim+eng")
        return text[:50_000], "ocr"

    def get_text(self, rel_path: str, max_chars: int = 8000) -> str | None:
        text_path, _ = self._index_paths(rel_path)
        if not text_path.exists() or not self.is_extract_current(rel_path):
            return None
        return text_path.read_text(encoding="utf-8")[:max_chars]

    def get_meta(self, rel_path: str) -> dict[str, Any] | None:
        meta_path = self._index_paths(rel_path)[1]
        if not meta_path.exists():
            return None
        try:
            return json.loads(meta_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def get_vector_meta(self, rel_path: str) -> dict[str, Any] | None:
        meta_path = self._vector_paths(rel_path)[1]
        if not meta_path.exists():
            return None
        try:
            return json.loads(meta_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def is_extract_current(self, rel_path: str) -> bool:
        try:
            text_path, _ = self._index_paths(rel_path)
            meta = self.get_meta(rel_path)
            return bool(
                text_path.is_file()
                and meta
                and meta.get("source_revision") == self.source_revision(rel_path)
                and meta.get("extractor_version") == EXTRACTOR_VERSION
            )
        except (FileNotFoundError, PermissionError, OSError):
            return False

    def embedding_fingerprint(self) -> str:
        if self.embedder is None:
            return ""
        raw = "|".join((
            self.embedder.__class__.__name__,
            str(getattr(self.embedder, "base_url", "")),
            str(getattr(self.embedder, "model", "")),
            CHUNK_VERSION,
        ))
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]

    def is_vector_current(self, rel_path: str) -> bool:
        try:
            vector_path, _ = self._vector_paths(rel_path)
            meta = self.get_vector_meta(rel_path)
            return bool(
                self.embedder is not None
                and vector_path.is_file()
                and meta
                and meta.get("source_revision") == self.source_revision(rel_path)
                and meta.get("embedding_fingerprint") == self.embedding_fingerprint()
                and meta.get("chunk_version") == CHUNK_VERSION
                and int(meta.get("dimensions", 0)) > 0
                and int(meta.get("chunks", 0)) > 0
            )
        except (FileNotFoundError, PermissionError, OSError, TypeError, ValueError):
            return False

    def is_index_current(self, rel_path: str, *, require_vector: bool) -> bool:
        if not self.is_extract_current(rel_path):
            return False
        meta = self.get_meta(rel_path) or {}
        if meta.get("chars", 0) <= 0:
            return True
        return self.is_vector_current(rel_path) if require_vector else True

    def invalidate_vector(self, rel_path: str) -> None:
        vector_path, meta_path = self._vector_paths(rel_path)
        vector_path.unlink(missing_ok=True)
        meta_path.unlink(missing_ok=True)

    def invalidate(self, rel_path: str, recursive: bool = False) -> None:
        rel = self._safe_rel(rel_path)
        text_path, meta_path = self._index_paths(rel)
        vector_path, vector_meta_path = self._vector_paths(rel)
        for path in (text_path, meta_path, vector_path, vector_meta_path):
            path.unlink(missing_ok=True)
        subtree = self.index_dir / rel
        if recursive and subtree.is_dir():
            shutil.rmtree(subtree)
        parent = text_path.parent
        while parent != self.index_dir:
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent

    def cleanup_orphans(self) -> dict[str, int]:
        candidates: set[str] = set()
        suffixes = (".vector.json", ".meta.json", ".npy", ".txt")
        for path in self.index_dir.rglob("*"):
            if not path.is_file():
                continue
            relative = path.relative_to(self.index_dir).as_posix()
            for suffix in suffixes:
                if relative.endswith(suffix):
                    candidates.add(relative[:-len(suffix)])
                    break
        removed = 0
        invalid_vectors = 0
        for rel in candidates:
            try:
                source = self.storage.resolve(rel)
            except (PermissionError, OSError, ValueError):
                source = None
            if source is None or not source.is_file():
                self.invalidate(rel, recursive=True)
                removed += 1
            elif (self._vector_paths(rel)[0].exists() or self._vector_paths(rel)[1].exists()) and not self.is_vector_current(rel):
                self.invalidate_vector(rel)
                invalid_vectors += 1
        return {"orphan_files": removed, "invalid_vectors": invalid_vectors}

    def search(self, query: str, limit: int = 10) -> list[dict[str, Any]]:
        results = []
        lowered_query = query.lower()
        for text_path in self.index_dir.rglob("*.txt"):
            rel = text_path.relative_to(self.index_dir).as_posix()[:-4]
            if not self.is_extract_current(rel):
                continue
            try:
                text = text_path.read_text(encoding="utf-8")
            except OSError:
                continue
            index = text.lower().find(lowered_query)
            if index < 0:
                continue
            snippet_start = max(0, index - 40)
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
        total_chars = 0
        by_method: dict[str, int] = {}
        indexed_files = 0
        for meta_path in self.index_dir.rglob("*.meta.json"):
            rel = meta_path.relative_to(self.index_dir).as_posix()[:-10]
            if not self.is_extract_current(rel):
                continue
            meta = self.get_meta(rel) or {}
            indexed_files += 1
            total_chars += int(meta.get("chars", 0))
            method = str(meta.get("method", "unknown"))
            by_method[method] = by_method.get(method, 0) + 1
        return {
            "indexed_files": indexed_files,
            "total_chars": total_chars,
            "by_method": by_method,
            "vectors": self.vector_stats(),
        }

    def vector_stats(self) -> dict[str, Any]:
        sources = self.iter_indexable_files()
        extracted = sum(self.is_extract_current(path) for path in sources)
        vectors = sum(self.is_vector_current(path) for path in sources)
        without_text = sum(
            self.is_extract_current(path) and int((self.get_meta(path) or {}).get("chars", 0)) <= 0
            for path in sources
        )
        sidecars = sum(1 for _ in self.index_dir.rglob("*.npy"))
        return {
            "eligible_files": len(sources),
            "extracted_files": extracted,
            "vector_files": vectors,
            "non_vectorizable_files": without_text,
            "missing_vectors": max(0, len(sources) - vectors - without_text),
            "stale_vectors": max(0, sidecars - vectors),
            "embedding_configured": self.embedder is not None,
            "model": str(getattr(self.embedder, "model", "")) if self.embedder is not None else "",
        }

    async def embed_file(self, rel_path: str, *, expected_revision: str | None = None) -> dict[str, Any]:
        if self.embedder is None:
            return {"ok": False, "error": "embedding is not configured"}
        revision = self.source_revision(rel_path)
        if expected_revision and revision != expected_revision:
            return {"ok": False, "stale": True, "error": "source changed"}
        text = self.get_text(rel_path, max_chars=200_000)
        if text is None:
            self.extract(rel_path)
            text = self.get_text(rel_path, max_chars=200_000) or ""
        if not text.strip():
            return {"ok": False, "error": "file has no extractable text"}

        chunks = [text[index:index + 2000] for index in range(0, len(text), 2000)][:50]
        vectors = await self.embedder.embed(chunks, task="retrieval.passage")
        if not vectors or not vectors[0]:
            return {"ok": False, "error": "embedding provider returned no vectors"}
        dimensions = len(vectors[0])
        if any(len(vector) != dimensions for vector in vectors):
            return {"ok": False, "error": "embedding dimensions are inconsistent"}
        if self.source_revision(rel_path) != revision:
            return {"ok": False, "stale": True, "error": "source changed"}

        vector_path, meta_path = self._vector_paths(rel_path)
        await asyncio.to_thread(self._atomic_vectors, vector_path, vectors)
        vector_meta = {
            "path": self._safe_rel(rel_path),
            "source_revision": revision,
            "embedding_fingerprint": self.embedding_fingerprint(),
            "provider": self.embedder.__class__.__name__,
            "model": str(getattr(self.embedder, "model", "")),
            "dimensions": dimensions,
            "chunks": len(chunks),
            "chunk_version": CHUNK_VERSION,
            "created_at": time.time(),
        }
        await asyncio.to_thread(self._atomic_text, meta_path, json.dumps(vector_meta, ensure_ascii=False))
        return {"ok": True, "path": rel_path, "chunks": len(chunks), "dim": dimensions}

    async def semantic_search(self, query: str, limit: int = 5) -> list[dict[str, Any]]:
        import numpy as np

        if self.embedder is None:
            return [{"error": "embedding is not configured"}]
        query_vector = (await self.embedder.embed([query], task="retrieval.query"))[0]
        query_array = np.asarray(query_vector, dtype=np.float32)
        query_norm = float(np.linalg.norm(query_array))
        if query_norm == 0:
            return []
        results = []
        for vector_path in self.index_dir.rglob("*.npy"):
            rel = vector_path.relative_to(self.index_dir).as_posix()[:-4]
            if not self.is_vector_current(rel):
                continue
            try:
                vectors = np.load(vector_path, allow_pickle=False)
                if vectors.ndim != 2 or vectors.shape[1] != query_array.shape[0]:
                    continue
                denominator = np.linalg.norm(vectors, axis=1) * query_norm + 1e-9
                best = float(np.max(np.dot(vectors, query_array) / denominator))
                results.append({"path": rel, "score": round(best, 4)})
            except (OSError, ValueError, TypeError):
                continue
        results.sort(key=lambda result: result["score"], reverse=True)
        return results[:max(1, min(limit, 50))]
