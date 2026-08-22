package com.agentdrive.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {
    @Test
    void unexpectedErrorsKeepDetailsServerSideAndLogOnlyTheSanitizedCause(CapturedOutput output) {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/files")
                        .header(WebRequestMetadata.REQUEST_ID_HEADER, "request-error")
                        .build()
        );

        var response = handler.unexpected(
                new IllegalStateException("provider token=sk-1234567890"), exchange);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody())
                .containsEntry("ok", false)
                .containsEntry("code", "internal_error")
                .containsEntry("detail", "业务处理失败，请稍后重试");
        assertThat(output.getOut())
                .contains("api_unexpected_error request_id=request-error")
                .contains("token=[REDACTED]")
                .doesNotContain("sk-1234567890");
    }
}
