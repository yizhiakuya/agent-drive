package com.agentdrive.api.skills;

import com.agentdrive.agent.AgentDriveApiSkillProvider;
import com.agentdrive.agent.OperationCatalog;
import com.agentdrive.agent.ReadSkillTool;
import com.agentdrive.agent.SkillAuthoringSkillProvider;
import com.agentdrive.skills.BuiltinSkillProvider;
import com.agentdrive.skills.DefaultSkillRegistry;
import com.agentdrive.skills.SkillRegistry;
import com.agentdrive.skills.SkillRepository;
import com.agentdrive.skills.SkillTextSanitizer;
import com.agentdrive.infrastructure.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

/** 在聊天 profile 中组装 Skill registry、内置 provider 和模型读取工具。 */
@Configuration
@Profile("java-chat")
public class SkillConfiguration {
    /**
     * 创建与 operation catalog 同源的 API Skill provider。
     * @param catalog 当前 Agent 后端 operation 目录
     * @return 动态内置 provider
     */
    @Bean
    BuiltinSkillProvider agentDriveApiSkillProvider(OperationCatalog catalog) {
        return new AgentDriveApiSkillProvider(catalog);
    }

    /**
     * 创建自定义 Skill 生命周期内置说明。
     * @return Skill authoring provider
     */
    @Bean
    BuiltinSkillProvider skillAuthoringSkillProvider() {
        return new SkillAuthoringSkillProvider();
    }

    /**
     * 创建合并内置与自定义 Skill 的 registry。
     * @param repository 自定义 Skill repository
     * @param providers 内置 Skill provider
     * @param sanitizer 自定义 Skill 文本清理器
     * @return owner-scoped registry
     */
    @Bean
    SkillRegistry skillRegistry(SkillRepository repository, List<BuiltinSkillProvider> providers,
                                SkillTextSanitizer sanitizer) {
        return new DefaultSkillRegistry(repository, providers, sanitizer);
    }

    /**
     * 创建复用会话凭据模式的 Skill 文本清理器。
     * @return OpenAI/Jina/Bearer 凭据不可逆脱敏函数
     */
    @Bean
    SkillTextSanitizer skillTextSanitizer() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor();
        return redactor::text;
    }

    /**
     * 创建模型可见的 Skill 读取工具。
     * @param registry owner-scoped Skill registry
     * @param objectMapper JSON mapper
     * @return read_skill 工具
     */
    @Bean
    ReadSkillTool readSkillTool(SkillRegistry registry, ObjectMapper objectMapper) {
        return new ReadSkillTool(registry, objectMapper);
    }
}
