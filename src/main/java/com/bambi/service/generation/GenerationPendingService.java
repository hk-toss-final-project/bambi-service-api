package com.bambi.service.generation;

import com.bambi.service.agent.jobs.AgentJobStatus;
import com.bambi.service.agent.publish.dto.PublishItem;
import com.bambi.service.generation.dto.GenerationPendingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 생성 접수(펜딩) 기록·조회 — 온디맨드/스케줄러 공용 접수 레이어 (2026-08-06 합의, 우석).
 *
 * <p>접수 기록은 agent 202 접수가 끝난 뒤에 남긴다(실패한 요청이 유령 펜딩을 만들지 않게).
 * 기록 실패는 접수 자체를 되돌리지 않는다 — agent Job 은 이미 등록됐으므로 warn 만 남긴다
 * (북마크 위키 중계와 같은 정책).
 *
 * <p>Agent Job 상태는 5초 폴링으로 갱신하고, 완료는 Publish Snapshot이 Service DB에 반영된
 * 트랜잭션에서 확정한다. 사용자 조회에는 최근 24시간의 종결 상태도 포함한다.
 */
@Service
public class GenerationPendingService {

    /** 생성 유형 값 (2026-08-06 계약): 아침 정기 브리핑. */
    public static final String REPORT_TYPE_MORNING_BRIEFING = "MORNING_BRIEFING";
    /** 생성 유형 값 (2026-08-06 계약): 사용자 즉시 생성. */
    public static final String REPORT_TYPE_ON_DEMAND = "ON_DEMAND";

