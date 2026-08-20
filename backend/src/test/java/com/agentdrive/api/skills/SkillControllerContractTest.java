package com.agentdrive.api.skills;

import com.agentdrive.api.auth.WebRequestPrincipalResolver;
import com.agentdrive.auth.AuthenticatedPrincipal;
import com.agentdrive.auth.CredentialAuthenticator;
import com.agentdrive.skills.SkillDefinition;
import com.agentdrive.skills.SkillPage;
import com.agentdrive.skills.SkillRegistry;
import com.agentdrive.skills.SkillRegistryException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkillControllerContractTest {
    @Test
    void listsAndSavesSkillsForAuthenticatedOwner() {
        UUID owner = UUID.randomUUID();
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.discover(eq(owner), eq("report"), eq(true), eq(0), eq(20)))
                .thenReturn(new SkillPage(List.of(), 0, 0, 0, 20, false, 0));
        when(registry.save(eq(owner), eq("weekly-report"), eq("周报"), eq("生成周报"), eq(true)))
                .thenReturn(skill("weekly-report", true));
        WebTestClient client = client(owner, registry);

        client.get().uri("/api/v1/skills?q=report&include_disabled=true&offset=0&limit=20")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.total_matches").isEqualTo(0);
        client.put().uri("/api/v1/skills/weekly-report")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"description\":\"周报\",\"instructions\":\"生成周报\",\"enabled\":true}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.skill.name").isEqualTo("weekly-report");

        verify(registry).save(owner, "weekly-report", "周报", "生成周报", true);
    }

    @Test
    void mapsBuiltinConflictAndRejectsInvalidBody() {
        UUID owner = UUID.randomUUID();
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.save(any(), any(), any(), any(), any(Boolean.class)))
                .thenThrow(new SkillRegistryException(409, "builtin_skill_read_only", "内置 Skill 不可修改"));
        WebTestClient client = client(owner, registry);

        client.put().uri("/api/v1/skills/agent-drive-api")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"description\":\"覆盖\",\"instructions\":\"覆盖\"}")
                .exchange().expectStatus().isEqualTo(409);

        SkillRegistry untouched = mock(SkillRegistry.class);
        client(owner, untouched).put().uri("/api/v1/skills/bad")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"description\":\"\",\"instructions\":\"\"}")
                .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(untouched);
    }

    @Test
    void returnsNotFoundForMissingSkill() {
        UUID owner = UUID.randomUUID();
        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.read(owner, "missing", true)).thenReturn(Optional.empty());

        client(owner, registry).get().uri("/api/v1/skills/missing")
                .exchange().expectStatus().isNotFound();
    }

    private static SkillDefinition skill(String name, boolean enabled) {
        return new SkillDefinition(name, "周报", "生成周报", enabled, "custom", 1, 1.0, 1.0);
    }

    private static WebTestClient client(UUID owner, SkillRegistry registry) {
        CredentialAuthenticator authenticator = credential ->
                "session-token".equals(credential)
                        ? Optional.of(new AuthenticatedPrincipal(owner, AuthenticatedPrincipal.CredentialKind.SESSION))
                        : Optional.empty();
        return WebTestClient.bindToController(new SkillController(
                        registry, new WebRequestPrincipalResolver(authenticator)))
                .build().mutate().defaultCookie("agentdrive_session", "session-token").build();
    }
}
