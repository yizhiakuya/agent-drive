package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;
import com.agentdrive.devices.DeviceStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("java-chat")
final class ChatBackendApiDeviceHandler implements BackendApiOperationHandler {
    private static final Set<String> OPERATIONS = Set.of(
            "GET /api/v1/devices",
            "POST /api/v1/devices/register",
            "DELETE /api/v1/devices/{device_id}"
    );

    private final DeviceStore devices;

    ChatBackendApiDeviceHandler(DeviceStore devices) {
        this.devices = devices;
    }

    @Override
    public Set<String> operations() {
        return OPERATIONS;
    }

    @Override
    public Map<String, Object> dispatch(String operation, BackendApiRequest request, UUID userId) {
        return switch (operation) {
            case "GET /api/v1/devices" -> Map.of("devices", devices.list(userId));
            case "POST /api/v1/devices/register" -> devices.register(
                    userId,
                    BackendApiParams.required(request, "device_id"),
                    BackendApiParams.parameter(request, "name", ""),
                    BackendApiParams.parameter(request, "model", ""),
                    BackendApiParams.parameter(request, "platform", "android"),
                    BackendApiParams.parameter(request, "app_version", ""),
                    BackendApiParams.mapParameter(request, "sync"));
            case "DELETE /api/v1/devices/{device_id}" -> removeDevice(request, userId);
            default -> throw new IllegalArgumentException("Unsupported device operation: " + operation);
        };
    }

    private Map<String, Object> removeDevice(BackendApiRequest request, UUID userId) {
        String deviceId = BackendApiParams.required(request, "device_id");
        if (!devices.remove(userId, deviceId)) return Map.of("ok", false, "error", "device_not_found");
        return Map.of("removed", deviceId, "tokens_revoked", true);
    }
}
