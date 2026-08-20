import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.fn();
vi.mock("./client", () => ({ api: (...args: unknown[]) => api(...args) }));

import { deleteSkill, getSkill, listSkills, saveSkill } from "./skills";

describe("skills api", () => {
  beforeEach(() => vi.clearAllMocks());

  it("encodes list and detail paths", async () => {
    api.mockResolvedValueOnce({ skills: [] }).mockResolvedValueOnce({ skill: { name: "a b" } });

    await listSkills("周报 skill", 10, 20, true);
    await getSkill("a b");

    expect(api.mock.calls[0][0]).toBe("/skills?q=%E5%91%A8%E6%8A%A5+skill&offset=10&limit=20&include_disabled=true");
    expect(api.mock.calls[1][0]).toBe("/skills/a%20b");
  });

  it("uses PUT and DELETE for mutations", async () => {
    api.mockResolvedValueOnce({ skill: { name: "weekly-report" } }).mockResolvedValueOnce({});
    const input = { description: "周报", instructions: "生成周报", enabled: true };

    await saveSkill("weekly-report", input);
    await deleteSkill("weekly-report");

    expect(api.mock.calls[0]).toEqual(["/skills/weekly-report", {
      method: "PUT",
      body: JSON.stringify(input),
    }]);
    expect(api.mock.calls[1]).toEqual(["/skills/weekly-report", { method: "DELETE" }]);
  });
});
