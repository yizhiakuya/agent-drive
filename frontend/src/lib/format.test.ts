import { describe, expect, it } from "vitest";
import { fmtTime } from "./format";

describe("fmtTime", () => {
  // 固定时区避免 CI 环境差异：toLocaleString("zh-CN") 结果随运行环境 TZ 变化，
  // 这里只断言结构分界与空值/选项分支，不硬编码具体日期字符串。
  it("空值返回空字符串", () => {
    expect(fmtTime(null)).toBe("");
    expect(fmtTime(undefined)).toBe("");
    expect(fmtTime(0)).toBe("");
  });

  it("默认返回 zh-CN 完整日期时间（含时间部分）", () => {
    const full = fmtTime(1_750_000_000);
    expect(full).not.toBe("");
    // 完整格式含时:分（冒号分隔）；dateOnly 不含冒号时间
    expect(full).toMatch(/:/);
  });

  it("dateOnly 仅返回日期", () => {
    const d = fmtTime(1_750_000_000, { dateOnly: true });
    expect(d).not.toBe("");
    // 日期不含时:分（无 ":" 时间分隔）
    expect(d).not.toMatch(/:/);
  });

  it("short 返回 月/日 时:分 且不含秒", () => {
    const s = fmtTime(1_750_000_000, { short: true });
    expect(s).not.toBe("");
    expect(s).toMatch(/:/);
    // short 格式含 4 个数字段（月日时分），与完整格式（含年/秒）区分：
    // zh-CN 2-digit 输出形如 "MM/DD HH:mm"，不包含 4 位年份
    expect(s).not.toMatch(/\b\d{4}\b/);
  });

  it("同一时间戳 dateOnly 与默认长度关系：dateOnly 更短", () => {
    const full = fmtTime(1_750_000_000);
    const d = fmtTime(1_750_000_000, { dateOnly: true });
    expect(d.length).toBeLessThan(full.length);
  });
});
