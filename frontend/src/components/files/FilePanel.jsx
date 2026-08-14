import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { listFiles, uploadFile } from "../../api/files.js";
import { fmtSize } from "../chat/ChatPanel.jsx";

/** 对话页右侧文件面板：面包屑导航 + 上级 + 点击预览 + 联动刷新 + 移动端折叠 */
export default function FilePanel() {
  const [path, setPath] = useState("");
  const [items, setItems] = useState([]);
  const [disk, setDisk] = useState(null);
  const [collapsed, setCollapsed] = useState(false);
  const [selected, setSelected] = useState(null); // 预览: {path, info, text}
  const fileRef = useRef(null);
  const pathRef = useRef("");

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

  // 移动端默认折叠（窄屏时收起文件面板，聚焦对话）
  useEffect(() => {
    if (window.innerWidth < 900) setCollapsed(true);
  }, []);

  useEffect(() => {
    function onFilesChanged() {
      load(pathRef.current);
    }
    window.addEventListener("agent-drive:files-changed", onFilesChanged);
    return () => window.removeEventListener("agent-drive:files-changed", onFilesChanged);
  }, [load]);

  async function onUpload(e) {
    const file = e.target.files?.[0];
    if (file) {
      try {
        await uploadFile(file, path);
        window.dispatchEvent(new CustomEvent("agent-drive:toast", { detail: { kind: "ok", text: `已上传 ${file.name}` } }));
        load(path);
      } catch (err) {
        window.dispatchEvent(new CustomEvent("agent-drive:toast", { detail: { kind: "error", text: `上传失败: ${err}` } }));
      }
      e.target.value = "";
    }
  }

  async function openItem(it) {
    if (it.is_dir) {
      load(it.path);
      setSelected(null);
      return;
    }
    setSelected({ path: it.path, info: null, text: "" });
    try {
      const r = await fetch(`/api/v1/files/info?path=${encodeURIComponent(it.path)}`);
      if (r.ok) {
        const data = await r.json();
        setSelected((s) => ({ ...s, info: data, text: data.preview_kind === "text" ? (data.snippet || "") : "" }));
      }
    } catch (e) { /* 忽略 */ }
  }

  const crumbs = path ? path.split("/").filter(Boolean) : [];
  function goTo(i) {
    load(crumbs.slice(0, i + 1).join("/"));
  }

  const rawUrl = selected?.path ? `/api/v1/files/raw?path=${encodeURIComponent(selected.path)}` : null;
  const isMarkdown = selected?.info?.path?.toLowerCase().endsWith(".md");

  return (
    <aside className={`file-panel ${collapsed ? "collapsed" : ""}`}>
      <div className="fp-head">
        <b>📁 文件</b>
        <span className="fp-head-actions">
          <button className="btn small" onClick={() => fileRef.current?.click()}>⬆ 上传</button>
          <button className="btn small" onClick={() => load(path)} title="刷新">🔄</button>
          <button className="btn small fp-toggle" onClick={() => setCollapsed((v) => !v)}
                  title={collapsed ? "展开" : "收起"}>{collapsed ? "▶" : "▼"}</button>
        </span>
        <input ref={fileRef} type="file" style={{ display: "none" }} onChange={onUpload} />
      </div>
      <div className="fp-body">
      <div className="fp-crumbs small">
        <button className={`crumb ${!path ? "active" : ""}`} onClick={() => load("")}>🏠</button>
        {crumbs.map((c, i) => (
          <span key={i}>
            <span className="crumb-sep">/</span>
            <button className={`crumb ${i === crumbs.length - 1 ? "active" : ""}`}
                    onClick={() => goTo(i)}>{c}</button>
          </span>
        ))}
        {path && (
          <button className="crumb up" title="返回上级"
                  onClick={() => load(crumbs.slice(0, -1).join("/"))}>⬆</button>
        )}
      </div>
      <div className="fp-list">
        {items.length === 0 && (
          <div className="fp-empty">
            <span className="fp-empty-icon">📂</span>
            目录为空
          </div>
        )}
        {items.map((it) => (
          <div
            key={it.path}
            className={`fp-item ${it.is_dir ? "dir" : ""} ${selected?.path === it.path ? "sel" : ""}`}
            onClick={() => openItem(it)}
            onDoubleClick={() => it.is_dir && load(it.path)}
          >
            <span className="fp-icon">{it.is_dir ? "📂" : "📄"}</span>
            <span className="fp-name" title={it.path}>{it.name}</span>
            <span className="fp-size">{it.is_dir ? "" : fmtSize(it.size)}</span>
          </div>
        ))}
      </div>
      {/* 预览小面板 */}
      {selected && !collapsed && (
        <div className="fp-mini-preview">
          <div className="pv-head">
            <b title={selected.path}>{selected.path.split("/").pop()}</b>
            <span className="fp-head-actions">
              <a className="btn small" href={`/api/v1/files/download?path=${encodeURIComponent(selected.path)}`} download>⬇</a>
              <button className="btn small" onClick={() => setSelected(null)} title="关闭预览">✕</button>
            </span>
          </div>
          {selected.info?.indexed && (
            <div className="pv-meta muted small">已索引({selected.info.indexed.method}, {selected.info.indexed.chars}字)</div>
          )}
          <div className="fp-mini-preview-body">
            {selected.info?.preview_kind === "image" && <img src={rawUrl} alt="" className="pv-img" />}
            {selected.info?.preview_kind === "pdf" && <iframe src={rawUrl} title="pdf" className="pv-pdf" />}
            {selected.info?.preview_kind === "text" && isMarkdown ? (
              <div className="pv-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{selected.text}</ReactMarkdown></div>
            ) : (
              <pre className="pv-text">{selected.text}</pre>
            )}
            {selected.info?.preview_kind === "binary" && <div className="muted small">二进制文件，下载查看</div>}
          </div>
        </div>
      )}
      {disk && (
        <div className="fp-disk">
          已用 {fmtSize(disk.used)} / {fmtSize(disk.total)} · 剩余 {fmtSize(disk.free)}
        </div>
      )}
      </div>
    </aside>
  );
}
