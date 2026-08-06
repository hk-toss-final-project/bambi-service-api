package com.bambi.service.agent.publish.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

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
        @JsonProperty("report_type") String reportType) {

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
}
