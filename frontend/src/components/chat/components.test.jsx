import { describe, it, expect } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ToolStep, ContextBar, PlanCard, fmtTokens, fmtSize, fmtToolArgs } from "./ChatPanel.jsx";

describe("fmtTokens/fmtSize 工具函数", () => {
  it("token 格式化", () => {
    expect(fmtTokens(500)).toBe("500");
    expect(fmtTokens(2400)).toBe("2.4K");
    expect(fmtTokens(262144)).toBe("262.1K");
    expect(fmtTokens(1500000)).toBe("1.50M");
  });
  it("文件大小格式化", () => {
    expect(fmtSize(500)).toBe("500 B");
    expect(fmtSize(2048)).toBe("2.0 KB");
    expect(fmtSize(5 * 1024 * 1024)).toBe("5.2 MB");  // 实现以 1e6 为 MB 基准
  });
});

describe("ContextBar 上下文进度条", () => {
  it("显示已用/总量", () => {
    render(<ContextBar usage={{ used: 2400, total: 262144, percent: 0.9 }} />);
    expect(screen.getByText(/2.4K \/ 262.1K/)).toBeInTheDocument();
  });
  it("超过 100% 时封顶", () => {
    render(<ContextBar usage={{ used: 999999, total: 262144, percent: 381 }} />);
    const fill = document.querySelector(".context-fill");
    expect(fill.style.width).toBe("100%");
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
    expect(screen.getByText("📋 执行计划（1/3）")).toBeInTheDocument();
    expect(screen.getByText("扫描文件")).toBeInTheDocument();
  });
});

describe("ToolStep 工具步骤", () => {
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
  it("list_files 结果渲染为表格", () => {
    render(<ToolStep step={{
      tool: "list_files", arguments: {}, status: "done",
      output: "[]", parsed: [{ name: "a.txt", is_dir: false, size: 100 }],
    }} />);
    fireEvent.click(screen.getByText("list_files"));
    expect(screen.getByText(/a\.txt/)).toBeInTheDocument();
    expect(screen.getAllByText("文件").length).toBeGreaterThan(0);
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
    expect(fmtToolArgs("read_skill", { path: "weekly-report" })).toBe("加载技能 weekly-report");
  });
  it("未知工具回退 JSON", () => {
    expect(fmtToolArgs("unknown_tool", { x: 1 })).toBe('{"x":1}');
  });
});
