package com.bambi.service.agent.publish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 발행 스냅샷 배치의 한 항목 (docs/service-integration-guide.md §4 Claim 응답).
 * Claim 응답에 전체 Payload 가 담겨 추가 조회 없이 바로 Upsert 할 수 있다.
 * agent PublishSnapshotResponse 와 1:1 (snake_case).
 *
 * <p>태그는 두 가지다(2026-08-05 계약 확장, 단계적).
 * <ul>
 *   <li>{@code content_tags} — 리포트 내용에서 추출한 실제 태그(2~5개, REPORT-010). 카드에 노출할 값.
 *   <li>{@code tags} — 생성 요청 topic 을 그대로 에코한 값(2026-07-30). 자동 카드는 전부 같은 topic 이라
 *       card 노출용으론 부적합. 계약 하위호환 위해 유지만 한다.
 * </ul>
 * service 는 {@link #interestTags()} 로 content_tags 를 우선 쓰고, 아직 안 온 스냅샷은 tags 로 폴백한다.
 *
 * <p>userId 는 agent 계약상 문자열이다(agent 는 사용자 ID 를 불투명 식별자로 다룬다).
 * service-db 의 users.id 는 Long 이므로 {@link #userIdAsLong()} 로 변환해서 쓴다.
 * agent 가 UUID 등 숫자가 아닌 ID 를 쓰기로 바뀌면 이 메서드만 고치면 된다.
 *
 * <p>{@code report_type} — 생성 유형(MORNING_BRIEFING|ON_DEMAND, 2026-08-06 합의).
 * 소라(agent 게이트웨이)가 단계적으로 추가하는 필드라 아직 안 오는 스냅샷이 대부분이다 —
 * 없으면 null 로 관용 파싱한다({@link #normalizedReportType()}). 발행 처리를 절대 깨지 않는다.
 * {@code request_idempotency_key}는 Service의 생성 작업을 Publish 완료로 닫는 연결 키다.
 *
 * <p>{@code change_history_enabled} — 이 카드의 {@code body} 가 변경점(Delta) 4단 폼인지
 * 기존 자유 형식인지 알려주는 신호(2026-08-11 김기용, agent 593ec99). 프론트가 본문 헤더
 * 문자열을 파싱해 폼을 추측하지 않게 하려고 계약에 넣었다. 필드가 없던 과거 스냅샷은
 * 전부 자유 형식이라 미도착을 {@code false} 로 다룬다(agent 계약 문서 §4 명시).
 */
public record PublishItem(
        @JsonProperty("content_id") String contentId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("version") Integer version,
        @JsonProperty("snapshot_hash") String snapshotHash,
        @JsonProperty("title") String title,
        @JsonProperty("summary") String summary,
        @JsonProperty("body") String body,
        @JsonProperty("citations") List<Citation> citations,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("content_tags") List<String> contentTags,
        @JsonProperty("report_type") String reportType,
        @JsonProperty("request_idempotency_key") String requestIdempotencyKey,
        @JsonProperty("generation_topic") String generationTopic,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        // 추천 매칭용(2026-08-11 계약 A안). 카드가 매핑되는 taxonomy topic_key 집합 + 파생 버전.
        // content_tags·report_type 처럼 단계적 롤아웃 — 안 오면 null(관용). Card.replaceTaxonomyTopics 가 null 을 흡수.
        @JsonProperty("taxonomy_topic_ids") List<String> taxonomyTopicIds,
        @JsonProperty("taxonomy_version") String taxonomyVersion,
        @JsonProperty("cover_image") CoverImage coverImage,
        // body 렌더링 규칙 신호(2026-08-11). 필드가 없던 과거 스냅샷은 자유 형식이므로
        // 미도착 = false 가 맞다 → 박싱하지 않고 primitive 로 받아 Jackson 기본값(false)을 쓴다.
        @JsonProperty("change_history_enabled") boolean changeHistoryEnabled) {

    /** cover_image 도입 전 내부 생성 코드와 테스트가 사용하던 생성자 계약을 유지한다. */
    public PublishItem(
            String contentId,
            String userId,
            Integer version,
            String snapshotHash,
            String title,
            String summary,
            String body,
            List<Citation> citations,
            List<String> tags,
            List<String> contentTags,
            String reportType,
            String requestIdempotencyKey,
            String generationTopic,
            OffsetDateTime createdAt,
            List<String> taxonomyTopicIds,
            String taxonomyVersion) {
        this(contentId, userId, version, snapshotHash, title, summary, body,
                citations, tags, contentTags, reportType, requestIdempotencyKey,
                generationTopic, createdAt, taxonomyTopicIds, taxonomyVersion, null, false);
    }

    /** change_history_enabled 도입 전 생성자 계약을 유지한다(대표 이미지까지만 싣는 호출부). */
    public PublishItem(
            String contentId,
            String userId,
            Integer version,
            String snapshotHash,
            String title,
            String summary,
            String body,
            List<Citation> citations,
            List<String> tags,
            List<String> contentTags,
            String reportType,
            String requestIdempotencyKey,
            String generationTopic,
            OffsetDateTime createdAt,
            List<String> taxonomyTopicIds,
            String taxonomyVersion,
            CoverImage coverImage) {
        this(contentId, userId, version, snapshotHash, title, summary, body,
                citations, tags, contentTags, reportType, requestIdempotencyKey,
                generationTopic, createdAt, taxonomyTopicIds, taxonomyVersion, coverImage, false);
    }

    /**
     * 연결 키가 실려 왔는가. 2026-08-10 이전에 이미 생성돼 저장된 Snapshot 은 이 값이
     * <b>빈 문자열</b>이다(소급 안 됨, 김기용 확인). 그 구간만 근사 매칭으로 커버한다.
     */
    public boolean hasRequestIdempotencyKey() {
        return requestIdempotencyKey != null && !requestIdempotencyKey.isBlank();
    }

    /**
     * 카드에 저장·노출할 관심사 태그. 리포트 내용 기반 {@code content_tags} 를 우선하고,
     * 아직 안 온(단계적 롤아웃) 스냅샷은 기존 {@code tags}(생성 topic)로 폴백한다. 둘 다 없으면 빈 목록.
     */
    public List<String> interestTags() {
        if (contentTags != null && !contentTags.isEmpty()) {
            return contentTags;
        }
        return tags != null ? tags : List.of();
    }

    /**
     * 저장용 생성 유형. 계약이 아직 단계적 롤아웃이라 관용 파싱한다 —
     * 미도착(null)·공백은 null, 컬럼 길이(30)를 넘는 예상 밖 값도 저장 실패로 발행을
     * 깨뜨리는 대신 null 로 다룬다(유형 미상). 값 자체는 열거 검증 없이 그대로 보존한다.
     */
    public String normalizedReportType() {
        if (reportType == null || reportType.isBlank()) {
            return null;
        }
        String value = reportType.strip();
        return value.length() > 30 ? null : value;
    }

    /**
     * 대표 이미지 저장용 값. 선택 필드가 잘못돼도 발행 전체를 실패시키지 않도록
     * 두 URL이 모두 절대 HTTP(S)일 때만 정규화하고, 나머지는 이미지 없음으로 다룬다.
     */
    public CoverImage normalizedCoverImage() {
        if (coverImage == null) {
            return null;
        }
        String imageUrl = normalizeHttpUrl(coverImage.url());
        String sourceUrl = normalizeHttpUrl(coverImage.sourceUrl());
        if (imageUrl == null || sourceUrl == null) {
            return null;
        }
        return new CoverImage(
                imageUrl,
                sourceUrl,
                normalizeText(coverImage.sourceTitle(), 500),
                normalizeText(coverImage.reference(), 20));
    }

    private static String normalizeHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 2048) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getRawAuthority() == null
                    || uri.getRawAuthority().isBlank()
                    || uri.getUserInfo() != null) {
                return null;
            }
            return normalized;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if (codePointCount <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, maxLength));
    }

    /**
     * service-db 저장용 사용자 ID. 숫자가 아니면 처리 실패로 보고 예외를 던진다
     * (워커가 retryable=false 로 ACK 하도록).
     */
    public Long userIdAsLong() {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "agent user_id 가 service-db 사용자 ID 형식이 아님: " + userId, e);
        }
    }

    /** 출처(인용). 출처 없는 카드 금지 원칙의 근거 데이터. */
    public record Citation(
            @JsonProperty("title") String title,
            @JsonProperty("url") String url) {
    }

    /** 리포트 대표 이미지와 화면에 표시할 실제 인용 출처. */
    public record CoverImage(
            @JsonProperty("url") String url,
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("source_title") String sourceTitle,
            @JsonProperty("reference") String reference) {
    }
}
