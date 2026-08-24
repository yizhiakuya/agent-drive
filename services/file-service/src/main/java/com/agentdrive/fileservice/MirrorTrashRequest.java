package com.agentdrive.fileservice;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** File Service 回收站镜像请求。 */
public record MirrorTrashRequest(
        @JsonProperty("owner_id") @NotBlank @Size(max = 64) String ownerId,
        @JsonProperty("path") @NotBlank @Size(max = 4096) String path,
        @JsonProperty("trash_id") @NotBlank @Size(max = 64) String trashId
) {
}
