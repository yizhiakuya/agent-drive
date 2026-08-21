import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import SkillsManager from "./SkillsManager";

const mocks = vi.hoisted(() => ({
  listSkills: vi.fn(),
  getSkill: vi.fn(),
  saveSkill: vi.fn(),
  deleteSkill: vi.fn(),
}));

vi.mock("@/lib/api/skills", () => ({
  listSkills: mocks.listSkills,
  getSkill: mocks.getSkill,
  saveSkill: mocks.saveSkill,
  deleteSkill: mocks.deleteSkill,
}));

const builtin = {
  name: "agent-drive-api",
  description: "内置 API",
  instructions: "API instructions",
  enabled: true,
  source: "builtin" as const,
  version: 1,
  created_at: null,
  updated_at: null,
};

const custom = {
  name: "weekly-report",
  description: "生成周报",
  instructions: "先读取文件，再生成周报",
  enabled: true,
  source: "custom" as const,
  version: 2,
  created_at: 1,
  updated_at: 2,
};

function page(skills = [builtin, custom]) {
  return {
    skills: skills.map((skill) => ({
      name: skill.name,
      description: skill.description,
      enabled: skill.enabled,
      source: skill.source,
      version: skill.version,
      updated_at: skill.updated_at,
    })),
    total_matches: skills.length,
    returned: skills.length,
    offset: 0,
    limit: 50,
    has_more: false,
    next_offset: skills.length,
  };
}

describe("SkillsManager", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listSkills.mockResolvedValue(page());
    mocks.getSkill.mockImplementation(async (name: string) => name === builtin.name ? builtin : custom);
    mocks.saveSkill.mockImplementation(async (name: string, input: typeof custom) => ({
      ...custom,
      ...input,
      name,
      version: name === custom.name ? 3 : 1,
    }));
    mocks.deleteSkill.mockResolvedValue(undefined);
  });

  it("loads the builtin skill as read-only", async () => {
    render(<SkillsManager />);

    expect(await screen.findByDisplayValue("agent-drive-api")).toBeDisabled();
    expect(screen.getByDisplayValue("API instructions")).toHaveAttribute("readonly");
    expect(screen.queryByRole("button", { name: "保存" })).not.toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "停用 agent-drive-api" })).toBeDisabled();
  });

  it("creates a custom skill and reloads the list", async () => {
    const created = { ...custom, name: "daily-brief", description: "每日简报",
      instructions: "生成每日简报", version: 1 };
    mocks.saveSkill.mockResolvedValue(created);
    mocks.listSkills.mockResolvedValueOnce(page()).mockResolvedValueOnce(page([builtin, custom, created]));
    render(<SkillsManager />);
    await screen.findByDisplayValue("agent-drive-api");

    fireEvent.click(screen.getByRole("button", { name: "新建" }));
    fireEvent.change(screen.getByLabelText("名称"), { target: { value: "daily-brief" } });
    fireEvent.change(screen.getByLabelText("说明"), { target: { value: "每日简报" } });
    fireEvent.change(screen.getByLabelText("指令"), { target: { value: "生成每日简报" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(mocks.saveSkill).toHaveBeenCalledWith("daily-brief", {
      description: "每日简报",
      instructions: "生成每日简报",
      enabled: true,
    }));
    expect((await screen.findAllByText("daily-brief")).length).toBeGreaterThanOrEqual(1);
  });

  it("toggles and deletes a custom skill", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<SkillsManager />);
    await screen.findByDisplayValue("agent-drive-api");

    fireEvent.click(screen.getByRole("switch", { name: "停用 weekly-report" }));
    await waitFor(() => expect(mocks.saveSkill).toHaveBeenCalledWith("weekly-report", {
      description: custom.description,
      instructions: custom.instructions,
      enabled: false,
    }));

    fireEvent.click(screen.getByRole("button", { name: /weekly-report/ }));
    await screen.findByDisplayValue(custom.instructions);
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    await waitFor(() => expect(mocks.deleteSkill).toHaveBeenCalledWith("weekly-report"));
    expect(confirm).toHaveBeenCalled();
  });

  it("ignores a stale detail response", async () => {
    let resolve!: (value: typeof builtin) => void;
    const stale = new Promise<typeof builtin>((done) => { resolve = done; });
    mocks.getSkill.mockImplementation((name: string) => name === builtin.name ? stale : Promise.resolve(custom));
    render(<SkillsManager />);
    await screen.findByText("weekly-report");

    fireEvent.click(screen.getByRole("button", { name: /weekly-report/ }));
    expect(await screen.findByDisplayValue(custom.instructions)).toBeInTheDocument();
    await act(async () => { resolve(builtin); });

    expect(screen.getByDisplayValue(custom.instructions)).toBeInTheDocument();
    expect(screen.queryByDisplayValue(builtin.instructions)).not.toBeInTheDocument();
  });
});
