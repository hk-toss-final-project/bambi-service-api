package com.bambi.service.agent;

import com.bambi.service.user.UserRegisteredEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link AgentContextSyncListener} 단위 테스트 — 가입 이벤트 처리 규약 검증.
 * 리스너는 동기화 세부(버전 관리)는 {@link AgentContextSyncService} 에 위임하고,
 * 실패를 삼켜 가입을 막지 않는 역할만 진다.
 */
class AgentContextSyncListenerTest {

    @Test
    void 가입_이벤트를_받으면_컨텍스트_동기화를_1회_호출한다() {
        AgentContextSyncService syncService = mock(AgentContextSyncService.class);
        AgentContextSyncListener listener = new AgentContextSyncListener(syncService);

        listener.onUserRegistered(new UserRegisteredEvent(42L));

        verify(syncService).syncUserContext(eq(42L));
    }

    @Test
    void agent_동기화가_실패해도_예외를_삼켜_가입을_막지_않는다() {
        AgentContextSyncService syncService = mock(AgentContextSyncService.class);
        doThrow(new RuntimeException("agent down")).when(syncService).syncUserContext(anyLong());
        AgentContextSyncListener listener = new AgentContextSyncListener(syncService);

        assertThatCode(() -> listener.onUserRegistered(new UserRegisteredEvent(7L)))
                .doesNotThrowAnyException();

        verify(syncService).syncUserContext(anyLong());
    }
}
