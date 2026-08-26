package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Owner-scoped implementation for one domain's registered backend operations. */
interface BackendApiOperationHandler {
    Set<String> operations();

    Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId);

    /** Owner-scoped dispatch with optional live business progress. */
    default Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId,
                                          Consumer<Map<String, Object>> progressListener) {
        return dispatch(operation, request, userId);
    }
}
