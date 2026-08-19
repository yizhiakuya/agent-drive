package com.agentdrive.infrastructure.persistence;

import com.agentdrive.devices.DeviceStore;
import com.agentdrive.infrastructure.persistence.mapper.DeviceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 通过 MyBatis 管理 owner-scoped 设备注册表。
 * <p>数据库中的同步状态以 JSON 保存，返回给上层时统一字段名并把坏 JSON 降级为空 Map；
 * 所有读写都要求明确 owner，避免设备跨用户访问。</p>
 */
public final class MybatisDeviceStore implements DeviceStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DeviceMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 保存设备 Mapper 和同步状态 JSON 映射器。
     * @param mapper 读写设备 metadata、sync_state 和 revoked_at 的 Mapper。
     * @param objectMapper 编解码 sync Map 的 Jackson 映射器。
     */
    public MybatisDeviceStore(DeviceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 列出 owner 当前未撤销的设备，并把数据库列转换为设备 API 结构。
     * @param userId 设备所属 owner 的 UUID。
     * @return 每台设备的标识、平台信息、时间戳和 sync Map。
     * @throws IllegalArgumentException userId 为空时抛出。
     */
    @Override
    public List<Map<String, Object>> list(UUID userId) {
        requireUser(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : mapper.selectActive(userId.toString())) {
            result.add(normalize(row));
        }
        return result;
    }

    /**
     * upsert 设备 metadata 和客户端同步检查点。
     * @param userId 设备所属 owner 的 UUID。
     * @param deviceId 客户端稳定的设备 ID。
     * @param name 设备显示名称。
     * @param model 设备型号。
     * @param platform 客户端平台。
     * @param appVersion 客户端版本。
     * @param sync 同步状态 Map，会被序列化为 JSON。
     * @return 规范化后的设备记录。
     */
    @Override
    public Map<String, Object> register(UUID userId, String deviceId, String name, String model,
                                        String platform, String appVersion, Map<String, Object> sync) {
        requireUser(userId);
        return normalize(mapper.upsertMetadata(
                userId.toString(), deviceId, name, model, platform, appVersion, json(sync)
        ));
    }

    /**
     * 撤销 owner 下指定设备的所有活动令牌/注册状态。
     * @param userId 设备所属 owner 的 UUID。
     * @param deviceId 客户端稳定的设备 ID。
     * @return 至少一行被更新时为 {@code true}。
     */
    @Override
    public boolean remove(UUID userId, String deviceId) {
        requireUser(userId);
        return mapper.revoke(userId.toString(), deviceId) > 0;
    }

    /**
     * 选择设备 API 对外暴露的字段，并解析 sync JSON。
     * @param row Mapper 返回的数据库列行。
     * @return 使用 snake_case 字段名的有序设备 Map。
     */
    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device_id", row.get("device_id"));
        result.put("name", valueOrEmpty(row.get("name")));
        result.put("model", valueOrEmpty(row.get("model")));
        result.put("platform", valueOrEmpty(row.get("platform")));
        result.put("app_version", valueOrEmpty(row.get("app_version")));
        result.put("first_seen", row.get("first_seen"));
        result.put("last_seen", row.get("last_seen"));
        result.put("sync", parseMap(row.get("sync_json")));
        return result;
    }

    /**
     * 把 JDBC JSON 值转换为字符串键 Map。
     * @param value 原始 Map 或 JSON 文本；空值返回空 Map，解析失败也返回空 Map。
     * @return 可供 API 使用的同步状态 Map。
     */
    private Map<String, Object> parseMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        try {
            return objectMapper.readValue(String.valueOf(value), MAP_TYPE);
        } catch (JsonProcessingException error) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 将同步状态序列化为写入数据库的 JSON。
     * @param value 同步状态 Map；空值按空对象保存。
     * @return JSON 文本。
     * @throws IllegalArgumentException Map 无法序列化时抛出。
     */
    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("sync must be valid JSON", error);
        }
    }

    /**
     * 读取设备文本列并统一空值表示。
     * @param value 数据库列值。
     * @return 空值对应空字符串，否则返回列文本。
     */
    private static String valueOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 在进入 Mapper 前阻止无 owner 的设备查询或写入。
     * @param userId 设备所属 owner 的 UUID。
     * @throws IllegalArgumentException userId 为空时抛出。
     */
    private static void requireUser(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
    }
}
