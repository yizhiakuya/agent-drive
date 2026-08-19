package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared conversion rules for backend_api path, query and body fields. */
final class BackendApiParams {
    private BackendApiParams() {
    }

    static String requiredPath(BackendApiRequest request, String name) {
        String value = request.pathParams().get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    static String required(BackendApiRequest request, String name) {
        String value = parameter(request, name, "");
        if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    static String parameter(BackendApiRequest request, String name, String fallback) {
        Object value = request.queryParams().get(name);
        if (value == null) value = request.body().get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    static boolean booleanParameter(BackendApiRequest request, String name) {
        return Boolean.parseBoolean(parameter(request, name, "false"));
    }

    static int integerParameter(BackendApiRequest request, String name, int fallback) {
        return Integer.parseInt(parameter(request, name, String.valueOf(fallback)));
    }

    static Map<String, Object> mapParameter(BackendApiRequest request, String name) {
        Object value = request.body().get(name);
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}
