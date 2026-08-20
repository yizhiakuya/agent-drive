"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { deleteSkill, getSkill, listSkills, saveSkill, type SkillDefinition, type SkillSummary } from "@/lib/api/skills";
import { EV, emitToast } from "@/lib/events";
import { cn } from "@/lib/utils";
import { BookOpen, Plus, RefreshCw, Save, Search, Trash2 } from "lucide-react";

const EMPTY_FORM = { name: "", description: "", instructions: "", enabled: true };
const NAME_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/;

export default function SkillsManager() {
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const [queryInput, setQueryInput] = useState("");
  const [query, setQuery] = useState("");
  const [hasMore, setHasMore] = useState(false);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [busyName, setBusyName] = useState<string | null>(null);
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const [detail, setDetail] = useState<SkillDefinition | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState<string | null>(null);
  const listRequestRef = useRef(0);
  const detailRequestRef = useRef(0);
  const skillsRef = useRef<SkillSummary[]>([]);
  const selectedNameRef = useRef<string | null>(null);
  const creatingRef = useRef(false);
  const nextOffsetRef = useRef(0);

  const openSkill = useCallback(async (name: string) => {
    const request = ++detailRequestRef.current;
    selectedNameRef.current = name;
    creatingRef.current = false;
    setSelectedName(name);
    setCreating(false);
    setDetailLoading(true);
    setError(null);
    try {
      const skill = await getSkill(name);
      if (request !== detailRequestRef.current) return;
      setDetail(skill);
      setForm({ name: skill.name, description: skill.description,
        instructions: skill.instructions, enabled: skill.enabled });
    } catch (reason) {
      if (request === detailRequestRef.current) setError(`Skill 加载失败：${String(reason)}`);
    } finally {
      if (request === detailRequestRef.current) setDetailLoading(false);
    }
  }, []);

  const load = useCallback(async (reset: boolean, requestedQuery: string) => {
    const request = ++listRequestRef.current;
    const offset = reset ? 0 : nextOffsetRef.current;
    setLoading(true);
    setError(null);
    try {
      const page = await listSkills(requestedQuery, offset, 50, true);
      if (request !== listRequestRef.current) return;
      const next = reset ? page.skills : [...skillsRef.current, ...page.skills];
      skillsRef.current = next;
      nextOffsetRef.current = page.next_offset;
      setSkills(next);
      setHasMore(page.has_more);
      setTotal(page.total_matches);
      const selected = selectedNameRef.current;
      if (reset && page.skills.length === 0 && !creatingRef.current) {
        detailRequestRef.current += 1;
        selectedNameRef.current = null;
        setSelectedName(null);
        setDetail(null);
        setForm(EMPTY_FORM);
      }
      if (reset && page.skills.length > 0 && !creatingRef.current
          && (!selected || !page.skills.some((skill) => skill.name === selected))) {
        void openSkill(page.skills[0].name);
      }
    } catch (reason) {
      if (request === listRequestRef.current) setError(`Skill 列表加载失败：${String(reason)}`);
    } finally {
      if (request === listRequestRef.current) setLoading(false);
    }
  }, [openSkill]);

  useEffect(() => { void load(true, ""); }, [load]);
  useEffect(() => () => {
    listRequestRef.current += 1;
    detailRequestRef.current += 1;
  }, []);
  useEffect(() => {
    const refresh = () => { void load(true, query); };
    window.addEventListener(EV.refresh, refresh);
    return () => window.removeEventListener(EV.refresh, refresh);
  }, [load, query]);

  function beginCreate() {
    detailRequestRef.current += 1;
    selectedNameRef.current = null;
    creatingRef.current = true;
    setCreating(true);
    setSelectedName(null);
    setDetail(null);
    setForm(EMPTY_FORM);
    setError(null);
  }

  function submitSearch(event: React.FormEvent) {
    event.preventDefault();
    const value = queryInput.trim();
    setQuery(value);
    void load(true, value);
  }

  async function persist() {
    const name = form.name.trim().toLowerCase();
    if (!NAME_PATTERN.test(name)) {
      setError("Skill 名称需使用 1-64 位小写字母、数字或中划线");
      return;
    }
    if (!form.description.trim() || !form.instructions.trim()) {
      setError("说明和指令不能为空");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const saved = await saveSkill(name, {
        description: form.description.trim(), instructions: form.instructions.trim(), enabled: form.enabled,
      });
      emitToast({ kind: "ok", text: `Skill ${saved.name} 已保存` });
      creatingRef.current = false;
      selectedNameRef.current = saved.name;
      setCreating(false);
      setSelectedName(saved.name);
      setDetail(saved);
      setForm({ name: saved.name, description: saved.description,
        instructions: saved.instructions, enabled: saved.enabled });
      await load(true, query);
    } catch (reason) {
      setError(`Skill 保存失败：${String(reason)}`);
    } finally {
      setSaving(false);
    }
  }

  async function toggleSkill(skill: SkillSummary, enabled: boolean) {
    if (skill.source === "builtin") return;
    setBusyName(skill.name);
    setError(null);
    try {
      const current = detail?.name === skill.name ? detail : await getSkill(skill.name);
      const saved = await saveSkill(skill.name, {
        description: current.description, instructions: current.instructions, enabled,
      });
      if (selectedNameRef.current === skill.name) {
        setDetail(saved);
        setForm((value) => ({ ...value, enabled: saved.enabled }));
      }
      await load(true, query);
    } catch (reason) {
      setError(`Skill 状态更新失败：${String(reason)}`);
    } finally {
      setBusyName(null);
    }
  }

  async function remove() {
    if (!detail || detail.source === "builtin") return;
    if (!window.confirm(`删除 Skill ${detail.name}？`)) return;
    setBusyName(detail.name);
    setError(null);
    try {
      await deleteSkill(detail.name);
      emitToast({ kind: "ok", text: `Skill ${detail.name} 已删除` });
      selectedNameRef.current = null;
      setSelectedName(null);
      setDetail(null);
      setForm(EMPTY_FORM);
      await load(true, query);
    } catch (reason) {
      setError(`Skill 删除失败：${String(reason)}`);
    } finally {
      setBusyName(null);
    }
  }

  const readOnly = detail?.source === "builtin" && !creating;

  return (
    <section className="border-b border-border py-5" aria-labelledby="skills-heading">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 id="skills-heading" className="flex items-center gap-2 text-sm font-bold">
            <BookOpen className="size-4 text-muted" aria-hidden="true" /> Skills
          </h3>
          <p className="mt-1 text-xs text-muted">{total} 个定义</p>
        </div>
        <div className="flex items-center gap-1.5">
          <Button variant="ghost" size="sm" onClick={() => void load(true, query)}
                  disabled={loading} title="刷新 Skills" aria-label="刷新 Skills">
            <RefreshCw data-icon="inline-start" className={loading ? "animate-spin" : undefined} />
          </Button>
          <Button size="sm" onClick={beginCreate} disabled={saving}>
            <Plus data-icon="inline-start" /> 新建
          </Button>
        </div>
      </div>

      {error && <Alert variant="destructive" className="mb-3 text-xs">{error}</Alert>}

      <div className="grid min-h-96 grid-cols-1 gap-4 lg:grid-cols-[minmax(15rem,0.8fr)_minmax(0,1.6fr)]">
        <div className="min-w-0 border border-border bg-panel">
          <form className="flex gap-1.5 border-b border-border p-2" onSubmit={submitSearch} role="search">
            <Input value={queryInput} onChange={(event) => setQueryInput(event.target.value)}
                   placeholder="搜索 Skills" aria-label="搜索 Skills" />
            <Button type="submit" variant="outline" size="icon-sm" aria-label="搜索"><Search /></Button>
          </form>
          <div className="max-h-[32rem] overflow-auto p-1.5">
            {skills.length === 0 && !loading ? (
              <Alert className="text-xs">没有匹配的 Skill</Alert>
            ) : skills.map((skill) => (
              <div key={skill.name}
                   className={cn("flex items-center gap-2 border-b border-border/60 px-2 py-2 last:border-b-0",
                     selectedName === skill.name && "bg-card")}>
                <Button type="button" variant="ghost" onClick={() => void openSkill(skill.name)}
                        className="h-auto min-w-0 flex-1 justify-start px-0 py-0 text-left hover:bg-transparent">
                  <span className="flex min-w-0 items-center gap-1.5">
                    <span className="truncate text-sm font-medium">{skill.name}</span>
                    <Badge variant={skill.source === "builtin" ? "secondary" : "outline"}>
                      {skill.source === "builtin" ? "内置" : `v${skill.version}`}
                    </Badge>
                  </span>
                  <span className="mt-0.5 block line-clamp-2 text-xs text-muted">{skill.description}</span>
                </Button>
                <Switch checked={skill.enabled} disabled={skill.source === "builtin" || busyName === skill.name}
                        onCheckedChange={(checked) => void toggleSkill(skill, checked)}
                        aria-label={`${skill.enabled ? "停用" : "启用"} ${skill.name}`} />
              </div>
            ))}
          </div>
          {hasMore && (
            <div className="border-t border-border p-2">
              <Button variant="outline" size="sm" className="w-full" onClick={() => void load(false, query)}
                      disabled={loading}>加载更多</Button>
            </div>
          )}
        </div>

        <div className="min-w-0 border border-border bg-panel p-3 sm:p-4">
          {detailLoading ? <p className="text-sm text-muted">加载中…</p> : (
            <FieldGroup>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="min-w-0">
                  <h4 className="truncate text-sm font-bold">{creating ? "新建 Skill" : detail?.name || "Skill"}</h4>
                  {!creating && detail && <p className="text-xs text-muted">{detail.source === "builtin" ? "内置只读" : `版本 ${detail.version}`}</p>}
                </div>
                {!readOnly && (
                  <div className="flex items-center gap-1.5">
                    {!creating && detail && (
                      <Button variant="destructive" size="sm" onClick={() => void remove()}
                              disabled={busyName === detail.name || saving}>
                        <Trash2 data-icon="inline-start" /> 删除
                      </Button>
                    )}
                    <Button size="sm" onClick={() => void persist()} disabled={saving}>
                      <Save data-icon="inline-start" /> {saving ? "保存中…" : "保存"}
                    </Button>
                  </div>
                )}
              </div>

              <Field data-disabled={!creating}>
                <FieldLabel htmlFor="skill-name">名称</FieldLabel>
                <Input id="skill-name" value={form.name} disabled={!creating}
                       aria-invalid={creating && form.name.length > 0 && !NAME_PATTERN.test(form.name)}
                       onChange={(event) => setForm((value) => ({ ...value, name: event.target.value.toLowerCase() }))}
                       placeholder="weekly-report" maxLength={64} />
                <FieldDescription>小写 slug，保存后不可改名</FieldDescription>
              </Field>

              <Field data-disabled={readOnly}>
                <FieldLabel htmlFor="skill-description">说明</FieldLabel>
                <Input id="skill-description" value={form.description} readOnly={readOnly}
                       onChange={(event) => setForm((value) => ({ ...value, description: event.target.value }))}
                       maxLength={500} />
              </Field>

              <Field data-disabled={readOnly}>
                <FieldLabel htmlFor="skill-instructions">指令</FieldLabel>
                <Textarea id="skill-instructions" value={form.instructions} readOnly={readOnly}
                          onChange={(event) => setForm((value) => ({ ...value, instructions: event.target.value }))}
                          className="min-h-72 resize-y font-mono text-xs leading-relaxed" maxLength={16000} />
                <FieldDescription>{form.instructions.length} / 16000</FieldDescription>
              </Field>

              {!readOnly && (
                <Field orientation="horizontal">
                  <div className="flex-1"><FieldLabel htmlFor="skill-enabled">启用</FieldLabel></div>
                  <Switch id="skill-enabled" checked={form.enabled}
                          onCheckedChange={(checked) => setForm((value) => ({ ...value, enabled: checked }))} />
                </Field>
              )}
            </FieldGroup>
          )}
        </div>
      </div>
    </section>
  );
}
