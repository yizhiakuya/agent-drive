import { useSyncExternalStore } from "react";

export type OperationActivityStatus = "running" | "succeeded" | "partial" | "failed" | "cancelled";
export type OperationActivitySource = "ui" | "agent" | "system" | "android";

export interface OperationActivity {
  id: string;
  source: OperationActivitySource;
  kind: string;
  title: string;
  operation?: string;
  target?: string;
  status: OperationActivityStatus;
  phase: string;
  message: string;
  startedAt: number;
  updatedAt: number;
  completed?: number;
  total?: number;
  succeeded?: number;
  failed?: number;
  error?: string;
  unread: boolean;
}

export type OperationActivityInput = Omit<OperationActivity, "id" | "status" | "updatedAt" | "unread" | "startedAt"> & {
  id?: string;
  startedAt?: number;
};

const STORAGE_KEY = "agent-drive-operation-activity-v1";
const STORAGE_VERSION = 1;
const MAX_FINISHED = 30;
const SERVER_SNAPSHOT: OperationActivity[] = [];
const listeners = new Set<() => void>();
let activities: OperationActivity[] = SERVER_SNAPSHOT;
let hydrated = false;
let sequence = 0;

function createId(prefix = "operation") {
  const random = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${(++sequence).toString(36)}`;
  return `${prefix}-${random}`;
}

function isFinished(status: OperationActivityStatus) {
  return status !== "running";
}

function isActivity(value: unknown): value is OperationActivity {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<OperationActivity>;
  return typeof item.id === "string"
    && typeof item.source === "string"
    && typeof item.kind === "string"
    && typeof item.title === "string"
    && typeof item.status === "string"
    && typeof item.phase === "string"
    && typeof item.message === "string"
    && typeof item.startedAt === "number"
    && typeof item.updatedAt === "number"
    && typeof item.unread === "boolean";
}

function hydrate() {
  if (hydrated || typeof window === "undefined") return;
  hydrated = true;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw) as { version?: unknown; activities?: unknown };
    if (parsed.version !== STORAGE_VERSION || !Array.isArray(parsed.activities)) return;
    activities = parsed.activities.filter(isActivity).filter((item) => isFinished(item.status)).slice(0, MAX_FINISHED);
  } catch {
    // Storage is optional; the activity center remains usable in memory.
  }
}

function persist() {
  if (typeof window === "undefined") return;
  try {
    const finished = activities.filter((item) => isFinished(item.status)).slice(0, MAX_FINISHED);
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: STORAGE_VERSION, activities: finished }));
  } catch {
    // Storage quota/private mode must not break operation feedback.
  }
}

function notify() {
  listeners.forEach((listener) => listener());
}

function commit(next: OperationActivity[], persistFinished = true) {
  activities = next;
  if (persistFinished) persist();
  notify();
}

export function subscribeOperationActivities(listener: () => void) {
  hydrate();
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getOperationActivitiesSnapshot() {
  hydrate();
  return activities;
}

export function useOperationActivities() {
  return useSyncExternalStore(
    subscribeOperationActivities,
    getOperationActivitiesSnapshot,
    () => SERVER_SNAPSHOT,
  );
}

export function startOperationActivity(input: OperationActivityInput) {
  hydrate();
  const now = Date.now();
  const id = input.id || createId(input.kind || "operation");
  const activity: OperationActivity = {
    ...input,
    id,
    startedAt: input.startedAt ?? now,
    status: "running",
    updatedAt: now,
    unread: false,
  };
  commit([activity, ...activities.filter((item) => item.id !== id)], false);
  return id;
}

export function updateOperationActivity(id: string, patch: Partial<OperationActivity>) {
  hydrate();
  const now = Date.now();
  const current = activities.find((item) => item.id === id);
  if (!current) return;
  commit(activities.map((item) => item.id === id
    ? { ...item, ...patch, id, updatedAt: now }
    : item), false);
}

export function finishOperationActivity(
  id: string,
  status: Exclude<OperationActivityStatus, "running"> = "succeeded",
  patch: Partial<OperationActivity> = {},
) {
  hydrate();
  const current = activities.find((item) => item.id === id);
  if (!current) return;
  const now = Date.now();
  commit(activities.map((item) => item.id === id
    ? { ...item, ...patch, id, status, updatedAt: now, unread: true }
    : item));
}

export function markOperationActivitiesRead() {
  hydrate();
  if (!activities.some((item) => item.unread)) return;
  commit(activities.map((item) => item.unread ? { ...item, unread: false } : item));
}

export function removeOperationActivity(id: string) {
  hydrate();
  if (!activities.some((item) => item.id === id)) return;
  commit(activities.filter((item) => item.id !== id));
}

export function clearFinishedOperationActivities() {
  hydrate();
  if (!activities.some((item) => isFinished(item.status))) return;
  commit(activities.filter((item) => !isFinished(item.status)));
}