    private static final Logger log = LoggerFactory.getLogger(GenerationPendingService.class);
    private static final Duration ACTIVE_VISIBLE_WINDOW = Duration.ofHours(6);
    private static final Duration JOB_VISIBLE_WINDOW = Duration.ofHours(24);
    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "RUNNING", "PUBLISHING");

    private final GenerationPendingRepository pendingRepository;

    public GenerationPendingService(GenerationPendingRepository pendingRepository) {
        this.pendingRepository = pendingRepository;
    }

    /**
     * 접수 사실을 멱등 기록하고 펜딩 id 를 반환한다. id 는 멱등키 파생 결정적 UUID 라
     * 같은 접수(같은 분 연타·스케줄러 재시도)는 같은 id 로 모여 중복 행이 생기지 않는다.
     * 기록 실패는 삼킨다 — agent 접수는 이미 성공했으므로 트리거 응답을 막지 않는다.
     */
    public String register(long userId, String idempotencyKey, String reportType,
                           String topic, String contentType, String agentJobId) {
        UUID id = deterministicId(idempotencyKey);
        try {
            // 트랜잭션은 리포지토리 메서드(REQUIRES_NEW)가 직접 연다 — 서비스 자기호출은 프록시를 안 탄다.
            pendingRepository.insertPending(id, userId, idempotencyKey, reportType,
                    truncate(topic, 500), contentType, agentJobId);
        } catch (Exception e) {
            log.warn("[GenerationPending] 접수 기록 실패 (userId={}, key={}) — 접수는 유지",
                    userId, idempotencyKey, e);
        }
        return id.toString();
    }

    /** 본인 활성 생성 작업 목록 — 기존 pending API 호환용. */
    @Transactional(readOnly = true)
    public List<GenerationPendingResponse> listRecent(long userId) {
        OffsetDateTime after = OffsetDateTime.now().minus(ACTIVE_VISIBLE_WINDOW);
        return pendingRepository
                .findByUserIdAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        userId, ACTIVE_STATUSES, after)
                .stream()
                .map(GenerationPendingResponse::from)
                .toList();
    }

    /** 본인 최근 생성 작업의 진행·완료·실패 상태를 반환한다. */
    @Transactional(readOnly = true)
    public List<GenerationPendingResponse> listRecentJobs(long userId) {
        return pendingRepository
                .findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
                        userId, OffsetDateTime.now().minus(JOB_VISIBLE_WINDOW))
                .stream()
                .map(GenerationPendingResponse::from)
                .toList();
    }

    /** 이번 tick에서 Agent 상태를 조회할 생성 작업을 반환한다. */
    @Transactional(readOnly = true)
    public List<GenerationPending> findPollable(int limit) {
        return pendingRepository.findPollable(limit);
    }

    /** Agent Job 상태를 사용자 생성 작업 상태로 반영한다. */
    public void applyAgentStatus(GenerationPending pending, AgentJobStatus status) {
        switch (status.status()) {
            case "queued" -> pendingRepository.updateStatus(pending.getId(), "PENDING", null);
            case "running" -> pendingRepository.updateStatus(pending.getId(), "RUNNING", null);
            case "completed" -> pendingRepository.updateStatus(pending.getId(), "PUBLISHING", null);
            case "failed" -> pendingRepository.updateStatus(pending.getId(), "FAILED", status.errorCode());
            case "cancelled" -> pendingRepository.updateStatus(pending.getId(), "CANCELLED", status.errorCode());
            default -> pendingRepository.updateStatus(pending.getId(), "FAILED", "UNKNOWN_JOB_STATUS");
        }
    }

    /** Agent에서 사라진 활성 Job을 최종 실패로 닫는다. */
    public void markMissing(GenerationPending pending) {
        pendingRepository.updateStatus(pending.getId(), "FAILED", "JOB_NOT_FOUND");
    }

    /** Publish Snapshot 반영과 같은 트랜잭션에서 생성 작업을 완료한다. */
    public void markCompleted(long userId, String idempotencyKey) {
        markCompleted(userId, idempotencyKey, null);
    }

    /** 완료 처리 + 완성 카드 연결. 카드 식별자를 모르면 {@code cardPublicId} 는 null 이어도 된다. */
    public void markCompleted(long userId, String idempotencyKey, UUID cardPublicId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        int closed = pendingRepository.markCompleted(userId, idempotencyKey, cardPublicId);
        if (closed == 0 && cardPublicId != null) {
            // 이미 COMPLETED 였던 행(재발행 등)에도 카드 링크는 채워 준다.
            pendingRepository.linkCard(userId, idempotencyKey, cardPublicId);
        }
    }

    /**
     * 발행된 Snapshot 하나로 해당 생성 작업을 닫는다 — <b>정공법 우선, 구 Snapshot 만 근사 매칭.</b>
     *
     * <p>연결 키({@code request_idempotency_key})가 실려 오면 그것으로 정확히 닫는다(2026-08-10
     * 김기용 반영). <b>2026-08-10 이전에 이미 생성돼 저장된 Snapshot 은 이 값이 빈 문자열</b>이고
     * 소급되지 않아, 그 구간만 근사 매칭으로 덮는다.
     *
     * <p><b>매칭되는 접수가 없는 것은 오류가 아니다.</b> agent 자체 생성 경로(온보딩 첫 리포트,
     * 관심사 리포트 등)의 카드에는 agent 내부 멱등키가 실려 오는데, service 가 보낸 요청이
     * 아니므로 이을 펜딩이 애초에 없다(김기용 확인). 조용히 넘어간다.
     *
     * @param newCard 신규 카드 발행인가. 근사 매칭은 <b>신규에만</b> 건다 — 기존 카드의 v2 재발행이
     *                나중에 접수된 다른 펜딩을 잘못 닫는 것을 막는다(우석 가드 2).
     */
    public void completeFromSnapshot(long userId, PublishItem item, UUID cardPublicId, boolean newCard) {
        if (item.hasRequestIdempotencyKey()) {
            markCompleted(userId, item.requestIdempotencyKey(), cardPublicId);
            return;
        }
        if (!newCard) {
            return;
        }
        completeByApproximateMatch(userId, item, cardPublicId);
    }

    /**
     * 연결 키가 없던 구 Snapshot 용 폴백. 정확도 한계를 알고 쓴다 — 같은 조건에 열린 접수가
     * 둘 이상이면(같은 주제 온디맨드 연타, delta 토글 등) <b>카드-펜딩 짝이 뒤바뀔 수 있다.</b>
     * 다만 둘 다 결국 닫히므로 "처리중"이 화면에 남지는 않는다.
     */
    private void completeByApproximateMatch(long userId, PublishItem item, UUID cardPublicId) {
        String reportType = item.normalizedReportType();
        if (reportType == null || item.createdAt() == null) {
            // 유형이나 발행 시각을 모르면 어느 접수인지 좁힐 수 없다 — 잘못 닫느니 열어 둔다.
            return;
        }
        // 아침 브리핑은 요청 topic 이 고정 문구("오늘의 관심사 브리핑")라 펜딩의 topic(topics[0])과
        // 다르다. 대신 멱등키가 {날짜}-{userId}-{contentType} 라 그날 1건뿐이라 주제를 안 봐도 유일하다.
        String topic = REPORT_TYPE_MORNING_BRIEFING.equals(reportType) ? null : item.generationTopic();
        pendingRepository.findApproximateMatch(userId, reportType, topic, item.createdAt())
                .ifPresent(pending -> {
                    pendingRepository.markCompletedById(pending.getId(), cardPublicId);
                    log.info("[GenerationPending] 연결 키 없는 구 Snapshot — 근사 매칭으로 완료 "
                                    + "(userId={}, reportType={}, topic={}, pendingId={})",
                            userId, reportType, topic, pending.getId());
                });
    }

    /**
     * 펜딩 id — 멱등키 파생 결정적 UUID. 트리거 응답(GenerationTriggerResponse.id)과
     * 같은 규칙이라 접수 응답과 펜딩 목록을 프론트가 매칭할 수 있다(우석 08-05 키 설계).
     */
    public static UUID deterministicId(String idempotencyKey) {
        return UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
