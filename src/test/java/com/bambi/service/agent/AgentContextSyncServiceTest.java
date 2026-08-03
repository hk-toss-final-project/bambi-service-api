package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentContextRequest;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentContextSyncService} — 동기화 때마다 컨텍스트 버전을 단조 증가시켜 보내는지 검증한다.
 * agent 는 같거나 작은 버전을 STALE 로 거부하므로(계약 §4.3), 매 호출이 더 큰 버전이어야 한다.
 */
class AgentContextSyncServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AgentGateway agentGateway = mock(AgentGateway.class);
    private final AgentContextSyncService service =
            new AgentContextSyncService(userRepository, agentGateway);

    private static User newUser() {
        return new User("qa@bambi.test", "hash", "큐에이");
    }

    @Test
    void 첫_동기화는_버전1로_보내고_사용자를_저장한다() {
        User user = newUser();   // 신규 → agent_context_version = 0
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.syncUserContext(1L);

        ArgumentCaptor<AgentContextRequest> captor = ArgumentCaptor.forClass(AgentContextRequest.class);
        verify(agentGateway).syncUserContext(anyLong(), captor.capture());
        assertThat(captor.getValue().contextVersion()).isEqualTo(1);   // 0 → 1
        assertThat(user.getAgentContextVersion()).isEqualTo(1);        // 저장 대상에 반영
        verify(userRepository).save(user);
    }

    @Test
    void 재동기화는_버전을_단조_증가시킨다() {
        User user = newUser();
        user.bumpAgentContextVersion();   // 이미 한 번 동기화된 상태(=1)
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.syncUserContext(1L);

        ArgumentCaptor<AgentContextRequest> captor = ArgumentCaptor.forClass(AgentContextRequest.class);
        verify(agentGateway).syncUserContext(anyLong(), captor.capture());
        assertThat(captor.getValue().contextVersion()).isEqualTo(2);   // 1 → 2 (STALE 방지)
    }

    @Test
    void 대상_사용자가_없으면_예외를_던지고_agent를_호출하지_않는다() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.syncUserContext(99L))
                .isInstanceOf(IllegalStateException.class);

        verify(agentGateway, never()).syncUserContext(anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
