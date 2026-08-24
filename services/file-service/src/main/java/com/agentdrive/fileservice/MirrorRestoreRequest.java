package com.agentdrive.fileservice;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** File Service 回收站恢复镜像请求。 */
public record MirrorRestoreRequest(
        @JsonProperty("owner_id") @NotBlank @Size(max = 64) String ownerId,
        @JsonProperty("trash_id") @NotBlank @Size(max = 64) String trashId,
        @JsonProperty("path") @NotBlank @Size(max = 4096) String path
) {
}
