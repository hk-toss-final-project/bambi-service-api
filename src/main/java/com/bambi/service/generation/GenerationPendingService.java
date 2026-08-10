package com.bambi.service.generation;

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
 * <p>노출은 최근 {@link #VISIBLE_WINDOW} 안의 PENDING 만 — 완료 전환(claim 연결고리)이 붙기
 * 전까지 오래된 접수가 "처리중"으로 영영 남는 것을 시간 창으로 막는다(후속: 소라 협의).
 */
@Service
public class GenerationPendingService {

    /** 생성 유형 값 (2026-08-06 계약): 아침 정기 브리핑. */
    public static final String REPORT_TYPE_MORNING_BRIEFING = "MORNING_BRIEFING";
    /** 생성 유형 값 (2026-08-06 계약): 사용자 즉시 생성. */
    public static final String REPORT_TYPE_ON_DEMAND = "ON_DEMAND";

    private static final Logger log = LoggerFactory.getLogger(GenerationPendingService.class);
    private static final Duration VISIBLE_WINDOW = Duration.ofMinutes(60);

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

    /**
     * 발행 도착 → 그 요청의 접수 펜딩을 완료로 전환한다(2026-08-10, agent 가
     * {@code request_idempotency_key} 를 스냅샷에 에코하면서 가능해졌다).
     *
     * <p>키가 없으면(구 스냅샷·빈 값) 아무것도 하지 않는다 — 그 펜딩은 종전처럼
     * {@link #VISIBLE_WINDOW} 가 지나 목록에서 빠진다.
     *
     * <p><b>실패를 삼킨다.</b> 이 전환은 "처리중 표시를 지우는" 부가 작업이라,
     * 실패해도 이미 저장된 카드·리포트를 되돌리면 안 된다(접수 기록과 같은 정책).
     */
    public void completeByIdempotencyKey(long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        try {
            int updated = pendingRepository.markCompleted(userId, idempotencyKey.strip());
            if (updated > 0) {
                log.info("[GenerationPending] 완료 전환 userId={}, key={}", userId, idempotencyKey);
            } else {
                // 정상 경로다 — 온보딩 리포트처럼 service 접수를 안 거친 생성이거나, 재-claim 두 번째.
                log.debug("[GenerationPending] 전환 대상 없음 userId={}, key={}", userId, idempotencyKey);
            }
        } catch (Exception e) {
            log.warn("[GenerationPending] 완료 전환 실패 (userId={}, key={}) — 발행은 유지",
                    userId, idempotencyKey, e);
        }
    }

    /** 본인 최근 60분 PENDING 목록 — 홈 "처리중" 슬롯용. */
    @Transactional(readOnly = true)
    public List<GenerationPendingResponse> listRecent(long userId) {
        OffsetDateTime after = OffsetDateTime.now().minus(VISIBLE_WINDOW);
        return pendingRepository
                .findByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(userId, "PENDING", after)
                .stream()
                .map(GenerationPendingResponse::from)
                .toList();
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
