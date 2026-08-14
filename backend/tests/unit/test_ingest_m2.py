"""M2a/M2b 回归测试：摄入管线 + 覆盖保护 + 语义搜索（Jina 打桩）。

用法: python3 tests/unit/test_ingest_m2.py
"""
import asyncio
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
try:  # Windows 控制台 GBK：强制 UTF-8 输出，避免 ✅/中文打印崩溃
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from app.ingest.pipeline import IngestPipeline
from app.storage.local import LocalStorage


def make_storage():
    tmp = Path(tempfile.mkdtemp())
    return LocalStorage(tmp), tmp


def test_overwrite_protection():
    """move/copy 目标存在默认报错，overwrite=true 放行，copy 同源报 ValueError"""
    st, _ = make_storage()
    st.save_bytes("a.txt", b"AAA")
    st.save_bytes("dir/a.txt", b"OLD")

    # move 覆盖保护
    try:
        st.move("a.txt", "dir")
        raise AssertionError("move 应报 FileExistsError")
    except FileExistsError:
        pass
    st.move("a.txt", "dir", overwrite=True)
    assert st.read_text("dir/a.txt") == "AAA"

    # copy 覆盖保护
    st.save_bytes("b.txt", b"BBB")
    st.save_bytes("dir/b.txt", b"OLD")
    try:
        st.copy("b.txt", "dir/b.txt")
        raise AssertionError("copy 应报 FileExistsError")
    except FileExistsError:
        pass
    st.copy("b.txt", "dir/b.txt", overwrite=True)
    assert st.read_text("dir/b.txt") == "BBB"

    # copy 同源
    try:
        st.copy("b.txt", "b.txt", overwrite=True)
        raise AssertionError("copy 同源应报 ValueError")
    except ValueError:
        pass


def test_extract_text_pdf_and_meta():
    """文本提取 → 索引；PDF 提取；OCR 图片标记"""
    st, _ = make_storage()
    st.save_bytes("notes/hello.txt", ("你好世界 hello world" * 10).encode())
    ing = IngestPipeline(st)

    meta = ing.extract("notes/hello.txt")
    assert meta["method"] == "text"
    assert meta["chars"] > 0
    assert "你好世界" in ing.get_text("notes/hello.txt")

    # 索引隐藏：list_dir 不显示 .index
    assert not any("index" in f["path"] for f in st.list_dir(""))

    # 不支持类型
    st.save_bytes("blob.bin", b"\x00\x01")
    meta = ing.extract("blob.bin")
    assert meta["method"] == "unsupported"

    # PDF（PyMuPDF 生成真 PDF）
    try:
        import fitz
        pdf = fitz.open()
        pdf.new_page().insert_text((72, 72), "PDF content marker penalty clause")
        pdf.save(str(st.resolve("contract.pdf")))
        pdf.close()
        meta = ing.extract("contract.pdf")
        assert meta["method"] == "pdf"
        assert "penalty clause" in ing.get_text("contract.pdf")
    except ImportError:
        print("(跳过 PDF 测试: pymupdf 未安装)")


def test_search_content():
    """全文搜索命中片段"""
    st, _ = make_storage()
    st.save_bytes("docs/合同A.txt", "房屋租赁合同 违约金 每月5000元".encode())
    st.save_bytes("docs/计划.txt", "年度预算计划 100万元".encode())
    ing = IngestPipeline(st)
    ing.extract("docs/合同A.txt")
    ing.extract("docs/计划.txt")

    hits = ing.search("违约金")
    assert len(hits) == 1 and "合同A" in hits[0]["path"]
    assert hits[0]["snippet"]
    hits2 = ing.search("预算")
    assert len(hits2) == 1 and "计划" in hits2[0]["path"]
    assert ing.search("不存在的词") == []


class FakeEmbedder:
    """Jina 云 API 打桩：字符集向量（共享字符多 ⇒ 相似度高，模拟 embedding 语义近似）"""
    def __init__(self):
        self.calls = 0
        self.vocab = "租赁合同租房协议预算计划"

    async def embed(self, texts, task="text-matching"):
        self.calls += 1
        vecs = []
        for t in texts:
            chars = set(t.lower())
            vecs.append([1.0 if c in chars else 0.0 for c in self.vocab])
        return vecs

    async def test_connection(self):
        return {"ok": True, "model": "fake", "dimensions": len(self.vocab)}


def test_embed_and_semantic_search():
    """向量化 + 语义检索（不同措辞命中同义内容）"""
    st, _ = make_storage()
    st.save_bytes("docs/租赁协议.txt", "房屋租赁协议 租金条款".encode())
    st.save_bytes("docs/预算表.txt", "年度预算 数字".encode())
    embedder = FakeEmbedder()
    ing = IngestPipeline(st, embedder=embedder)
    ing.extract("docs/租赁协议.txt")
    ing.extract("docs/预算表.txt")

    async def run():
        r1 = await ing.embed_file("docs/租赁协议.txt")
        assert r1["ok"] and r1["dim"] == len(embedder.vocab)
        await ing.embed_file("docs/预算表.txt")
        # "租房合同"（不同措辞）→ 命中"租赁协议"
        hits = await ing.semantic_search("租房合同", limit=3)
        assert hits and hits[0]["path"] == "docs/租赁协议.txt", hits
        assert hits[0]["score"] > 0
        # 无向量文件不崩溃
        st2_hits = await ing.semantic_search("预算")
        assert st2_hits[0]["path"] == "docs/预算表.txt"

    asyncio.run(run())

    # 统计
    stats = ing.stats()
    assert stats["indexed_files"] >= 2


def test_embed_requires_provider():
    """未配置 embedder 时 embed_file 明确报错"""
    st, _ = make_storage()
    st.save_bytes("x.txt", b"hello")
    ing = IngestPipeline(st)
    ing.extract("x.txt")
    async def run():
        r = await ing.embed_file("x.txt")
        assert r["ok"] is False and "embedding" in r["error"]
        hits = await ing.semantic_search("hello")
        assert hits and "error" in hits[0]
    asyncio.run(run())


if __name__ == "__main__":
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    for fn in fns:
        fn()
        print(f"✅ {fn.__name__}")
    print(f"🎉 全部测试通过！({len(fns)} 项)")
