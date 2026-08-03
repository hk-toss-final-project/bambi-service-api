package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentContextRequest;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.springframework.stereotype.Service;

/**
 * 사용자 컨텍스트를 agent 에 반영한다(계약 §4).
 *
 * <p>agent 는 {@code context_version} 이 사용자별 단조 증가일 것을 요구하고, 같거나 작은 값을
 * 재전송하면 STALE_CONTEXT_VERSION 으로 거부한다(§4.3). 그래서 보낼 때마다 service-db 의
 * {@code users.agent_context_version} 을 +1 해 저장한 뒤 그 값을 싣는다.
 * 가입뿐 아니라 이후 설정 변경(플랜·개인화·차단) 재동기도 이 진입점을 쓰면 된다.
 */
@Service
public class AgentContextSyncService {

    private final UserRepository userRepository;
    private final AgentGateway agentGateway;

    public AgentContextSyncService(UserRepository userRepository, AgentGateway agentGateway) {
        this.userRepository = userRepository;
        this.agentGateway = agentGateway;
    }

    /**
     * 사용자 컨텍스트를 agent 에 동기화한다. 버전을 +1 해 저장한 뒤(짧은 트랜잭션) 그 버전으로 PUT 한다.
     * HTTP 는 트랜잭션 밖에서 일어나며, 전송이 실패해도 버전은 이미 올라가 있어(단조 증가 유지)
     * 다음 재시도가 더 큰 버전으로 반영한다.
     */
    public void syncUserContext(long userId) {
        int version = allocateNextVersion(userId);
        agentGateway.syncUserContext(userId, AgentContextRequest.forVersion(version));
    }

    /**
     * 사용자의 컨텍스트 버전을 +1 하고 새 값을 반환한다.
     * {@code save()} 가 자체 트랜잭션으로 영속하므로 HTTP 호출을 트랜잭션 안에 붙잡지 않는다.
     * 동시 호출은 드물고, 겹쳐서 같은 버전이 나가도 STALE 은 "이미 최신"으로 삼켜지므로 안전하다.
     */
    private int allocateNextVersion(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("컨텍스트 동기화 대상 사용자 없음: " + userId));
        int next = user.bumpAgentContextVersion();
        userRepository.save(user);
        return next;
    }
}
