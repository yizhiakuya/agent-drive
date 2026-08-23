package com.agentdrive.api.chat;

import com.agentdrive.agent.BackendApiDispatcher;
import com.agentdrive.agent.BackendApiTool;
import com.agentdrive.agent.ConfirmationService;
import com.agentdrive.agent.AgentTool;
import com.agentdrive.agent.FrontendActionTool;
import com.agentdrive.agent.OperationCatalog;
import com.agentdrive.agent.ProviderRuntimeResolver;
import com.agentdrive.agent.PlanTool;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.infrastructure.AppProperties;
import com.agentdrive.infrastructure.PersistentChatRuntimeStateStore;
import com.agentdrive.skills.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * 在 {@code java-chat} profile 下组装生产聊天 runtime 的 Spring bean。
 *
 * <p>配置把签名确认、operation catalog、backend_api/read_skill 工具和持久化 owner-aware
 * {@link LangChainAgentRuntime} 串起来；HTTP API 共享同一套 operation 定义与 PostgreSQL
 * 状态存储，不在这里执行聊天或工具调用。
 */
@Configuration
@Profile("java-chat")
public class ChatRuntimeConfiguration {
    /**
     * 创建使用应用确认签名密钥和持久化状态的确认服务。
     *
     * @param properties 提供 red 操作签名密钥。
     * @param stateStore 持久化待确认参数和消费状态的聊天 runtime 状态存储。
     * @param objectMapper 序列化确认参数和签名载荷的映射器。
     * @return 绑定当前部署密钥和状态存储的确认服务。
     */
    @Bean
    ConfirmationService confirmationService(AppProperties properties,
                                             PersistentChatRuntimeStateStore stateStore,
                                             ObjectMapper objectMapper) {
        return new ConfirmationService(properties.confirmationSigningKey(), objectMapper, stateStore);
    }

    /**
     * 创建模型可见的统一 backend_api 工具。
     *
     * @param catalog 提供 discover 返回的已登记 operation 和风险信息。
     * @param dispatcher 执行已校验 operation 的 owner-aware 分发器。
     * @param objectMapper 解析模型工具参数并编码结果。
     * @return 只暴露 catalog 中 operation 的 backend API 工具。
     */
    @Bean
    BackendApiTool backendApiTool(OperationCatalog catalog,
                                  BackendApiDispatcher dispatcher,
                                  ObjectMapper objectMapper) {
        return new BackendApiTool(catalog, dispatcher, objectMapper);
    }

    /**
     * 创建接收当前浏览器能力清单的前端动作适配器。
     *
     * @param objectMapper 解析能力 schema、动作参数和结果 JSON 的映射器
     * @return 使用统一 AgentTool 契约的前端通道适配器
     */
    @Bean
    FrontendActionTool frontendActionTool(ObjectMapper objectMapper) {
        return new FrontendActionTool(objectMapper);
    }

    /** 创建只记录当前会话可视化计划的绿色辅助工具。 */
    @Bean
    PlanTool planTool(ObjectMapper objectMapper) {
        return new PlanTool(objectMapper);
    }

    /**
     * 创建系统提示、Agent 文档和 Skill 目录上下文 provider。
     * @param skillRegistry 当前 owner 的 Skill registry
     * @param files owner 文件服务
     * @param properties 应用系统提示配置
     * @return 每次模型请求重新装配的上下文 provider
     */
    @Bean
    ChatContextProvider chatContextProvider(SkillRegistry skillRegistry,
                                            FileStorageService files,
                                            PersistentChatRuntimeStateStore stateStore,
                                            AppProperties properties) {
        return new DefaultChatContextProvider(
                skillRegistry,
                files,
                AgentSystemPrompt.normalize(properties.systemPrompt()),
                new com.agentdrive.infrastructure.SensitiveDataRedactor(),
                stateStore
        );
    }

    /**
     * 创建按 owner 解析 Provider、持久化工具重放和会话轨迹的聊天 runtime。
     *
     * @param providerResolver 按认证用户加载 Provider 模型和请求工厂。
     * @param tools 模型可见的统一 Agent 工具适配器集合。
     * @param objectMapper 聊天和工具 JSON 映射器。
     * @param confirmationService red 操作确认服务。
     * @param stateStore 同时实现工具重放和 transcript 持久化的状态存储。
     * @param contextProvider owner-scoped 上下文装配器。
     * @param properties 提供系统提示和可选的 Agent 运维步数熔断。
     * @return 生产用 {@link ChatRuntime}。
     */
    @Bean
    ChatRuntime chatRuntime(ProviderRuntimeResolver providerResolver,
                            List<AgentTool> tools,
                            ObjectMapper objectMapper,
                            ConfirmationService confirmationService,
                            PersistentChatRuntimeStateStore stateStore,
                            ChatContextProvider contextProvider,
                            AppProperties properties) {
        return new LangChainAgentRuntime(
                providerResolver,
                tools,
                objectMapper,
                confirmationService,
                stateStore,
                stateStore,
                contextProvider,
                properties.systemPrompt(),
                properties.maxChatSteps()
        );
    }
}
