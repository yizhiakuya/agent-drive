"use client"

import * as React from "react"
import { EyeIcon, EyeOffIcon } from "lucide-react"

import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
} from "@/components/ui/input-group"

type SecretInputProps = Omit<React.ComponentProps<"input">, "type">

function SecretInput({
  value,
  className,
  disabled,
  ...props
}: SecretInputProps) {
  const [visible, setVisible] = React.useState(false)

  React.useEffect(() => {
    if (value == null || value === "") setVisible(false)
  }, [value])

  const actionLabel = visible ? "隐藏本次输入的 API Key" : "显示本次输入的 API Key"

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
          title={actionLabel}
          disabled={disabled || value == null || value === ""}
          onClick={() => setVisible((current) => !current)}
        >
          {visible ? <EyeOffIcon aria-hidden="true" /> : <EyeIcon aria-hidden="true" />}
        </InputGroupButton>
      </InputGroupAddon>
    </InputGroup>
  )
}

export { SecretInput }
