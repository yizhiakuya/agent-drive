package com.agentdrive.infrastructure;

import com.agentdrive.auth.AuthAccountStore;
import com.agentdrive.auth.AuthRateLimiter;
import com.agentdrive.auth.AuthService;
import com.agentdrive.auth.ConversationSessionService;
import com.agentdrive.auth.SessionTitleGenerator;
import com.agentdrive.agent.ProviderRuntimeResolver;
import com.agentdrive.index.IndexStore;
import com.agentdrive.index.EmbeddingRuntimeConfig;
import com.agentdrive.index.SemanticSearchService;
import com.agentdrive.devices.DeviceStore;
import com.agentdrive.auth.PasswordHasher;
import com.agentdrive.files.FileStorageService;
import com.agentdrive.skills.SkillRepository;
import com.agentdrive.infrastructure.persistence.MybatisAuthAccountStore;
import com.agentdrive.infrastructure.persistence.MybatisChatRuntimeStateStore;
import com.agentdrive.infrastructure.persistence.MybatisConversationSessionStore;
import com.agentdrive.infrastructure.persistence.MybatisDeviceStore;
import com.agentdrive.infrastructure.persistence.MybatisLlmProviderConfigStore;
import com.agentdrive.infrastructure.persistence.MybatisSkillRepository;
import com.agentdrive.infrastructure.persistence.MybatisIndexStore;
import com.agentdrive.infrastructure.persistence.MybatisEmbeddingConfigStore;
import com.agentdrive.infrastructure.persistence.MybatisVisionConfigStore;
import com.agentdrive.infrastructure.persistence.MybatisFileStorageService;
import com.agentdrive.infrastructure.persistence.LlmProviderConfigStore;
import com.agentdrive.infrastructure.persistence.MybatisCredentialAuthenticator;
import com.agentdrive.infrastructure.persistence.mapper.AuthAccountMapper;
import com.agentdrive.infrastructure.persistence.mapper.ChatRuntimeStateMapper;
import com.agentdrive.infrastructure.persistence.mapper.ConversationSessionMapper;
import com.agentdrive.infrastructure.persistence.mapper.DeviceMapper;
import com.agentdrive.infrastructure.persistence.mapper.CredentialMapper;
import com.agentdrive.infrastructure.persistence.mapper.LlmProviderConfigMapper;
import com.agentdrive.infrastructure.persistence.mapper.FileMapper;
import com.agentdrive.infrastructure.persistence.mapper.SkillMapper;
import com.agentdrive.infrastructure.persistence.mapper.IndexMapper;
import com.agentdrive.infrastructure.persistence.mapper.EmbeddingConfigMapper;
import com.agentdrive.infrastructure.persistence.mapper.VisionConfigMapper;
import com.agentdrive.vision.VisionModelClient;
import com.agentdrive.vision.VisionDescriptionPort;
import com.agentdrive.vision.VisionDescriptionService;
import com.agentdrive.vision.RemoteVisionDescriptionService;
import com.agentdrive.vision.VisionRuntimeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

