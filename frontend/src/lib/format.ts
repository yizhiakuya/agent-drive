// 展示工具函数（从 ChatPanel 拆出，独立测试）

/** 秒级时间戳 → zh-CN 展示文案；dateOnly=仅日期，short=月/日 时:分（不含年/秒）。 */
export function fmtTime(tsSeconds: number | null | undefined, opts?: { dateOnly?: boolean; short?: boolean }): string {
  if (!tsSeconds) return "";
  const d = new Date(tsSeconds * 1000);
  if (opts?.dateOnly) return d.toLocaleDateString("zh-CN");
  if (opts?.short) {
    return d.toLocaleString("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  }
  return d.toLocaleString("zh-CN");
}

export function fmtSize(n: number): string {
  if (n > 1e9) return (n / 1e9).toFixed(1) + " GB";
  if (n > 1e6) return (n / 1e6).toFixed(1) + " MB";
  if (n > 1e3) return (n / 1e3).toFixed(1) + " KB";
  return n + " B";
}

export function fmtTokens(n: number): string {
  if (n >= 1e6) return (n / 1e6).toFixed(2) + "M";
  if (n >= 1e3) return (n / 1e3).toFixed(1) + "K";
  return String(n);
}

/** 工具参数人类可读化（"删除 a.txt" 而非 {"path":"a.txt"}） */
export function fmtToolArgs(tool: string, args: Record<string, unknown>): string {
  const a = args || {};
  const p = String(a.path ?? a.src ?? a.query ?? a.name ?? "");
  switch (tool) {
    case "delete_file": return `删除 ${p}`;
    case "read_file": case "read_document": return `读取 ${p}`;
    case "write_file": return `写入 ${p}`;
    case "append_file": return `追加 ${p}`;
    case "copy_file": return `复制 ${a.src} → ${a.dst}`;
    case "move_file": return `移动 ${a.src} → ${a.dst_dir}/`;
    case "rename_file": return `重命名 ${a.src} → ${a.dst}`;
    case "create_folder": return `新建文件夹 ${p}`;
    case "list_files": return p ? `列出 ${p}` : "列出根目录";
    case "search_files": return `搜索 ${p}`;
    case "search_content": return `内容搜索 "${a.query || p}"`;
    case "semantic_search": return `语义搜索 "${a.query || p}"`;
    case "set_plan": return "制定执行计划";
    case "update_plan": return "更新执行计划";
    case "remember": return `记住 "${String(a.content || a.text || "").slice(0, 20)}…"`;
    case "memory_search": case "memory_get": return `记忆检索 ${p}`;
    case "read_skill": return `加载技能 ${p}`;
    case "get_storage_info": return "查看存储用量";
    case "get_system_status": return "查看系统状态";
    case "view_audit_log": return "查看审计日志";
    case "analyze_failures": return "分析失败记录";
    case "run_automation_now": return "执行自动化规则";
    default: return JSON.stringify(a);
  }
}

export const TOOL_ICONS: Record<string, string> = {
  list_files: "📂", search_files: "🔍", read_file: "📖", write_file: "✍️",
  append_file: "➕", copy_file: "📄", create_folder: "📁", rename_file: "✏️",
  move_file: "🚚", delete_file: "🗑️", get_storage_info: "💾",
  get_system_status: "⚙️", set_llm_provider: "🔌", test_llm_connection: "📡",
  set_preference: "🎛️", add_rule: "📏", remove_rule: "➖", view_audit_log: "🧾",
  analyze_failures: "🔬", set_plan: "📋", update_plan: "📋",
  remember: "🧠", memory_search: "🔎", memory_get: "📇", read_skill: "📚",
  read_document: "📖", search_content: "🔍", semantic_search: "🧭", index_stats: "📊",
  run_automation_now: "🤖", automation_status: "⏰",
};

export const STEP_STATUS: Record<string, [string, string]> = {
  running: ["🔄", "执行中"],
  done: ["✅", "完成"],
  error: ["❌", "失败"],
};
