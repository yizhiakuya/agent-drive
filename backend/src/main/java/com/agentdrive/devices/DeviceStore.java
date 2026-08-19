package com.agentdrive.devices;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按用户隔离保存设备注册信息及同步状态的持久化接口。
 * 实现必须保证设备查询、注册和撤销都以 {@code userId} 为边界，不能让一个用户读取或修改另一个用户的设备。
 */
public interface DeviceStore {
    /**
     * 查询用户当前登记的全部设备，并返回设备标识、客户端元数据、同步状态及撤销状态等字段。
     * 返回结果供设备管理 API 直接展示；实现不应返回其他用户的设备，也不应在此方法中修改注册表。
     *
     * @param userId 设备归属用户的 UUID。
     * @return 该用户的设备记录列表；没有设备时返回空列表。
     */
    List<Map<String, Object>> list(UUID userId);

    /**
     * 创建或更新用户的设备注册记录，并保存客户端信息和同步状态。
     * 同一用户的同一 {@code deviceId} 应被视为同一设备再次注册，而不是产生重复记录；返回值是持久化后的设备表示。
     *
     * @param userId 设备归属用户的 UUID。
     * @param deviceId 客户端提供的稳定设备标识，用于幂等匹配注册记录。
     * @param name 客户端显示名称。
     * @param model 设备型号。
     * @param platform 客户端运行平台。
     * @param appVersion 客户端版本号。
     * @param sync 客户端上报的同步配置或检查点，具体字段由设备同步契约定义。
     * @return 注册后的设备记录，包含服务端生成或更新的状态字段。
     */
    Map<String, Object> register(UUID userId,
                                  String deviceId,
                                  String name,
                                  String model,
                                  String platform,
                                  String appVersion,
                                  Map<String, Object> sync);

    /**
     * 撤销用户的一台设备，使其后续不能再作为有效设备使用。
     * 实现应以用户和设备的联合条件定位记录，并将撤销状态持久化；找不到记录时返回 {@code false}。
     *
     * @param userId 设备归属用户的 UUID。
     * @param deviceId 要撤销的设备标识。
     * @return 成功找到并撤销记录时为 {@code true}，记录不存在时为 {@code false}。
     */
    boolean remove(UUID userId, String deviceId);
}
