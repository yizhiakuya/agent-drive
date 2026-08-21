import { describe, expect, it } from "vitest";
import { classifyBootFailure } from "./boot-errors";

describe("classifyBootFailure", () => {
  it("treats only authentication statuses as unauthorized", () => {
    expect(classifyBootFailure(401)).toBe("unauthorized");
    expect(classifyBootFailure({ status: 403 })).toBe("unauthorized");
  });

  it("treats server, network, and parsing failures as unavailable", () => {
    expect(classifyBootFailure(500)).toBe("unavailable");
    expect(classifyBootFailure(new TypeError("Failed to fetch"))).toBe("unavailable");
    expect(classifyBootFailure(new SyntaxError("bad json"))).toBe("unavailable");
  });
});
