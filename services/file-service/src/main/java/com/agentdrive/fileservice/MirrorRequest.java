package com.agentdrive.fileservice;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** File Service 接收的单文件镜像写入请求。 */
public record MirrorRequest(
        @JsonProperty("owner_id") @NotBlank @Size(max = 64) String ownerId,
        @JsonProperty("path") @NotBlank @Size(max = 4096) String path,
        @JsonProperty("revision") long revision,
        @JsonProperty("content_md5") @NotBlank @Size(max = 32) String contentMd5,
        @JsonProperty("data") @NotBlank String data
) {
}
