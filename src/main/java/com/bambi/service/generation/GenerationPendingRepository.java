package com.bambi.service.generation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

    List<GenerationPending> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long userId, OffsetDateTime after);

    List<GenerationPending> findByUserIdAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            Long userId, List<String> statuses, OffsetDateTime after);

    @Query(value = """
            SELECT * FROM service.generation_pendings
            WHERE status IN ('PENDING', 'RUNNING')
              AND agent_job_id IS NOT NULL
            ORDER BY created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<GenerationPending> findPollable(@Param("limit") int limit);

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE service.generation_pendings
            SET status = :status, error_code = :errorCode, updated_at = now(),
                completed_at = CASE WHEN :status IN ('FAILED', 'CANCELLED') THEN now() ELSE NULL END
            WHERE id = :id
            """, nativeQuery = true)
    void updateStatus(@Param("id") UUID id, @Param("status") String status,
                      @Param("errorCode") String errorCode);

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE service.generation_pendings
            SET status = 'COMPLETED', error_code = NULL, updated_at = now(), completed_at = now(),
                card_public_id = COALESCE(:cardPublicId, card_public_id)
            WHERE user_id = :userId AND idempotency_key = :idempotencyKey
              AND status <> 'COMPLETED'
            """, nativeQuery = true)
    int markCompleted(@Param("userId") Long userId,
                      @Param("idempotencyKey") String idempotencyKey,
                      @Param("cardPublicId") UUID cardPublicId);

    /**
     * 이미 COMPLETED 인 행에도 카드 링크만 채운다 — 완료 전환이 먼저 일어난 뒤(예: 재발행)
     * 카드 식별자를 알게 된 경우. 상태·시각은 건드리지 않는다.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE service.generation_pendings
            SET card_public_id = :cardPublicId, updated_at = now()
            WHERE user_id = :userId AND idempotency_key = :idempotencyKey
              AND card_public_id IS NULL
            """, nativeQuery = true)
    int linkCard(@Param("userId") Long userId,
                 @Param("idempotencyKey") String idempotencyKey,
                 @Param("cardPublicId") UUID cardPublicId);

    /**
     * 연결 키가 없는 구(舊) Snapshot 용 근사 매칭 — 아직 안 닫힌 접수 중 <b>가장 오래된</b> 1건.
     *
     * <p>2026-08-10 이전에 만들어져 저장된 Snapshot 은 {@code request_idempotency_key} 가 빈
     * 문자열이라(소급 불가, 김기용 확인) 정확히 이을 수단이 없다. 그 구간만 이 쿼리로 덮는다.
     *
     * <ul>
     *   <li>{@code topic} 이 null 이면 <b>주제를 안 본다</b> — 아침 브리핑은 요청 topic 이
     *       고정 문구라 펜딩의 topic(topics[0])과 애초에 다르다. 대신 하루 1건 보장
     *       (멱등키가 {날짜}-{userId}-{contentType})이라 사용자·유형·날짜만으로 유일하다.</li>
     *   <li>{@code snapshotCreatedAt} 이후에 만들어진 접수는 제외한다 — 이 카드보다 <b>나중에</b>
     *       접수된 펜딩을 잘못 닫으면 아직 처리 중인 요청이 완료로 보인다(우석 가드 2).</li>
     *   <li>같은 조건에 열린 접수가 둘 이상이면 가장 오래된 것부터 닫는다. 카드-펜딩 짝이
     *       뒤바뀔 수 있으나 둘 다 결국 닫히므로 "처리중"이 남지는 않는다.</li>
     * </ul>
     */
    @Query(value = """
            SELECT * FROM service.generation_pendings
            WHERE user_id = :userId
              AND report_type = :reportType
              AND status <> 'COMPLETED'
              AND created_at <= :snapshotCreatedAt
              AND (CAST(:topic AS text) IS NULL OR topic = CAST(:topic AS text))
            ORDER BY created_at
            LIMIT 1
            """, nativeQuery = true)
    Optional<GenerationPending> findApproximateMatch(
            @Param("userId") Long userId,
            @Param("reportType") String reportType,
            @Param("topic") String topic,
            @Param("snapshotCreatedAt") OffsetDateTime snapshotCreatedAt);

    /** 근사 매칭으로 찾은 행 1건만 id 로 닫는다(멱등키가 아니라 id 기준). */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE service.generation_pendings
            SET status = 'COMPLETED', error_code = NULL, updated_at = now(), completed_at = now(),
                card_public_id = COALESCE(:cardPublicId, card_public_id)
            WHERE id = :id AND status <> 'COMPLETED'
            """, nativeQuery = true)
    int markCompletedById(@Param("id") UUID id, @Param("cardPublicId") UUID cardPublicId);
}
