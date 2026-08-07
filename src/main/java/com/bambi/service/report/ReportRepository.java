package com.bambi.service.report;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /** 발행 워커의 멱등 Upsert 판단용 — 같은 사용자+external_content_id 리포트 조회 */
    Optional<Report> findByUserIdAndExternalContentId(Long userId, String externalContentId);

    /** 관리자 대시보드 — 살아있는 리포트 총수(soft delete 제외). */
    long countByDeletedAtIsNull();

    /** 관리자 대시보드 — 지정 시각 이후 생성된 리포트 수(오늘 생성 집계, soft delete 제외). */
    long countByDeletedAtIsNullAndCreatedAtGreaterThanEqual(OffsetDateTime from);

    /**
     * 리포트 단건 조회 (대외 식별자 publicId + 소유자 범위, soft delete 제외).
     * 카드 상세의 본문 진입용. citations 를 함께 로딩(@EntityGraph).
     */
    @EntityGraph(attributePaths = "citations")
    Optional<Report> findByPublicIdAndUserIdAndDeletedAtIsNull(UUID publicId, Long userId);

    /**
     * 리포트 단건 조회 (소유자 검증 없이 publicId 로만, soft delete 제외).
     * 접근 권한(내 것 or PUBLIC 카드 참조)은 서비스에서 판단한다. citations 함께 로딩.
     */
    @EntityGraph(attributePaths = "citations")
    Optional<Report> findByPublicIdAndDeletedAtIsNull(UUID publicId);
}
