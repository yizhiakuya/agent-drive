import { describe, it, expect } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { fileStatsSummary, fmtSize, fmtTokens, fmtToolArgs, fmtToolTitle } from "@/lib/format";
import { ToolStep } from "./ToolStep";
import { ContextBar } from "./ContextBar";
import { PlanCard } from "./PlanCard";
import { chatTextDelta } from "./ChatPanel";

describe("fmt 工具函数", () => {
  it("token 格式化", () => {
    expect(fmtTokens(500)).toBe("500");
    expect(fmtTokens(2400)).toBe("2.4K");
    expect(fmtTokens(262144)).toBe("262.1K");
    expect(fmtTokens(1500000)).toBe("1.50M");
  });
  it("文件大小格式化", () => {
    expect(fmtSize(500)).toBe("500 B");
    expect(fmtSize(2048)).toBe("2.0 KB");
    expect(fmtSize(5 * 1024 * 1024)).toBe("5.2 MB");
  });
});

describe("ChatPanel SSE 文本", () => {
  it("从 text 事件对象读取增量而非拼接 object", () => {
    expect(chatTextDelta({ text: "你好" })).toBe("你好");
    expect(chatTextDelta({ text: { nested: true } })).toBe("");
  });
});

describe("fmtToolArgs 工具参数人类可读", () => {
  it("危险/文件操作转换为自然语言", () => {
    expect(fmtToolArgs("delete_file", { path: "a.txt" })).toBe("删除 a.txt");
    expect(fmtToolArgs("copy_file", { src: "a", dst: "b" })).toBe("复制 a → b");
    expect(fmtToolArgs("move_file", { src: "a", dst_dir: "dir" })).toBe("移动 a → dir/");
    expect(fmtToolArgs("write_file", { path: "notes/x.md" })).toBe("写入 notes/x.md");
    expect(fmtToolArgs("list_files", {})).toBe("列出根目录");
    expect(fmtToolArgs("semantic_search", { query: "预算" })).toBe('语义搜索 "预算"');
    expect(fmtToolArgs("read_skill", { action: "read", name: "weekly-report" })).toBe("加载 Skill weekly-report");
    expect(fmtToolArgs("read_skill", { action: "discover", query: "周报" })).toBe("查找 Skill “周报”");
    expect(fmtToolArgs("plan", { action: "set" })).toBe("制定执行计划");
    expect(fmtToolArgs("plan", { action: "update" })).toBe("更新执行计划");
  });
  it("未知工具回退 JSON", () => {
    expect(fmtToolArgs("unknown_tool", { x: 1 })).toBe('{"x":1}');
  });
});

describe("ContextBar 上下文圆环", () => {
  it("显示已用/总量", () => {
    render(<ContextBar usage={{ used: 2400, total: 262144, percent: 0.9 }} />);
    expect(screen.getByTestId("context-usage-summary")).toHaveTextContent(/2.4K \/ 262.1K/);
    expect(screen.getByRole("img", { name: /上下文窗口 1%/ })).toBeInTheDocument();
  });
  it("超过 100% 时封顶", () => {
    render(<ContextBar usage={{ used: 999999, total: 262144, percent: 381 }} />);
    const ring = screen.getByTestId("context-progress-ring");
    expect((ring as unknown as SVGCircleElement).style.strokeDashoffset).toBe("0");
    expect(screen.getByText("100%")).toBeInTheDocument();
  });
  it("默认收起，点击顶部控件后展开详情", () => {
    render(<ContextBar usage={{ used: 333000, total: 967000, percent: 34 }} />);
    const details = screen.getByTestId("context-usage");
    expect(details).not.toHaveAttribute("open");
    fireEvent.click(screen.getByTestId("context-usage-summary"));
    expect(details).toHaveAttribute("open");
    expect(screen.getByTestId("context-usage-details")).toHaveTextContent("可用空间");
  });
  it("点击外部或按 Escape 会自动收起", () => {
    render(<ContextBar usage={{ used: 333000, total: 967000, percent: 34 }} />);
    const details = screen.getByTestId("context-usage");
    fireEvent.click(screen.getByTestId("context-usage-summary"));
    expect(details).toHaveAttribute("open");
    fireEvent.pointerDown(document.body);
    expect(details).not.toHaveAttribute("open");
    fireEvent.click(screen.getByTestId("context-usage-summary"));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(details).not.toHaveAttribute("open");
  });
});

describe("fmtToolTitle 业务步骤标题", () => {
  it("将 backend_api operation 映射为用户级标题", () => {
    expect(fmtToolTitle("backend_api", {
      action: "call",
      operation: "GET /api/v1/files/stats",
    })).toBe("统计文件");
    expect(fmtToolTitle("backend_api", {
      action: "call",
      operation: "GET /api/v1/files",
      query_params: { q: "合同" },
    })).toBe("搜索文件");
  });

  it("从 stats 结果生成文件统计摘要", () => {
    expect(fileStatsSummary("backend_api", { operation: "GET /api/v1/files/stats" }, {
      ok: true,
      result: { file_count: 777, folder_count: 97, total_size_bytes: 2048 },
    })).toBe("777 个文件 · 97 个文件夹 · 2.0 KB");
  });
});

describe("PlanCard 计划卡片", () => {
  it("显示完成进度", () => {
    const plan = [
      { text: "扫描文件", status: "done" },
      { text: "分类", status: "in_progress" },
      { text: "汇报", status: "pending" },
    ];
    render(<PlanCard plan={plan} />);
    expect(screen.getByText(/执行计划（1\/3）/)).toBeInTheDocument();
    expect(screen.getByText("当前会话")).toBeInTheDocument();
    expect(screen.getByText("扫描文件")).toBeInTheDocument();
  });
});

