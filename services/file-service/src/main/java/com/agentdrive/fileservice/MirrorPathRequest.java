package com.agentdrive.fileservice;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** File Service 接收的目录/路径镜像变更请求。 */
public record MirrorPathRequest(
        @JsonProperty("owner_id") @NotBlank @Size(max = 64) String ownerId,
        @JsonProperty("source") @NotBlank @Size(max = 4096) String source,
        @JsonProperty("destination") @NotBlank @Size(max = 4096) String destination,
        @JsonProperty("overwrite") boolean overwrite
) {
}
