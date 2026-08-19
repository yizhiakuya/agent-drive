package com.agentdrive.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimiterTest {
    @Test
    void rejectsOnlyAfterConfiguredWindowQuota() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allow("login:client", 5)).isTrue();
        }
        assertThat(limiter.allow("login:client", 5)).isFalse();
        assertThat(limiter.allow("login:other-client", 5)).isTrue();
    }

    @Test
    void rejectsInvalidKeysAndLimits() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        assertThat(limiter.allow("", 5)).isFalse();
        assertThat(limiter.allow("key", 0)).isFalse();
    }
}
