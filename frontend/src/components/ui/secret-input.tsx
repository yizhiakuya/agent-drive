"use client"

import * as React from "react"
import { EyeIcon, EyeOffIcon, LoaderCircleIcon } from "lucide-react"

import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group"

type SecretInputProps = Omit<React.ComponentProps<"input">, "type"> & {
  storedSecretAvailable?: boolean
  revealStoredSecret?: () => Promise<boolean>
}

function SecretInput({
  value,
  className,
  disabled,
  storedSecretAvailable = false,
  revealStoredSecret,
  ...props
}: SecretInputProps) {
  const [visible, setVisible] = React.useState(false)
  const [revealing, setRevealing] = React.useState(false)
  const revealRequestRef = React.useRef(0)

  React.useEffect(() => {
    if (value == null || value === "") setVisible(false)
  }, [value])

  React.useEffect(() => {
    if (!storedSecretAvailable) {
      revealRequestRef.current += 1
      setRevealing(false)
    }
  }, [storedSecretAvailable])

  React.useEffect(() => () => {
    revealRequestRef.current += 1
  }, [])

  const hasDraft = value != null && value !== ""
  const canRevealStored = storedSecretAvailable && revealStoredSecret != null
  const actionLabel = revealing
    ? "正在读取已保存的 API Key"
    : visible
      ? "隐藏 API Key"
      : hasDraft
        ? "显示本次输入的 API Key"
        : canRevealStored
          ? "显示已保存的 API Key"
          : "显示本次输入的 API Key"

  async function toggleVisibility() {
    if (visible) {
      setVisible(false)
      return
    }
    if (hasDraft) {
      setVisible(true)
      return
    }
    if (!canRevealStored) return

    const request = ++revealRequestRef.current
    setRevealing(true)
    try {
      const revealed = await revealStoredSecret()
      if (request === revealRequestRef.current && revealed) setVisible(true)
    } catch {
      // The owning form reports reveal failures in its existing alert surface.
    } finally {
      if (request === revealRequestRef.current) setRevealing(false)
    }
  }

  return (
    <InputGroup>
      <InputGroupInput
        {...props}
        type={visible ? "text" : "password"}
        value={value}
        disabled={disabled}
        className={className}
      />
      <InputGroupAddon align="inline-end">
        <InputGroupButton
          size="icon-xs"
          aria-label={actionLabel}
          aria-pressed={visible}
          aria-busy={revealing || undefined}
          title={actionLabel}
          disabled={disabled || revealing || (!hasDraft && !canRevealStored)}
          onClick={() => { void toggleVisibility() }}
        >
          {revealing
            ? <LoaderCircleIcon className="animate-spin" aria-hidden="true" />
            : visible
              ? <EyeOffIcon aria-hidden="true" />
              : <EyeIcon aria-hidden="true" />}
        </InputGroupButton>
      </InputGroupAddon>
    </InputGroup>
  )
}

export { SecretInput }
