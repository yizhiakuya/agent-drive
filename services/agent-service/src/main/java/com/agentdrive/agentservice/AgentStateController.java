package com.agentdrive.agentservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Agent Service 内部状态 API。 */
@RestController
@RequestMapping("/internal/v1")
public final class AgentStateController {
    private static final String TOKEN_HEADER = "X-Agent-Service-Token";
    private final AgentServiceProperties properties;
    private final AgentStateService service;

    public AgentStateController(AgentServiceProperties properties, AgentStateService service) {
        this.properties = properties;
        this.service = service;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "agent");
    }

    @GetMapping("/ready")
    public Map<String, Object> ready(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return service.ready();
    }

    @PostMapping("/chat/state")
    public Map<String, Object> state(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                     @RequestBody Map<String, Object> request) {
        authorize(token);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("result", service.handle(request));
        return response;
    }

    private void authorize(String token) {
        if (properties.internalToken().isBlank() || token == null || !MessageDigest.isEqual(
                properties.internalToken().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "agent service token is invalid");
        }
    }

    @ExceptionHandler(AgentStateService.AgentStateException.class)
    public ResponseEntity<Map<String, Object>> business(AgentStateService.AgentStateException error) {
        return ResponseEntity.status(error.status()).body(Map.of("ok", false, "status", error.status(),
                "code", error.code(), "detail", error.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> failure(RuntimeException error) {
        if (error instanceof ResponseStatusException status) {
            int code = status.getStatusCode().value();
            return ResponseEntity.status(code).body(Map.of("ok", false, "status", code,
                    "code", "unauthorized", "detail", "agent service token is invalid"));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("ok", false, "status", 502,
                "code", "agent_service_failure", "detail", "agent state service failed"));
    }
}
