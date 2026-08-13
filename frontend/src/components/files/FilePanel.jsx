import { useCallback, useEffect, useRef, useState } from "react";
import { listFiles, uploadFile } from "../../api/files.js";

export default function FilePanel() {
  const [path, setPath] = useState("");
  const [items, setItems] = useState([]);
  const [disk, setDisk] = useState(null);
  const [collapsed, setCollapsed] = useState(false); // 移动端折叠
  const fileRef = useRef(null);
  const pathRef = useRef(""); // load 回调闭包外的当前路径（供事件监听使用）

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

  // 联动刷新：Agent 操作文件后自动刷新当前目录
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
      await uploadFile(file, path);
      load(path);
    }
  }

  function fmt(n) {
    if (n > 1e9) return (n / 1e9).toFixed(1) + " GB";
    if (n > 1e6) return (n / 1e6).toFixed(1) + " MB";
    if (n > 1e3) return (n / 1e3).toFixed(1) + " KB";
    return n + " B";
  }

  return (
    <aside className={`file-panel ${collapsed ? "collapsed" : ""}`}>
      <div className="fp-head">
        <b>📁 文件</b>
        <span className="fp-head-actions">
          <button className="btn small" onClick={() => fileRef.current?.click()}>⬆ 上传</button>
          <button className="btn small fp-toggle" onClick={() => setCollapsed((v) => !v)}
                  title={collapsed ? "展开" : "收起"}>{collapsed ? "▶" : "▼"}</button>
        </span>
        <input ref={fileRef} type="file" style={{ display: "none" }} onChange={onUpload} />
      </div>
      <div className="fp-body">
      <div className="fp-path">{path || "/"}</div>
      <div className="fp-list">
        {items.length === 0 && <div className="muted small">（空目录）</div>}
        {items.map((it) => (
          <div
            key={it.path}
            className={`fp-item ${it.is_dir ? "dir" : ""}`}
            onDoubleClick={() => it.is_dir && load(it.path)}
          >
            <span className="fp-icon">{it.is_dir ? "📂" : "📄"}</span>
            <span className="fp-name" title={it.path}>{it.name}</span>
            <span className="fp-size">{it.is_dir ? "" : fmt(it.size)}</span>
          </div>
        ))}
      </div>
      {disk && (
        <div className="fp-disk">
          已用 {fmt(disk.used)} / {fmt(disk.total)} · 剩余 {fmt(disk.free)}
        </div>
      )}
      </div>
    </aside>
  );
}
