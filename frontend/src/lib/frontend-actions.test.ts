import { describe, expect, it, vi } from "vitest";
import {
  dispatchFrontendAction,
  getFrontendCapabilities,
  isSafeFrontendPath,
  normalizeFrontendAction,
  registerFrontendActionHandler,
} from "./frontend-actions";

describe("frontend action registry", () => {
  it("exposes metadata for discovery but only normalizes registered operations", () => {
    expect(getFrontendCapabilities().map((item) => item.operation)).toEqual([
      "files.open",
      "files.show_details",
      "files.open_folder",
    ]);
    expect(normalizeFrontendAction({
      operation: "files.open",
      arguments: { path: "docs/readme.md" },
    })).toMatchObject({
      operation: "files.open",
      targetTab: "files",
    });
    expect(normalizeFrontendAction({
      operation: "files.delete",
      arguments: { path: "docs/readme.md" },
    })).toBeNull();
    expect(normalizeFrontendAction({
      operation: "files.open",
      arguments: {},
    })).toBeNull();
    expect(normalizeFrontendAction({
      operation: "files.open",
      arguments: { path: "../secret" },
    })).toBeNull();
  });

  it("rejects traversal and absolute paths", () => {
    expect(isSafeFrontendPath("docs/readme.md")).toBe(true);
    expect(isSafeFrontendPath("", true)).toBe(true);
    expect(isSafeFrontendPath("../secret")).toBe(false);
    expect(isSafeFrontendPath("/etc/passwd")).toBe(false);
    expect(isSafeFrontendPath("docs\\readme.md")).toBe(false);
  });

  it("dispatches only to the handler registered for the exact operation", async () => {
    const handler = vi.fn(async () => undefined);
    const unregister = registerFrontendActionHandler("files.open", handler);
    try {
      await expect(dispatchFrontendAction({
        id: "action-1",
        operation: "files.open",
        arguments: { path: "docs/readme.md" },
        targetTab: "files",
      })).resolves.toBe(true);
      await expect(dispatchFrontendAction({
        id: "action-2",
        operation: "files.delete",
        arguments: { path: "docs/readme.md" },
        targetTab: "files",
      })).resolves.toBe(false);
      expect(handler).toHaveBeenCalledTimes(1);
    } finally {
      unregister();
    }
  });
});
