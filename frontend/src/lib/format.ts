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

type ToolArgFormatter = (args: Record<string, unknown>, path: string) => string;

const TOOL_ARG_FORMATTERS: Record<string, ToolArgFormatter> = {
  delete_file: (_args, path) => `删除 ${path}`,
  read_file: (_args, path) => `读取 ${path}`,
  read_document: (_args, path) => `读取 ${path}`,
  write_file: (_args, path) => `写入 ${path}`,
  append_file: (_args, path) => `追加 ${path}`,
  copy_file: (args) => `复制 ${args.src} → ${args.dst}`,
  move_file: (args) => `移动 ${args.src} → ${args.dst_dir}/`,
  rename_file: (args) => `重命名 ${args.src} → ${args.dst}`,
  create_folder: (_args, path) => `新建文件夹 ${path}`,
  list_files: (_args, path) => path ? `列出 ${path}` : "列出根目录",
  search_files: (_args, path) => `搜索 ${path}`,
  search_content: (args, path) => `内容搜索 "${args.query || path}"`,
  semantic_search: (args, path) => `语义搜索 "${args.query || path}"`,
  set_plan: () => "制定执行计划",
  update_plan: () => "更新执行计划",
  remember: (args) => `记住 "${String(args.content || args.text || "").slice(0, 20)}…"`,
  memory_search: (_args, path) => `记忆检索 ${path}`,
  memory_get: (_args, path) => `记忆检索 ${path}`,
  read_skill: (_args, path) => `加载技能 ${path}`,
  get_storage_info: () => "查看存储用量",
  get_system_status: () => "查看系统状态",
  view_audit_log: () => "查看审计日志",
  analyze_failures: () => "分析失败记录",
  run_automation_now: () => "执行自动化规则",
  configure_embeddings: (args) => `配置向量服务 ${String(args.model || "")}`,
};

/** 工具参数人类可读化（"删除 a.txt" 而非 {"path":"a.txt"}） */
export function fmtToolArgs(tool: string, args: Record<string, unknown>): string {
  const normalized = args || {};
  const path = String(normalized.path ?? normalized.src ?? normalized.query ?? normalized.name ?? "");
  return TOOL_ARG_FORMATTERS[tool]?.(normalized, path) ?? maskSecretsJson(normalized);
}

/**
 * JSON 展示脱敏：敏感键的值一律替换为 ***，仅用于界面展示，不能当作安全存储或日志脱敏的替代。
 */
export function maskSecretsJson(obj: unknown): string {
  return JSON.stringify(obj, (k, v) =>
    typeof k === "string" && /key|token|secret|password|authorization/i.test(k) ? "***" : v
  );
}

/** 将任务 payload/result 脱敏后格式化为可阅读的 JSON；非 JSON 值保留可展示的降级文本。 */
export function formatJson(value: unknown): string {
  if (value === null || value === undefined) return "";
  const masked = maskSecretsJson(value);
  try {
    return JSON.stringify(JSON.parse(masked), null, 2) ?? "";
  } catch {
    return masked;
  }
}

export const STEP_STATUS: Record<string, [string, string]> = {
  running: ["", "执行中"],
  done: ["", "完成"],
  error: ["", "失败"],
};
