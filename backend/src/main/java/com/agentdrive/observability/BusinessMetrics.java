package com.agentdrive.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;

/**
 * 统一输出轻量业务指标日志。
 *
 * <p>指标只进入现有日志链路，不创建任务、队列或新的持久化表；字段不包含
 * 文件正文、完整路径、凭据或查询原文，便于后续由日志系统聚合成功率和耗时。</p>
 */
public final class BusinessMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessMetrics.class);

    private BusinessMetrics() {
    }

    /**
     * 记录一次索引类业务操作的批量结果和耗时。
     *
     * @param operation text、vision 或 vector
     * @param ownerId 当前 owner
     * @param requested 请求项数量
     * @param succeeded 成功项数量
     * @param failed 失败项数量
     * @param durationMillis 服务端执行耗时
     * @param provider 当前 provider 标识；未知时传空字符串
     */
    public static void index(String operation, UUID ownerId, int requested, int succeeded,
                             int failed, long durationMillis, String provider) {
        LOGGER.info(
                "business_metric=index operation={} owner={} requested={} succeeded={} failed={} duration_ms={} provider={}",
                token(operation), ownerId == null ? "-" : ownerId,
                Math.max(0, requested), Math.max(0, succeeded), Math.max(0, failed),
                Math.max(0, durationMillis), token(provider));
    }

    /**
     * 记录一次语义检索结果，用于计算无结果率和查询耗时。
     *
     * @param mode semantic 或 semantic_evidence
     * @param ownerId 当前 owner
     * @param resultCount 返回结果数
     * @param minScore 生效的最低相关度
     * @param durationMillis 服务端执行耗时
     * @param success 是否成功完成检索
     */
    public static void search(String mode, UUID ownerId, int resultCount, double minScore,
                              long durationMillis, boolean success) {
        LOGGER.info(
                "business_metric=search mode={} owner={} result_count={} no_match={} min_score={} duration_ms={} success={}",
                token(mode), ownerId == null ? "-" : ownerId,
                Math.max(0, resultCount), resultCount <= 0, minScore,
                Math.max(0, durationMillis), success);
    }

    /**
     * 记录一次文件媒体打开成功，用于估算搜索结果打开率。
     *
     * @param ownerId 当前 owner
     * @param download 是否为下载而非预览
     */
    public static void fileOpen(UUID ownerId, boolean download) {
        LOGGER.info("business_metric=file_open owner={} download={} success=true",
                ownerId == null ? "-" : ownerId, download);
    }

    /**
     * 记录 Agent 登记 operation 的最终结果。
     *
     * @param ownerId 当前 owner
     * @param tool 工具名
     * @param operation operation 标识
     * @param status HTTP 或内部状态
     * @param durationMillis 工具执行耗时
     */
    public static void agentOperation(UUID ownerId, String tool, String operation,
                                      int status, long durationMillis) {
        LOGGER.info(
                "business_metric=agent_operation owner={} tool={} operation={} status={} success={} duration_ms={}",
                ownerId == null ? "-" : ownerId, token(tool), token(operation), status,
                status == 0 || (status >= 200 && status < 400), Math.max(0, durationMillis));
    }

    /**
     * 记录用户主动取消 Agent 运行。
     *
     * @param ownerId 当前 owner
     * @param sessionId 会话 ID；仅记录安全标识
     */
    public static void cancelled(UUID ownerId, String sessionId) {
        LOGGER.info("business_metric=agent_cancel owner={} session_id={}",
                ownerId == null ? "-" : ownerId, token(sessionId));
    }

    private static String token(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.replaceAll("[^a-zA-Z0-9._:/-]", "_").toLowerCase(Locale.ROOT);
    }
}
