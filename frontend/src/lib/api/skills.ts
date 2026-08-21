import { api } from "./client";

export interface SkillSummary {
  name: string;
  description: string;
  enabled: boolean;
  source: "builtin" | "custom";
  version: number;
  updated_at: number | null;
}

export interface SkillDefinition extends SkillSummary {
  instructions: string;
  created_at: number | null;
}

export interface SkillPage {
  skills: SkillSummary[];
  total_matches: number;
  returned: number;
  offset: number;
  limit: number;
  has_more: boolean;
  next_offset: number;
}

export interface SkillInput {
  description: string;
  instructions: string;
  enabled: boolean;
}

export async function listSkills(query = "", offset = 0, limit = 50,
                                 includeDisabled = true): Promise<SkillPage> {
  const params = new URLSearchParams({
    q: query,
    offset: String(offset),
    limit: String(limit),
    include_disabled: String(includeDisabled),
  });
  return api<SkillPage>(`/skills?${params.toString()}`);
}

export async function getSkill(name: string): Promise<SkillDefinition> {
  const response = await api<{ skill: SkillDefinition }>(`/skills/${encodeURIComponent(name)}`);
  return response.skill;
}

export async function saveSkill(name: string, input: SkillInput): Promise<SkillDefinition> {
  const response = await api<{ skill: SkillDefinition }>(`/skills/${encodeURIComponent(name)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
  return response.skill;
}

export async function deleteSkill(name: string): Promise<void> {
  await api(`/skills/${encodeURIComponent(name)}`, { method: "DELETE" });
}
