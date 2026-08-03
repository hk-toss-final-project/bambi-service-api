package com.bambi.service.agent;

import com.bambi.service.user.UserRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 가입 직후 사용자 컨텍스트를 agent 에 1회 동기화한다. (계약: PUT /internal/v1/users/{id}/context)
 *
 * <p>가입 트랜잭션이 <b>커밋된 뒤</b>(AFTER_COMMIT) 실행하므로:
 * <ul>
 *   <li>agent 호출이 가입 트랜잭션을 붙잡지 않는다 (HTTP 대기 분리).
 *   <li>agent 가 죽어 동기화가 실패해도 가입 자체는 이미 성공 — 막지 않는다(계약 §4.4).
 * </ul>
 * 실패한 건은 로그로 남고, 추후 재동기(backfill) 대상이다.
 */
@Component
public class AgentContextSyncListener {

    private static final Logger log = LoggerFactory.getLogger(AgentContextSyncListener.class);

    private final AgentContextSyncService contextSyncService;

    public AgentContextSyncListener(AgentContextSyncService contextSyncService) {
        this.contextSyncService = contextSyncService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        try {
            contextSyncService.syncUserContext(event.userId());
            log.info("[AgentContextSync] 가입 컨텍스트 동기화 완료 (userId={})", event.userId());
        } catch (Exception e) {
            log.warn("[AgentContextSync] 가입 컨텍스트 동기화 실패 (userId={}) — 가입은 유지, 재동기 필요",
                    event.userId(), e);
        }
    }
}