/**
 * 注册 Java 后端的基础 Bean、MyBatis 存储适配器和按 profile 启用的运行时组件。
 * <p>本类只负责依赖装配：数据访问实现通过 Mapper 注入，文件服务使用应用配置的
 * 数据目录和上传上限，聊天 profile 才创建模型解析器和标题生成器。</p>
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
@MapperScan("com.agentdrive.infrastructure.persistence.mapper")
public class ApplicationConfiguration {
    /**
     * 注册密码哈希器，供认证服务生成和验证密码摘要。
     * @return 无外部依赖的 {@link PasswordHasher} 实例。
     */
    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    /**
     * 注册认证接口使用的进程内限速器。
     * @return 用于 setup/login/pair 请求计数的 {@link AuthRateLimiter}。
     */
    @Bean
    AuthRateLimiter authRateLimiter() {
        return new AuthRateLimiter();
    }

    /**
     * 将认证账户 Mapper 封装为应用层账户存储。
     * @param mapper 访问用户、会话、设备和配对码表的 MyBatis Mapper。
     * @return 使用该 Mapper 的账户存储实现。
     */
    @Bean
    MybatisAuthAccountStore mybatisAuthAccountStore(AuthAccountMapper mapper) {
        return new MybatisAuthAccountStore(mapper);
    }

    /**
     * 装配认证业务服务。
     * @param store 持久化用户、会话、设备和配对码的账户存储。
     * @param passwords 执行密码哈希和验证的组件。
     * @return 使用给定存储和密码组件的认证服务。
     */
    @Bean
    AuthService authService(AuthAccountStore store, PasswordHasher passwords) {
        return new AuthService(store, passwords);
    }

    /**
     * 装配持久化聊天运行时状态存储。
     * @param mapper 读写工具重放、待确认调用、消息和 nonce 的 Mapper。
     * @param objectMapper 序列化工具参数、结果和 trace JSON 的 Jackson 映射器。
     * @return 会在落库前执行敏感信息脱敏的运行时状态存储。
     */
    @Bean
    MybatisChatRuntimeStateStore mybatisChatRuntimeStateStore(
            ChatRuntimeStateMapper mapper,
            ObjectMapper objectMapper
    ) {
        return new MybatisChatRuntimeStateStore(mapper, objectMapper);
    }

    /**
     * 装配 owner-scoped 会话存储。
     * @param mapper 访问会话元数据和消息表的 Mapper。
     * @param objectMapper 解析消息中的 arguments/parsed JSON 的映射器。
     * @return 将数据库行转换为会话 API 结构的存储实现。
     */
    @Bean
    MybatisConversationSessionStore mybatisConversationSessionStore(ConversationSessionMapper mapper, ObjectMapper objectMapper) {
        return new MybatisConversationSessionStore(mapper, objectMapper);
    }

    /**
     * 装配设备注册表存储。
     * @param mapper 访问设备 metadata、sync_state 和撤销状态的 Mapper。
     * @param objectMapper 将同步状态 Map 转成 JSON 并解析回 Map 的映射器。
     * @return 绑定 owner 查询和 JSON 转换逻辑的设备存储实现。
     */
    @Bean
    MybatisDeviceStore mybatisDeviceStore(DeviceMapper mapper, ObjectMapper objectMapper) {
        return new MybatisDeviceStore(mapper, objectMapper);
    }

    /**
     * 装配 owner-scoped 自定义 Skill repository。
     * @param mapper 读写 agent_skills 表的 Mapper
     * @return 使用 PostgreSQL 数量保护和版本递增语义的 repository
     */
    @Bean
    SkillRepository skillRepository(SkillMapper mapper) {
        return new MybatisSkillRepository(mapper);
    }

    /**
     * 装配全文/向量索引存储。
     * @param mapper 读写文档、chunk、embedding 和索引清理记录的 Mapper。
     * @return 将数据库索引行转换为索引领域结构的存储实现。
     */
    @Bean
    MybatisIndexStore mybatisIndexStore(IndexMapper mapper) {
        return new MybatisIndexStore(mapper);
    }

    /**
     * 装配 embedding 配置存储。
     * @param mapper 读写 owner 的 embedding JSON 配置的 Mapper。
     * @param objectMapper 编解码配置字段和 Base64 密文的映射器。
     * @return 在读取时校验 JSON、保存时序列化配置的存储实现。
     */
    @Bean
    MybatisEmbeddingConfigStore mybatisEmbeddingConfigStore(EmbeddingConfigMapper mapper, ObjectMapper objectMapper) {
        return new MybatisEmbeddingConfigStore(mapper, objectMapper);
    }

    /**
     * 装配视觉模型配置存储。
     * @param mapper 读写 owner 视觉 preference JSON 的 Mapper。
     * @param objectMapper 编解码视觉配置和密文字段的 JSON mapper。
     * @return 校验 JSON、保存密文的 owner-scoped 视觉配置存储。
     */
    @Bean
    MybatisVisionConfigStore mybatisVisionConfigStore(VisionConfigMapper mapper, ObjectMapper objectMapper) {
        return new MybatisVisionConfigStore(mapper, objectMapper);
    }

    /**
     * 装配 OpenAI 兼容视觉模型客户端。
     * @param objectMapper 构造请求和规整模型 JSON 输出的 mapper。
     * @return 使用应用 HTTP(S) 代理策略的视觉模型客户端。
     */
    @Bean
    @Profile({"java-files", "java-auth", "java-chat"})
    VisionModelClient visionModelClient(ObjectMapper objectMapper) {
        return new VisionModelClient(objectMapper);
    }

    /**
     * 根据配置选择本地视觉实现或独立 Content Service 客户端。
     * @param properties 提供可选 Content Service 地址和内部令牌。
     * @param configs owner-scoped 视觉模型配置。
     * @param files owner 文件内容端口。
     * @param client 本地 OpenAI 兼容视觉客户端。
     * @param objectMapper HTTP JSON 编解码器。
     * @return 当前部署拓扑使用的视觉应用端口。
     */
    @Bean
    @Profile({"java-files", "java-auth", "java-chat"})
    VisionDescriptionPort visionDescriptionPort(AppProperties properties,
                                                 VisionRuntimeConfig configs,
                                                 FileStorageService files,
                                                 VisionModelClient client,
                                                 ObjectMapper objectMapper) {
        if (properties.contentServiceUrl().isBlank()) {
            return new VisionDescriptionService(configs, files, client);
        }
        if (properties.contentServiceToken().isBlank()) {
            throw new IllegalStateException("app.content-service-token is required when content-service-url is configured");
        }
        return new RemoteVisionDescriptionService(properties.contentServiceUrl(),
                properties.contentServiceToken(), configs, files, objectMapper);
    }

    /**
     * 装配 LLM provider 配置存储。
     * @param mapper 读写 provider 字段、加密 key 和 key 指纹的 Mapper。
     * @return 同时支持运行时配置读取和设置页脱敏视图的存储实现。
     */
    @Bean
    MybatisLlmProviderConfigStore llmProviderConfigStore(LlmProviderConfigMapper mapper) {
        return new MybatisLlmProviderConfigStore(mapper);
    }

    /**
     * 在文件、认证或聊天 profile 中启用 LLM API key 加密器。
     * @param properties 提供并校验配置中的 LLM 加密密钥。
     * @return 使用应用级 AES 密钥的 API key cipher。
     */
    @Bean
    @Profile({"java-files", "java-auth", "java-chat"})
    LlmApiKeyCipher llmApiKeyCipher(AppProperties properties) {
        return new LlmApiKeyCipher(properties.llmConfigKey());
    }

    /**
     * 在 {@code java-chat} profile 中装配数据库 provider 解析器。
     * @param store owner-scoped provider 配置存储。
     * @param keyCipher 解密数据库 API key 的 cipher。
     * @return 根据数据库配置创建聊天模型的解析器。
     */
    @Bean
    @Profile("java-chat")
    DatabaseProviderRuntimeResolver databaseProviderRuntimeResolver(
            LlmProviderConfigStore store,
            LlmApiKeyCipher keyCipher
    ) {
        return new DatabaseProviderRuntimeResolver(store, keyCipher, new com.agentdrive.agent.StreamingModelFactory());
    }

    /**
     * 在聊天 profile 中把 provider 解析器接入会话标题生成器。
     * @param resolver 按 owner 创建聊天模型的解析器。
     * @return 使用当前 owner 模型生成不超过 20 字标题的实现。
     */
    @Bean
    @Profile("java-chat")
    SessionTitleGenerator sessionTitleGenerator(ProviderRuntimeResolver resolver) {
        return new AiSessionTitleGenerator(resolver);
    }

    /**
     * 在文件相关 profile 中装配 owner 文件存储。
     * @param mapper 维护文件 metadata、revision、回收站和去重索引的 Mapper。
     * @param properties 提供文件根目录和上传字节上限。
     * @return 执行路径安全检查、原子发布和文件变更通知的文件服务。
     */
    @Bean
    @Profile({"java-files", "java-auth", "java-chat"})
    FileStorageService fileStorageService(FileMapper mapper, AppProperties properties,
                                          EmbeddingRuntimeConfig embeddingConfigs,
                                          SemanticSearchService semanticSearch) {
        return new MybatisFileStorageService(mapper, Path.of(properties.dataDir()), properties.maxUploadBytes(),
                embeddingConfigs, semanticSearch);
    }

    /**
     * 装配 Cookie/Bearer/设备令牌认证器。
     * @param mapper 通过 credential hash 查询会话或设备 owner 的 Mapper。
     * @return 只向上层返回 owner UUID 和凭据类型的认证器。
     */
    @Bean
    MybatisCredentialAuthenticator mybatisCredentialAuthenticator(CredentialMapper mapper) {
        return new MybatisCredentialAuthenticator(mapper);
    }

    /**
     * 装配会话应用服务，并按 profile 提供可选的 AI 标题生成器。
     * @param store owner-scoped 会话和消息存储。
     * @param titleGenerator 可能不存在的标题生成器 provider；无聊天 profile 时不强制创建模型。
     * @return 使用持久化会话存储和可选标题生成能力的服务。
     */
    @Bean
    ConversationSessionService conversationSessionService(
            MybatisConversationSessionStore store,
            ObjectProvider<SessionTitleGenerator> titleGenerator
    ) {
        return new ConversationSessionService(store, titleGenerator.getIfAvailable());
    }
}
