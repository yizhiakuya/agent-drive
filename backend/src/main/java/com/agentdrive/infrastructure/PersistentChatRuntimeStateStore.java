package com.agentdrive.infrastructure;

import com.agentdrive.agent.ChatTranscriptStore;
import com.agentdrive.agent.ConfirmationStateStore;
import com.agentdrive.agent.ToolReplayStore;

/**
 * 持久化聊天运行时状态的聚合端口。
 * <p>生产实现把同一会话的 transcript、工具 replay、待确认调用和 nonce 消费放入
 * PostgreSQL/MyBatis 状态边界；会话 ID 对应的 owner 归属由会话和认证边界保证，本接口
 * 不提供跨 owner 读取状态的能力。</p>
 * <p>继承的 {@link ChatTranscriptStore} 语义是：user/assistant/context 消息、assistant 的独立
 * reasoning、tool trace 以及用于续接路由的 {@code last_trace} 持久化到会话状态中。生产
 * MyBatis 实现会在写入 transcript 和 {@code last_trace} 前递归脱敏文本、工具参数、工具
 * 输出和 parsed 值；同来源 context 未变化时不重复追加，{@code last_trace} 是覆盖式快照，
 * 空输入保存为空列表，而不是追加历史。</p>
 * <p>继承的 {@link ToolReplayStore} 语义是按 session、工具名和完整参数 JSON 精确匹配并
 * 持久化 replay 输出。replay 只适用于调用方标记为可安全重放的非 red 工具；生产实现为
 * 保证确定性匹配，按原样保存 arguments、output 和 parsed，不经过 transcript 脱敏器，
 * 因此调用方不得把密钥或高风险工具结果写入 replay。继承的
 * {@link ConfirmationStateStore} 是另一条边界：pending confirmation 的原始参数和签名
 * 必须保留以便精确校验和重放，nonce 消费则必须是一次性的原子操作；这些原文不应混入
 * transcript 或 {@code last_trace}。</p>
 */
public interface PersistentChatRuntimeStateStore
        extends ToolReplayStore, ConfirmationStateStore, ChatTranscriptStore {
}
