import { fireEvent, render, screen, waitFor } from "@testing-library/react"
import { describe, expect, it, vi } from "vitest"

import { SecretInput } from "./secret-input"

describe("SecretInput", () => {
  it("在没有草稿时按需读取并显示已保存的 API Key", async () => {
    const revealStoredSecret = vi.fn().mockResolvedValue(true)
    const onChange = vi.fn()
    const { rerender } = render(
      <SecretInput aria-label="API Key" value="" onChange={onChange}
                   storedSecretAvailable revealStoredSecret={revealStoredSecret} />
    )

    const revealButton = screen.getByRole("button", { name: "显示已保存的 API Key" })
    expect(revealButton).toBeEnabled()
    fireEvent.click(revealButton)
    await waitFor(() => expect(revealStoredSecret).toHaveBeenCalledOnce())

    rerender(
      <SecretInput aria-label="API Key" value="saved-secret" onChange={onChange}
                   storedSecretAvailable revealStoredSecret={revealStoredSecret} />
    )
    await waitFor(() => expect(screen.getByLabelText("API Key")).toHaveAttribute("type", "text"))
  })

  it("只显示当前草稿，并在草稿清空后恢复隐藏", () => {
    const onChange = vi.fn()
    const { rerender } = render(
      <SecretInput aria-label="API Key" value="sk-draft" onChange={onChange} />
    )

    const input = screen.getByLabelText("API Key")
    expect(input).toHaveAttribute("type", "password")

    fireEvent.click(screen.getByRole("button", { name: "显示本次输入的 API Key" }))
    expect(input).toHaveAttribute("type", "text")

    fireEvent.click(screen.getByRole("button", { name: "隐藏 API Key" }))
    expect(input).toHaveAttribute("type", "password")

    fireEvent.click(screen.getByRole("button", { name: "显示本次输入的 API Key" }))
    rerender(<SecretInput aria-label="API Key" value="" onChange={onChange} />)
    rerender(<SecretInput aria-label="API Key" value="sk-next-draft" onChange={onChange} />)

    expect(input).toHaveAttribute("type", "password")
  })
})
