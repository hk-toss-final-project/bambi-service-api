package com.bambi.service.generation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface GenerationPendingRepository extends JpaRepository<GenerationPending, UUID> {

    /**
     * 접수 멱등 insert — 같은 id(멱등키 파생)·같은 멱등키 재접수는 조용히 흡수한다
     * (같은 분 연타·스케줄러 재시도가 행을 늘리지 않게). NotificationRepository 와 같은 패턴.
     * 호출부(트리거)는 트랜잭션 없는 HTTP 경로라 REQUIRES_NEW 로 여기서 직접 연다
     * (서비스 내부 self-invocation 은 프록시를 안 타 @Transactional 이 무시된다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
            INSERT INTO service.generation_pendings (
                id, user_id, idempotency_key, report_type, topic, content_type, agent_job_id, status
            ) VALUES (
                :id, :userId, :idempotencyKey, :reportType, :topic, :contentType, :agentJobId, 'PENDING'
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    void insertPending(
            @Param("id") UUID id,
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("reportType") String reportType,
            @Param("topic") String topic,
            @Param("contentType") String contentType,
            @Param("agentJobId") String agentJobId);

    /** 본인 것만, 지정 시각 이후 접수된 PENDING 을 최신순으로 — 처리중 슬롯 노출용. */
    List<GenerationPending> findByUserIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
            Long userId, String status, OffsetDateTime after);

    /**
     * 발행 도착 → 해당 접수를 완료로 전환한다. {@code status = 'PENDING'} 조건이 곧 멱등장치다 —
     * 재-claim 으로 같은 스냅샷이 두 번 와도 두 번째는 0행이라 아무 일도 하지 않는다.
     *
     * <p>발행 트랜잭션과 분리한다(REQUIRES_NEW). 펜딩 전환이 실패해도 카드·리포트 저장을
     * 되돌리면 안 되기 때문이다 — 접수 기록({@link #insertPending})과 같은 정책이다.
     *
     * @return 전환된 행 수(0 = 해당 펜딩 없음 또는 이미 완료)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
            UPDATE service.generation_pendings
            SET status = 'COMPLETED', updated_at = now()
            WHERE user_id = :userId
              AND idempotency_key = :idempotencyKey
              AND status = 'PENDING'
            """, nativeQuery = true)
    int markCompleted(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey);
}
