package com.bambi.service.scrap;

import com.bambi.service.agent.dto.AgentAcceptedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 스크랩 트랜잭션의 Outbox 기록과 폴러의 Claim·종결 상태를 관리한다. */
@Service
public class ScrapWikiOutboxService {

    private static final Logger log = LoggerFactory.getLogger(ScrapWikiOutboxService.class);

    private final ScrapWikiOutboxRepository repository;

    public ScrapWikiOutboxService(ScrapWikiOutboxRepository repository) {
        this.repository = repository;
    }

    /** 스크랩 생성과 같은 트랜잭션에 Wiki 편입 이벤트를 기록한다. */
    public void enqueueAdd(long userId, long cardId, String contentId) {
        repository.save(ScrapWikiOutboxEvent.add(userId, cardId, contentId));
    }

    /** 최근 저장 이벤트를 참조하는 Wiki 해제 이벤트를 같은 트랜잭션에 기록한다. */
    public void enqueueRemove(long userId, long cardId, String contentId) {
        repository.findFirstByUserIdAndCardIdAndActionOrderByIdDesc(userId, cardId, "ADD")
                .ifPresentOrElse(
                        added -> repository.save(ScrapWikiOutboxEvent.remove(
                                userId, cardId, contentId, added.getSourceEventId())),
                        () -> log.warn(
                                "[ScrapWikiOutbox] 연결할 ADD 이벤트 없음 — REMOVE 생략(userId={}, cardId={})",
                                userId, cardId));
    }

    /** 카드별 선행 이벤트 순서를 지키며 처리할 Batch를 점유한다. */
    @Transactional
    public List<ScrapWikiOutboxEvent> claim(int limit) {
        List<ScrapWikiOutboxEvent> claimed = repository.findClaimable(limit);
        claimed.forEach(ScrapWikiOutboxEvent::markProcessing);
        return claimed;
    }

    /** Agent 접수와 상태 추적 등록이 끝난 이벤트를 전달 완료로 닫는다. */
    @Transactional
    public void markDelivered(long id, AgentAcceptedJob accepted) {
        repository.findById(id).ifPresent(event -> event.markDelivered(accepted));
    }

    /** 실패한 이벤트를 다음 폴링에서 재시도하도록 되돌린다. */
    @Transactional
    public void markRetry(long id, Throwable error) {
        repository.findById(id).ifPresent(event -> event.markRetry(error.getMessage()));
    }
}
