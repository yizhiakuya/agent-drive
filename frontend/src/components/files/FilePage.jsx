import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { listFiles, uploadFile } from "../../api/files.js";
import { fmtSize } from "../chat/ChatPanel.jsx";

/** 全宽文件管理页：面包屑导航 + 列表 + 预览面板 */
export default function FilePage() {
  const [path, setPath] = useState("");
  const [items, setItems] = useState([]);
  const [disk, setDisk] = useState(null);
  const [selected, setSelected] = useState(null); // 选中文件（预览）
  const [info, setInfo] = useState(null);
  const [previewText, setPreviewText] = useState("");
  const fileRef = useRef(null);
  const pathRef = useRef("");
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);

  const load = useCallback(async (p) => {
    try {
      const r = await listFiles(p);
      setItems(r.items);
      setDisk(r.disk);
      setPath(r.path);
      pathRef.current = r.path;
    } catch (e) {
      console.error(e);
    }
  }, []);

  useEffect(() => { load(""); }, [load]);
  useEffect(() => {
    function onChanged() { load(pathRef.current); }
    window.addEventListener("agent-drive:files-changed", onChanged);
    return () => window.removeEventListener("agent-drive:files-changed", onChanged);
  }, [load]);

  async function openItem(it) {
    if (it.is_dir) {
      load(it.path);
      setSelected(null); setInfo(null); setPreviewText("");
      return;
    }
    setSelected(it.path);
    setInfo(null); setPreviewText("");
    try {
      const r = await fetch(`/api/v1/files/info?path=${encodeURIComponent(it.path)}`);
      if (r.ok) {
        const data = await r.json();
        setInfo(data);
        if (data.preview_kind === "text" && data.snippet) setPreviewText(data.snippet);
      }
    } catch (e) { /* 忽略 */ }
  }

  // 面包屑段
  const crumbs = path ? path.split("/").filter(Boolean) : [];
  function goTo(i) {
    load(crumbs.slice(0, i + 1).join("/"));
  }

  async function doUpload(file) {
    if (!file) return;
    setUploading(true);
    try {
      await uploadFile(file, path);
      window.dispatchEvent(new CustomEvent("agent-drive:toast", { detail: { kind: "ok", text: `已上传 ${file.name}` } }));
      load(path);
    } catch (e) {
      window.dispatchEvent(new CustomEvent("agent-drive:toast", { detail: { kind: "error", text: `上传失败: ${e}` } }));
    } finally {
      setUploading(false);
    }
  }

  async function onUpload(e) {
    const file = e.target.files?.[0];
    if (file) { await doUpload(file); e.target.value = ""; }
  }

  function onDrop(e) {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer?.files?.[0];
    if (file) doUpload(file);
  }

  const rawUrl = selected ? `/api/v1/files/raw?path=${encodeURIComponent(selected)}` : null;

  return (
    <section
      className={`file-page ${dragOver ? "drag-over" : ""}`}
      onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
      onDragLeave={() => setDragOver(false)}
      onDrop={onDrop}
    >
      <div className="fp-main">
        <div className="fp-head">
          <b>📁 文件</b>
          <span className="fp-head-actions">
            <button className="btn small" onClick={() => load(path ? crumbs.slice(0, -1).join("/") : "")}
                    disabled={!path} title="返回上级">⬆ 上级</button>
            <button className="btn small" onClick={() => fileRef.current?.click()} disabled={uploading}>
              {uploading ? <><span className="spinner" /> 上传中</> : "⬆ 上传"}
            </button>
            <button className="btn small" onClick={() => load(path)} title="刷新">🔄</button>
          </span>
          <input ref={fileRef} type="file" style={{ display: "none" }} onChange={onUpload} />
        </div>

        <div className="fp-crumbs">
          <button className={`crumb ${!path ? "active" : ""}`} onClick={() => load("")}>🏠 根目录</button>
          {crumbs.map((c, i) => (
            <span key={i}>
              <span className="crumb-sep">/</span>
              <button className={`crumb ${i === crumbs.length - 1 ? "active" : ""}`} onClick={() => goTo(i)}>{c}</button>
            </span>
          ))}
        </div>

        <div className="fp-table-wrap">
          <table className="fp-table">
            <thead>
              <tr><th>名称</th><th style={{width:90}}>大小</th><th style={{width:170}}>修改时间</th></tr>
            </thead>
            <tbody>
              {items.length === 0 && (
                <tr><td colSpan={3} style={{padding:32}}>
                  <div className="fp-empty">
                    <span className="fp-empty-icon">{dragOver ? "📥" : "📂"}</span>
                    {dragOver ? "松开鼠标上传文件" : "目录为空 — 拖文件到这里，或点「上传」"}
                  </div>
                </td></tr>
              )}
              {items.map((it) => (
                <tr key={it.path}
                    className={`fp-row ${it.is_dir ? "dir" : ""} ${selected === it.path ? "sel" : ""}`}
                    onClick={() => openItem(it)}
                    onDoubleClick={() => it.is_dir && load(it.path)}>
                  <td><span className="fp-icon">{it.is_dir ? "📂" : "📄"}</span> {it.name}</td>
                  <td>{it.is_dir ? "—" : fmtSize(it.size)}</td>
                  <td className="muted small">{it.mtime ? new Date(it.mtime * 1000).toLocaleString() : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {disk && (
          <div className="fp-disk">
            已用 {fmtSize(disk.used)} / {fmtSize(disk.total)} · 剩余 {fmtSize(disk.free)}
          </div>
        )}
      </div>

      {/* 预览面板 */}
      <div className="fp-preview">
        {!selected && <div className="muted small" style={{padding:16}}>← 点击文件预览（文本/Markdown/图片/PDF）</div>}
        {selected && (
          <>
            <div className="pv-head">
              <b title={selected}>{selected.split("/").pop()}</b>
              <a className="btn small" href={`/api/v1/files/download?path=${encodeURIComponent(selected)}`} download>⬇ 下载</a>
            </div>
            {info && (
              <div className="pv-meta muted small">
                {fmtSize(info.size)} · {new Date(info.modified * 1000).toLocaleString()}
                {info.indexed && ` · 已索引(${info.indexed.method}, ${info.indexed.chars}字)`}
              </div>
            )}
            <div className="pv-body">
              {info?.preview_kind === "image" && <img src={rawUrl} alt={selected} className="pv-img" />}
              {info?.preview_kind === "pdf" && <iframe src={rawUrl} title={selected} className="pv-pdf" />}
              {info?.preview_kind === "text" && selected.toLowerCase().endsWith(".md") ? (
                <div className="pv-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{previewText}</ReactMarkdown></div>
              ) : (
                <pre className="pv-text">{previewText}</pre>
              )}
              {info?.preview_kind === "binary" && (
                <div className="muted small" style={{padding:16}}>二进制文件不支持预览，可下载后查看</div>
              )}
            </div>
          </>
        )}
      </div>
    </section>
  );
}
