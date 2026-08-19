package com.agentdrive.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationSessionServiceTest {
    @Test
    void createsSessionWhenRequestDoesNotProvideOne() {
        UUID userId = UUID.randomUUID();
        UUID createdId = UUID.randomUUID();
        ConversationSessionService service = new ConversationSessionService(new ConversationSessionStore() {
            @Override
            public Optional<ConversationSession> findOwned(UUID ignoredUserId, UUID ignoredSessionId) {
                return Optional.empty();
            }

            @Override
            public ConversationSession create(UUID actualUserId) {
                return new ConversationSession(createdId, actualUserId);
            }
        });

        assertThat(service.ensureOwned(userId, null)).isEqualTo(createdId.toString());
    }

    @Test
    void rejectsSessionOwnedByAnotherUser() {
        UUID currentUser = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ConversationSessionStore store = new ConversationSessionStore() {
            @Override
            public Optional<ConversationSession> findOwned(UUID ignoredUserId, UUID requestedId) {
                return Optional.of(new ConversationSession(requestedId, otherUser));
            }

            @Override
            public ConversationSession create(UUID ignoredUserId) {
                return new ConversationSession(UUID.randomUUID(), ignoredUserId);
            }
        };
        ConversationSessionService service = new ConversationSessionService(store);

        assertThatThrownBy(() -> service.ensureOwned(currentUser, sessionId.toString()))
                .isInstanceOf(ConversationSessionService.SessionNotFoundException.class);
    }

    @Test
    void rejectsMalformedSessionId() {
        ConversationSessionService service = new ConversationSessionService(new ConversationSessionStore() {
            @Override
            public Optional<ConversationSession> findOwned(UUID userId, UUID sessionId) {
                return Optional.empty();
            }

            @Override
            public ConversationSession create(UUID userId) {
                return new ConversationSession(UUID.randomUUID(), userId);
            }
        });

        assertThatThrownBy(() -> service.ensureOwned(UUID.randomUUID(), "not-a-uuid"))
                .isInstanceOf(ConversationSessionService.InvalidSessionIdException.class);
    }

    @Test
    void summarizeDoesNotLeakNullContentIntoTitle() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SummarizableStore store = new SummarizableStore(userId, sessionId)
                .withMessages(
                        message("user", "帮我整理文件"),
                        message("assistant", null));
        ConversationSessionService service = new ConversationSessionService(store);

        Map<String, Object> result = service.summarizeOwned(userId, sessionId.toString());

        assertThat(String.valueOf(result.get("summary"))).isEqualTo("帮我整理文件");
        assertThat(String.valueOf(result.get("title"))).isEqualTo("帮我整理文件");
        assertThat(String.valueOf(result.get("summary"))).doesNotContain("null");
        assertThat(store.summary).isEqualTo("帮我整理文件");
        assertThat(store.title).isEqualTo("帮我整理文件");
    }

    @Test
    void summarizeUsesAiTitleGeneratorWhileKeepingDeterministicSummary() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SummarizableStore store = new SummarizableStore(userId, sessionId)
                .withMessages(message("user", "帮我整理工作目录"), message("assistant", "已完成整理"));
        ConversationSessionService service = new ConversationSessionService(store,
                (ignoredUserId, ignoredMessages) -> "标题：工作文件整理");

        Map<String, Object> result = service.summarizeOwned(userId, sessionId.toString());

        assertThat(result.get("summary")).isEqualTo("帮我整理工作目录；已完成整理");
        assertThat(result.get("title")).isEqualTo("工作文件整理");
        assertThat(store.title).isEqualTo("工作文件整理");
    }

    @Test
    void summarizeDoesNotRegenerateExistingTitle() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        int[] calls = {0};
        SummarizableStore store = new SummarizableStore(userId, sessionId)
                .withMeta(Map.of("title", "已有标题"))
                .withMessages(message("user", "继续整理文件"));
        ConversationSessionService service = new ConversationSessionService(store,
                (ignoredUserId, ignoredMessages) -> {
                    calls[0]++;
                    return "不应调用";
                });

        Map<String, Object> result = service.summarizeOwned(userId, sessionId.toString());

        assertThat(result.get("title")).isEqualTo("已有标题");
        assertThat(calls[0]).isZero();
        assertThat(store.title).isEqualTo("已有标题");
    }

    @Test
    void summarizeWithBlankContentSkipsPersistingAndKeepsExistingTitle() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SummarizableStore store = new SummarizableStore(userId, sessionId)
                .withMeta(Map.of("title", "已有标题", "summary", "已有摘要"))
                .withMessages(
                        message("user", null),
                        message("assistant", "   "));
        ConversationSessionService service = new ConversationSessionService(store);

        Map<String, Object> result = service.summarizeOwned(userId, sessionId.toString());

        assertThat(result.get("summary")).isEqualTo("已有摘要");
        assertThat(result.get("title")).isEqualTo("已有标题");
        assertThat(store.summarizeCalled).isFalse();
    }

    @Test
    void summarizeWithBlankContentDoesNotPersistEmptyTitle() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SummarizableStore store = new SummarizableStore(userId, sessionId)
                .withMeta(Map.of())
                .withMessages(message("user", null));
        ConversationSessionService service = new ConversationSessionService(store);

        Map<String, Object> result = service.summarizeOwned(userId, sessionId.toString());

        assertThat(result.get("title")).isEqualTo("");
        assertThat(store.summarizeCalled).isFalse();
    }

    @Test
    void summarizeTruncatesLongConversationToSummaryAndTitleLengths() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String longMessage = "字".repeat(120);
        SummarizableStore store = new SummarizableStore(userId, sessionId)
                .withMessages(Map.of("role", "user", "content", longMessage));
        ConversationSessionService service = new ConversationSessionService(store);

        Map<String, Object> result = service.summarizeOwned(userId, sessionId.toString());

        assertThat(String.valueOf(result.get("summary"))).hasSize(80);
        assertThat(String.valueOf(result.get("title"))).hasSize(20);
        assertThat(store.summary).hasSize(80);
        assertThat(store.title).hasSize(20);
    }

    /** Map.of 不允许 null 值，而消息正文可能为 null；用可变 map 构造。 */
    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    /** 可总结会话的 store 桩：记录 summarize 写并保存 summary/title。 */
    private static final class SummarizableStore implements ConversationSessionStore {
        private final UUID owner;
        private final UUID sessionId;
        private final Map<String, Object> meta = new HashMap<>();
        private final List<Map<String, Object>> messages = new ArrayList<>();
        private String summary;
        private String title;
        private boolean summarizeCalled;

        private SummarizableStore(UUID owner, UUID sessionId) {
            this.owner = owner;
            this.sessionId = sessionId;
        }

        private SummarizableStore withMeta(Map<String, Object> meta) {
            this.meta.putAll(meta);
            return this;
        }

        private SummarizableStore withMessages(Map<String, Object>... messages) {
            this.messages.addAll(Arrays.asList(messages));
            return this;
        }

        @Override
        public Optional<ConversationSession> findOwned(UUID userId, UUID requested) {
            return Optional.empty();
        }

        @Override
        public ConversationSession create(UUID userId) {
            return new ConversationSession(sessionId, userId);
        }

        @Override
        public Map<String, Object> findOwnedDetails(UUID userId, UUID requested) {
            return owner.equals(userId) && sessionId.equals(requested) ? new HashMap<>(meta) : null;
        }

        @Override
        public List<Map<String, Object>> messagesOwned(UUID userId, UUID requested) {
            return owner.equals(userId) && sessionId.equals(requested) ? List.copyOf(messages) : List.of();
        }

        @Override
        public boolean updateSummary(UUID userId, UUID requested, String summary, String title) {
            if (!owner.equals(userId) || !sessionId.equals(requested)) return false;
            summarizeCalled = true;
            this.summary = summary;
            this.title = title;
            return true;
        }
    }

}