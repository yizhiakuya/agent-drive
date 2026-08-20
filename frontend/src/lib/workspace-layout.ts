export type WorkspacePanel = "sessions" | "files";

export interface WorkspacePanelLayout {
  collapsed: boolean;
  width: number;
}

export interface WorkspaceLayout {
  sessions: WorkspacePanelLayout;
  files: WorkspacePanelLayout;
}

export const WORKSPACE_LAYOUT_STORAGE_KEY = "agent-drive-workspace-layout-v1";

export const WORKSPACE_PANEL_LIMITS: Record<WorkspacePanel, {
  min: number;
  max: number;
  defaultWidth: number;
  collapsedWidth: number;
}> = {
  sessions: { min: 220, max: 360, defaultWidth: 240, collapsedWidth: 48 },
  files: { min: 260, max: 460, defaultWidth: 320, collapsedWidth: 48 },
};

export function createDefaultWorkspaceLayout(): WorkspaceLayout {
  return {
    sessions: { collapsed: false, width: WORKSPACE_PANEL_LIMITS.sessions.defaultWidth },
    files: { collapsed: false, width: WORKSPACE_PANEL_LIMITS.files.defaultWidth },
  };
}

export function clampWorkspacePanelWidth(panel: WorkspacePanel, width: number): number {
  const { min, max } = WORKSPACE_PANEL_LIMITS[panel];
  const normalized = Number.isFinite(width) ? width : min;
  return Math.min(max, Math.max(min, Math.round(normalized)));
}

function readPanelLayout(panel: WorkspacePanel, value: unknown): WorkspacePanelLayout {
  const defaults = createDefaultWorkspaceLayout()[panel];
  if (typeof value !== "object" || value === null) return defaults;
  const candidate = value as { collapsed?: unknown; width?: unknown };
  return {
    collapsed: typeof candidate.collapsed === "boolean" ? candidate.collapsed : defaults.collapsed,
    width: typeof candidate.width === "number" && Number.isFinite(candidate.width)
      ? clampWorkspacePanelWidth(panel, candidate.width)
      : defaults.width,
  };
}

export function parseWorkspaceLayout(value: unknown): WorkspaceLayout {
  if (typeof value !== "object" || value === null) return createDefaultWorkspaceLayout();
  const candidate = value as { sessions?: unknown; files?: unknown };
  return {
    sessions: readPanelLayout("sessions", candidate.sessions),
    files: readPanelLayout("files", candidate.files),
  };
}

export function loadWorkspaceLayout(storage?: Storage): WorkspaceLayout {
  if (!storage) return createDefaultWorkspaceLayout();
  try {
    const raw = storage.getItem(WORKSPACE_LAYOUT_STORAGE_KEY);
    return raw ? parseWorkspaceLayout(JSON.parse(raw)) : createDefaultWorkspaceLayout();
  } catch {
    return createDefaultWorkspaceLayout();
  }
}

export function saveWorkspaceLayout(layout: WorkspaceLayout, storage?: Storage): void {
  if (!storage) return;
  try {
    storage.setItem(WORKSPACE_LAYOUT_STORAGE_KEY, JSON.stringify(parseWorkspaceLayout(layout)));
  } catch {
    // Private browsing and full storage quotas should not break the workspace.
  }
}
