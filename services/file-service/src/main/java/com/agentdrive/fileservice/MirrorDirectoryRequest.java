package com.agentdrive.fileservice;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** File Service 接收的目录镜像请求。 */
public record MirrorDirectoryRequest(
        @JsonProperty("owner_id") @NotBlank @Size(max = 64) String ownerId,
        @JsonProperty("path") @NotBlank @Size(max = 4096) String path
) {
}
