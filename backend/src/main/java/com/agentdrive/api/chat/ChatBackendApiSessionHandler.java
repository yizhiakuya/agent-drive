package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.api.automation.AutomationReportService;
import com.agentdrive.auth.ConversationSessionService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("java-chat")
final class ChatBackendApiSessionHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "GET /api/v1/automation/latest",
            "GET /api/v1/sessions",
            "GET /api/v1/sessions/{sessionId}",
            "POST /api/v1/sessions/{sessionId}/summarize",
            "DELETE /api/v1/sessions/{sessionId}"
    );

    private final ConversationSessionService sessions;
    private final AutomationReportService automation;

    ChatBackendApiSessionHandler(ConversationSessionService sessions, AutomationReportService automation) {
        this.sessions = sessions;
        this.automation = automation;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
        return switch (operation) {
            case "GET /api/v1/automation/latest" -> automation.latestFor(userId);
            case "GET /api/v1/sessions" -> Map.of("sessions", sessions.listOwned(userId));
            case "GET /api/v1/sessions/{sessionId}" -> sessionDetails(request, userId);
            case "POST /api/v1/sessions/{sessionId}/summarize" -> sessions.summarizeOwned(
                    userId, BackendApiParams.requiredPath(request, "sessionId"));
            case "DELETE /api/v1/sessions/{sessionId}" -> deleteSession(request, userId);
            default -> throw new IllegalArgumentException("Unsupported session operation: " + operation);
        };
    }

    private Map<String, Object> sessionDetails(BackendApiRequest request, UUID userId) {
        ConversationSessionService.SessionDetails details = sessions.getOwned(
                userId, BackendApiParams.requiredPath(request, "sessionId"));
        return Map.of("meta", details.meta(), "messages", details.messages());
    }

    private Map<String, Object> deleteSession(BackendApiRequest request, UUID userId) {
        String sessionId = BackendApiParams.requiredPath(request, "sessionId");
        sessions.deleteOwned(userId, sessionId);
        return Map.of("deleted", sessionId);
    }
}
