import { fireEvent, render, screen } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import Onboarding from "./Onboarding"

const mocks = vi.hoisted(() => ({
  configureLLM: vi.fn(),
  setConfigured: vi.fn(),
}))

vi.mock("@/lib/api/config", () => ({
  configureLLM: mocks.configureLLM,
}))

vi.mock("@/lib/store", () => ({
  useAppStore: (selector: (state: { setConfigured: typeof mocks.setConfigured }) => unknown) =>
    selector({ setConfigured: mocks.setConfigured }),
}))

describe("Onboarding", () => {
  it("允许查看和重新隐藏本次输入的模型 API Key", () => {
    render(<Onboarding />)

    const input = screen.getByLabelText("API Key")
    fireEvent.change(input, { target: { value: "sk-onboarding-draft" } })
    expect(input).toHaveAttribute("type", "password")

    fireEvent.click(screen.getByRole("button", { name: "显示本次输入的 API Key" }))
    expect(input).toHaveAttribute("type", "text")

    fireEvent.click(screen.getByRole("button", { name: "隐藏本次输入的 API Key" }))
    expect(input).toHaveAttribute("type", "password")
  })
})
