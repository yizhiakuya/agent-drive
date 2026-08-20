import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./select";
import {
  Combobox,
  ComboboxContent,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
} from "./combobox";

describe("Select", () => {
  const originalScrollIntoView = Element.prototype.scrollIntoView;

  beforeEach(() => {
    Element.prototype.scrollIntoView = () => {};
  });

  afterEach(() => {
    Element.prototype.scrollIntoView = originalScrollIntoView;
  });

  it("keeps the focused option readable with the brand theme", () => {
    render(
      <Select open value="high" onValueChange={() => {}}>
        <SelectTrigger aria-label="思考等级">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="auto">自动</SelectItem>
          <SelectItem value="high">深度</SelectItem>
        </SelectContent>
      </Select>,
    );

    // Radix temporarily aria-hides the app root while its portal is open.
    // Query the trigger directly so this assertion remains stable in jsdom.
    const trigger = document.querySelector<HTMLElement>('[data-slot="select-trigger"]');
    expect(trigger).not.toBeNull();
    if (!trigger) return;
    expect(trigger).toHaveClass("focus-visible:border-accent2", "focus-visible:ring-2", "focus-visible:ring-accent-soft");
    expect(trigger).not.toHaveClass("focus-visible:border-ring", "focus-visible:ring-3", "focus-visible:ring-ring/50");

    const selected = screen.getByRole("option", { name: "深度" });
    expect(selected).toHaveAttribute("data-state", "checked");
    expect(selected).toHaveClass("focus:bg-accent-soft", "focus:text-text");
    expect(selected).toHaveClass("data-[highlighted]:bg-accent-soft", "data-[highlighted]:text-text");
    expect(selected).not.toHaveClass("focus:bg-accent", "focus:text-accent-foreground");
  });

  it("keeps highlighted combobox options readable with the brand theme", () => {
    render(
      <Combobox
        open
        value="high"
        items={[{ value: "auto", label: "自动" }, { value: "high", label: "深度" }]}
        onValueChange={() => {}}
      >
        <ComboboxInput aria-label="模型" />
        <ComboboxContent>
          <ComboboxList>
            {(item) => <ComboboxItem value={String(item.value)}>{String(item.label)}</ComboboxItem>}
          </ComboboxList>
        </ComboboxContent>
      </Combobox>,
    );

    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(2);
    expect(options[0]).toHaveClass("data-highlighted:bg-accent-soft", "data-highlighted:text-text");
    expect(options[0]).not.toHaveClass("data-highlighted:bg-accent", "data-highlighted:text-accent-foreground");
  });
});
