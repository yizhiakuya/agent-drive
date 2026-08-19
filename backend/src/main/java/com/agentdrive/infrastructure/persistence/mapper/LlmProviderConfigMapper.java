package com.agentdrive.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * owner 级 LLM provider 配置的 MyBatis 映射接口。
 *
 * <p>配置表以 {@code user_id} 区分 owner；API key 以加密字节和指纹形式持久化，接口不读取或写入
 * 明文密钥。
 */
@Mapper
public interface LlmProviderConfigMapper {
    /**
     * 查询 owner 保存的 LLM provider 配置。
     *
     * <p>SQL 按 {@code user_id} 精确匹配，返回 provider、基础 URL、模型、加密 API key 和 API key
     * 指纹；不会返回明文 API key。
     *
     * @param userId 配置所属 owner 的 UUID 字符串
     * @return 配置字段映射；owner 尚未保存配置时返回 {@code null}
     */
    Map<String, Object> selectByUserId(@Param("userId") String userId);

    /**
     * 插入或更新 owner 的 LLM provider 配置。
     *
     * <p>以 {@code user_id} 为冲突键；首次插入保存全部 provider 字段，冲突时更新 provider、基础
     * URL、模型、加密 API key、API key 指纹和 {@code updated_at}。
     *
     * @param userId 配置所属 owner 的 UUID 字符串
     * @param provider provider 类型标识
     * @param baseUrl provider API 的基础 URL
     * @param model 使用的模型标识
     * @param encryptedApiKey 加密后的 API key 字节
     * @param apiKeyFingerprint API key 指纹，用于识别密钥变化而不暴露密钥内容
     * @return 插入或更新的配置记录数，成功时通常为 {@code 1}
     */
    int upsert(@Param("userId") String userId,
               @Param("provider") String provider,
               @Param("baseUrl") String baseUrl,
               @Param("model") String model,
               @Param("encryptedApiKey") byte[] encryptedApiKey,
               @Param("apiKeyFingerprint") String apiKeyFingerprint);
}
