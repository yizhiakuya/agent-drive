package com.agentdrive.infrastructure.persistence;

import com.agentdrive.infrastructure.persistence.mapper.SkillMapper;
import com.agentdrive.skills.SkillRepository;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MybatisSkillRepositoryTest {
    @Test
    void supportsClassBasedTransactionProxying() {
        SkillMapper mapper = mock(SkillMapper.class);
        UUID userId = UUID.randomUUID();
        when(mapper.selectAll(userId.toString())).thenReturn(List.of());

        ProxyFactory factory = new ProxyFactory(new MybatisSkillRepository(mapper));
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());

        SkillRepository proxy = (SkillRepository) factory.getProxy();

        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
        assertThat(proxy.list(userId)).isEmpty();
    }
}
