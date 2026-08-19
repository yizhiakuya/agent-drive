package com.agentdrive.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 为已有会话消息生成简短标题的可选 Provider。
 *
 * <p>实现可以调用当前 owner 的模型，但不应修改会话；调用方负责清洗结果、限制
 * 长度并在失败时回退到确定性标题。</p>
 */
@FunctionalInterface
public interface SessionTitleGenerator {
    /**
     * 根据用户会话消息生成候选标题。
     * @param userId 当前 owner UUID，用于选择其 Provider 配置
     * @param messages 会话消息快照，通常包含 user 和 assistant 角色
     * @return 模型生成的标题文本；调用方负责验证和截断
     */
    String generate(UUID userId, List<Map<String, Object>> messages);
}
