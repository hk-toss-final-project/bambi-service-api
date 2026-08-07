package com.bambi.service.admin;

import com.bambi.service.admin.dto.AdminContextSyncResponse;
import com.bambi.service.admin.dto.AdminUserResponse;
import com.bambi.service.agent.AgentContextSyncService;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 관리자용 사용자 조회.
 *
 * 도메인 서비스(Note 등)가 "내 것"만 보는 것과 달리, 여기선 전체 사용자를 훑는다.
 * 그만큼 접근 통제가 중요한데, 권한 검사는 SecurityConfig 의 /api/admin/** = ADMIN 한 곳에
 * 모아 두었으므로 서비스 계층은 조회 로직에만 집중한다.
 */
@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final AgentContextSyncService agentContextSyncService;

    public AdminUserService(UserRepository userRepository,
                            AgentContextSyncService agentContextSyncService) {
        this.userRepository = userRepository;
        this.agentContextSyncService = agentContextSyncService;
    }

    /**
     * 전체 사용자를 가입 최신순으로 반환한다.
     * 탈퇴(soft delete)한 계정도 빼지 않고 status 로 구분해 함께 보여준다 — 관리자는
     * "지금 활성인 사람"뿐 아니라 "있었던 사람"도 봐야 하기 때문.
     */
    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers() {
        return userRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(AdminUserResponse::from)
                .toList();
    }

    /**
     * 사용자를 활성/비활성 전환한다(관리자 토글). 비활성은 soft delete 시각으로 표시하며,
     * 같은 상태로의 재요청은 그대로 둔다(멱등). 없는 사용자면 NOT_FOUND.
     */
    @Transactional
    public AdminUserResponse setActive(long userId, boolean active) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "사용자를 찾을 수 없습니다 (id=" + userId + ")"));
        if (active) {
            user.activate();
        } else {
            user.deactivate();
        }
        return AdminUserResponse.from(user);
    }

    /**
     * 사용자 컨텍스트(관심사)를 agent 에 손으로 다시 밀어 넣는다.
     *
     * <p>가입·관심사 변경 때 자동으로 도는 것과 <b>같은 경로</b>다(#46 이벤트 → #47 버전 정합).
     * 자동 동기화는 실패해도 사용자 흐름을 막지 않으려고 조용히 넘어가므로, agent 쪽에
     * 관심사가 안 붙은 계정이 남을 수 있다. 그 계정을 관리자가 즉시 되살리는 복구 버튼이다.
     *
     * <p>일부러 트랜잭션을 열지 않는다 — 동기화는 agent HTTP 왕복이라, 트랜잭션 안에서
     * 부르면 커넥션을 왕복 내내 붙잡는다. 버전 증가는 {@code AgentContextVersionAllocator}
     * 가 자기 트랜잭션에서 처리한다.
     *
     * <p>실패는 삼키지 않는다. 자동 경로와 달리 관리자는 결과를 보려고 누른 것이라,
     * agent 가 못 받으면 AGENT_UNAVAILABLE 이 그대로 화면까지 올라가야 한다.
     */
    public AdminContextSyncResponse resyncAgentContext(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "사용자를 찾을 수 없습니다 (id=" + userId + ")"));

        agentContextSyncService.syncUserContext(userId);

        // 버전은 동기화 중 별도 트랜잭션에서 올라가므로, 방금 나간 값을 보려면 다시 읽는다.
        int syncedVersion = userRepository.findById(userId)
                .map(User::getAgentContextVersion)
                .orElse(user.getAgentContextVersion());
        return new AdminContextSyncResponse(
                user.getId(), user.getEmail(), syncedVersion, OffsetDateTime.now());
    }
}
