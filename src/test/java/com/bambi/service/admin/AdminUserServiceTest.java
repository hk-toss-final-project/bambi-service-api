package com.bambi.service.admin;

import com.bambi.service.admin.dto.AdminContextSyncResponse;
import com.bambi.service.admin.dto.AdminUserResponse;
import com.bambi.service.agent.AgentContextSyncService;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminUserService} — 활성/비활성 토글의 soft delete 반영과, 강제 재동기화의 위임·실패 전파 검증.
 */
class AdminUserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AgentContextSyncService contextSyncService = mock(AgentContextSyncService.class);
    private final AdminUserService service = new AdminUserService(userRepository, contextSyncService);

    /** createdAt(@CreationTimestamp)은 영속 시 채워지므로 단위 테스트에선 직접 심는다. */
    private User activeUser() {
        User user = new User("a@bambi.test", "hash", "홍길동");
        ReflectionTestUtils.setField(user, "createdAt", OffsetDateTime.now());
        return user;
    }

    @Test
    @DisplayName("비활성화: deletedAt 을 찍고 status 를 INACTIVE 로 내려준다")
    void deactivateMarksInactive() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AdminUserResponse response = service.setActive(1L, false);

        assertThat(response.status()).isEqualTo("INACTIVE");
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.isActive()).isFalse();
    }

    @Test
    @DisplayName("활성화: deletedAt 을 지우고 status 를 ACTIVE 로 되돌린다")
    void activateClearsDeletedAt() {
        User user = activeUser();
        user.deactivate();   // 먼저 비활성 상태로
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        AdminUserResponse response = service.setActive(1L, true);

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(user.getDeletedAt()).isNull();
        assertThat(user.isActive()).isTrue();
    }

    @Test
    @DisplayName("없는 사용자면 NOT_FOUND")
    void unknownUserThrowsNotFound() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setActive(9L, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("강제 재동기화: 동기화 경로를 그대로 태우고 나간 버전을 돌려준다")
    void resyncDelegatesAndReportsVersion() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        // 실제로는 AgentContextVersionAllocator 가 별도 트랜잭션에서 올린다 — 그 효과만 흉내낸다.
        doAnswer(invocation -> user.bumpAgentContextVersion())
                .when(contextSyncService).syncUserContext(1L);

        AdminContextSyncResponse response = service.resyncAgentContext(1L);

        verify(contextSyncService).syncUserContext(1L);
        assertThat(response.email()).isEqualTo("a@bambi.test");
        assertThat(response.contextVersion()).isEqualTo(1);
        assertThat(response.syncedAt()).isNotNull();
    }

    @Test
    @DisplayName("강제 재동기화: 없는 사용자면 agent 를 부르지 않고 NOT_FOUND")
    void resyncUnknownUserDoesNotCallAgent() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resyncAgentContext(9L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(contextSyncService, never()).syncUserContext(anyLong());
    }

    @Test
    @DisplayName("강제 재동기화: agent 실패는 삼키지 않고 그대로 올린다 (관리자가 결과를 봐야 함)")
    void resyncPropagatesAgentFailure() {
        User user = activeUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        doThrow(new ApiException(ErrorCode.AGENT_UNAVAILABLE, "agent 연결 실패"))
                .when(contextSyncService).syncUserContext(1L);

        assertThatThrownBy(() -> service.resyncAgentContext(1L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
    }
}
