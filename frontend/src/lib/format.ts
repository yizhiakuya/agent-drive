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
  plan: (args) => args.action === "set" ? "制定执行计划" : "更新执行计划",
  remember: (args) => `记住 "${String(args.content || args.text || "").slice(0, 20)}…"`,
  memory_search: (_args, path) => `记忆检索 ${path}`,
  memory_get: (_args, path) => `记忆检索 ${path}`,
  read_skill: (args) => args.action === "read"
    ? `加载 Skill ${String(args.name || "")}`.trim()
    : args.query
      ? `查找 Skill “${String(args.query)}”`
      : "查看 Skill 目录",
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

/** 将统一 backend_api operation 转成用户可理解的业务步骤标题。 */
export function fmtToolTitle(tool: string, args: Record<string, unknown> = {}): string {
  if (tool !== "backend_api") return tool;
  const operation = typeof args.operation === "string" ? args.operation : "";
  if (!operation) return args.action === "discover" ? "查找能力" : "执行后端操作";
  const normalized = operation.toUpperCase();
  const path = operation.replace(/^[A-Z]+\s+/, "");
  if (path === "/api/v1/files/stats") return "统计文件";
  if (path === "/api/v1/files") {
    const query = args.query_params;
    const queryValue = query && typeof query === "object"
      ? (query as Record<string, unknown>).q
      : null;
    return typeof queryValue === "string" && queryValue ? "搜索文件" : "浏览文件";
  }
  if (path === "/api/v1/files/info") return "查看文件信息";
  if (path === "/api/v1/files/content") return "读取文件内容";
  if (/\/api\/v1\/index(?:\/|$)/i.test(path)) return "更新索引";
  if (/\/api\/v1\/config(?:\/|$)/i.test(path)) return normalized.startsWith("GET") ? "查看服务配置" : "更新服务配置";
  if (/\/api\/v1\/sessions(?:\/|$)/i.test(path)) return "读取会话";
  if (/\/api\/v1\/devices(?:\/|$)/i.test(path)) return "读取设备状态";
  if (/\/api\/v1\/skills(?:\/|$)/i.test(path)) return "管理 Skill";
  if (/\/api\/v1\/files\//i.test(path)) return normalized.startsWith("GET") ? "查看文件状态" : "修改文件";
  return "执行后端操作";
}

/** 返回同步工具运行时的业务阶段提示。 */
export function fmtToolProgress(tool: string, args: Record<string, unknown> = {}): string {
  if (tool !== "backend_api") return tool === "plan" ? "正在更新当前会话计划" : "正在执行工具";
  const operation = typeof args.operation === "string" ? args.operation : "";
  const path = operation.replace(/^[A-Z]+\s+/, "");
  if (path.endsWith("/index/file")) return "正在抽取文本并写入索引";
  if (path.endsWith("/index/vision")) return "正在调用视觉模型分析图片";
  if (path.endsWith("/index/vectors")) return "正在生成文件向量";
  if (path.endsWith("/index/rebuild")) return "正在重建全文索引";
  if (path.endsWith("/config/models") || path.endsWith("/config/vision/models")) return "正在探测模型目录";
  if (path.endsWith("/vision/describe")) return "正在生成图片描述";
  if (path.endsWith("/files/stats")) return "正在递归统计文件";
  return `正在${fmtToolTitle(tool, args)}`;
}

/** 将工具运行耗时格式化为适合紧凑 Activity 行的文本。 */
export function fmtElapsedMs(value: number | null | undefined): string {
  const totalSeconds = Math.max(0, Math.floor((value ?? 0) / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes.toString().padStart(2, "0")}:${seconds.toString().padStart(2, "0")}`;
}

/** 从 backend_api 结果提取文件统计，供 Activity 卡片做一眼可读的摘要。 */
export function fileStatsSummary(tool: string, args: Record<string, unknown> = {}, parsed: unknown): string | null {
  if (tool !== "backend_api" || args.operation !== "GET /api/v1/files/stats") return null;
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
  const outer = parsed as Record<string, unknown>;
  const value = outer.result && typeof outer.result === "object" && !Array.isArray(outer.result)
    ? outer.result as Record<string, unknown>
    : outer;
  if (typeof value.file_count !== "number" || typeof value.folder_count !== "number") return null;
  const bytes = typeof value.total_size_bytes === "number" ? value.total_size_bytes : null;
  return `${value.file_count} 个文件 · ${value.folder_count} 个文件夹${bytes === null ? "" : ` · ${fmtSize(bytes)}`}`;
}

/**
 * JSON 展示脱敏：敏感键的值一律替换为 ***，仅用于界面展示，不能当作安全存储或日志脱敏的替代。
 */
export function maskSecretsJson(obj: unknown): string {
  return JSON.stringify(obj, (k, v) =>
    typeof k === "string" && /key|token|secret|password|authorization/i.test(k) ? "***" : v
  );
}

/** 将工具参数/结果脱敏后格式化为可阅读的 JSON；非 JSON 值保留可展示的降级文本。 */
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