describe("ToolStep 工具步骤", () => {
  it("运行中的长工具显示业务阶段和运行计时", () => {
    render(<ToolStep step={{
      tool: "backend_api",
      arguments: { operation: "PUT /api/v1/index/vectors" },
      status: "running",
      progressMessage: "正在生成文件向量",
      elapsedMs: 2300,
    }} />);
    expect(screen.getByText("正在生成文件向量")).toBeInTheDocument();
    expect(screen.getByText("00:02")).toBeInTheDocument();
    expect(screen.getByRole("progressbar", { name: /正在生成文件向量/ })).toBeInTheDocument();
    expect(screen.queryByText("此步骤没有可展示的返回内容")).not.toBeInTheDocument();
  });

  it("running 状态显示执行中", () => {
    render(<ToolStep step={{ tool: "list_files", arguments: {}, status: "running" }} />);
    expect(screen.getByText(/执行中/)).toBeInTheDocument();
    expect(screen.getByText("list_files")).toBeInTheDocument();
  });
  it("done 状态显示完成 + 点击展开结果", () => {
    render(<ToolStep step={{ tool: "write_file", arguments: { path: "a.md" }, status: "done", output: "{\"action\":\"新建\"}", parsed: { action: "新建" } }} />);
    expect(screen.getByText(/完成/)).toBeInTheDocument();
    fireEvent.click(screen.getByText("write_file"));
    expect(screen.getByText(/新建/)).toBeInTheDocument();
  });
  it("error 状态显示失败", () => {
    render(<ToolStep step={{ tool: "read_file", arguments: { path: "x" }, status: "error", output: "{\"ok\":false,\"error\":\"文件不存在\"}", parsed: { ok: false, error: "文件不存在" } }} />);
    expect(screen.getByText(/失败/)).toBeInTheDocument();
  });

  it("完成态工具步骤保留独立耗时", () => {
    render(<ToolStep step={{ tool: "backend_api", status: "done", elapsedMs: 4800, output: "{}", parsed: { ok: true } }} />);
    expect(screen.getByText("耗时 00:04")).toBeInTheDocument();
    expect(screen.getByLabelText("工具耗时 耗时 00:04")).toBeInTheDocument();
  });

  it("历史 backend_api 嵌套失败结果显示失败详情", () => {
    render(<ToolStep step={{
      tool: "backend_api",
      arguments: { action: "call" },
      status: "done",
      parsed: { ok: true, result: { ok: false, error: "provider_failed", detail: "视觉服务不可用" } },
    }} />);
    fireEvent.click(screen.getByText("backend_api"));
    expect(screen.getByText(/视觉服务不可用/)).toBeInTheDocument();
    expect(screen.getByText(/失败/)).toBeInTheDocument();
  });

  it("文件统计步骤直接显示业务摘要", () => {
    render(<ToolStep step={{
      tool: "backend_api",
      arguments: { action: "call", operation: "GET /api/v1/files/stats" },
      status: "done",
      parsed: { ok: true, result: { file_count: 777, folder_count: 97, total_size_bytes: 2048 } },
    }} />);
    fireEvent.click(screen.getByText("统计文件"));
    expect(screen.getByText("777 个文件 · 97 个文件夹 · 2.0 KB")).toBeInTheDocument();
  });
  it("对象型 parsed 渲染为完整 JSON 而非截断原文", () => {
    const parsed = { llm_configured: true, embeddings: { configured: false, model: "" } };
    render(<ToolStep step={{ tool: "get_system_status", arguments: {}, status: "done", output: "{\"llm_configured\": true, \"embeddings\":", parsed }} />);
    fireEvent.click(screen.getByText("get_system_status"));
    expect(screen.getByText(/"configured": false/)).toBeInTheDocument();
    expect(screen.getByText(/"model": ""/)).toBeInTheDocument();
  });
  it("只有结构化 parsed、没有 output 时也能展开工具结果", () => {
    render(<ToolStep step={{
      tool: "backend_api",
      arguments: { action: "discover" },
      status: "done",
      parsed: { ok: true, returned: 0, has_more: false },
    }} />);
    fireEvent.click(screen.getByText("backend_api"));
    expect(screen.getByText(/\"returned\": 0/)).toBeInTheDocument();
  });
  it("没有 output 或 parsed 时展开后给出空结果提示", () => {
    render(<ToolStep step={{ tool: "backend_api", status: "done" }} />);
    fireEvent.click(screen.getByText("backend_api"));
    expect(screen.getByText("此步骤没有可展示的返回内容")).toBeInTheDocument();
  });
  it("read_skill 直接显示加载的 Skill 名称", () => {
    render(<ToolStep step={{
      tool: "read_skill",
      arguments: { action: "read", name: "weekly-report" },
      status: "done",
      output: "{}",
    }} />);
    expect(screen.getByText("Skill · weekly-report")).toBeInTheDocument();
  });

  it("list_files 结果渲染为表格", () => {
    render(<ToolStep step={{
      tool: "list_files", arguments: {}, status: "done",
      output: "[]", parsed: [{ name: "a.txt", is_dir: false, size: 100 }],
    }} />);
    fireEvent.click(screen.getByText("list_files"));
    expect(screen.getByText(/a\.txt/)).toBeInTheDocument();
  });
});
