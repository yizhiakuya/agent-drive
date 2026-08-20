import { beforeEach, describe, expect, it } from "vitest";
import {
  WORKSPACE_LAYOUT_STORAGE_KEY,
  clampWorkspacePanelWidth,
  loadWorkspaceLayout,
  parseWorkspaceLayout,
  saveWorkspaceLayout,
} from "./workspace-layout";

describe("workspace layout persistence", () => {
  beforeEach(() => localStorage.clear());

  it("clamps invalid widths while preserving valid collapsed state", () => {
    const layout = parseWorkspaceLayout({
      sessions: { collapsed: true, width: 9999 },
      files: { collapsed: false, width: 1 },
    });

    expect(layout.sessions).toEqual({ collapsed: true, width: 360 });
    expect(layout.files).toEqual({ collapsed: false, width: 260 });
    expect(clampWorkspacePanelWidth("sessions", Number.NaN)).toBe(220);
  });

  it("persists and restores both panel states", () => {
    const layout = parseWorkspaceLayout({
      sessions: { collapsed: true, width: 300 },
      files: { collapsed: false, width: 420 },
    });

    saveWorkspaceLayout(layout, localStorage);
    expect(localStorage.getItem(WORKSPACE_LAYOUT_STORAGE_KEY)).toContain("300");
    expect(loadWorkspaceLayout(localStorage)).toEqual(layout);
  });
});
