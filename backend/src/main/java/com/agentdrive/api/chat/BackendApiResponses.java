package com.agentdrive.api.chat;

import com.agentdrive.agent.OperationDefinition;

import java.util.Map;

final class BackendApiResponses {
    private BackendApiResponses() {
    }

    static Map<String, Object> missingOwner() {
        return Map.of("ok", false, "error", "missing_authenticated_owner");
    }

    static Map<String, Object> notImplemented(OperationDefinition operation) {
        return Map.of(
                "ok", false,
                "error", "operation_not_implemented",
                "operation", operation.operation()
        );
    }
}
