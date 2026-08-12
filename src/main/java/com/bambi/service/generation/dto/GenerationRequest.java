package com.bambi.service.generation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 콘텐츠 생성 요청 (agent 계약: POST /internal/v1/users/{id}/generations, §3.4).
 * userId 는 경로에 있으므로 바디에 없다. agent 와 1:1 (snake_case).
 *
 * <p><b>⚠️ topics 를 채우면 topic 의 의미가 바뀐다 (2026-08-07 계약).</b> 평소 {@code topic} 은
 * agent 의 <b>실제 검색어</b>지만, {@code topics} 가 있으면 {@code topic} 은
 * <b>카드 제목·generation_topic 표시용</b>이 되고 본문이 다루는 주제는 {@code topics} 가 결정한다.
 * 두 규칙이 정반대다.
 *
 * <p>그래서 {@code topics} 가 있을 때만 고정 문구를 {@code topic} 에 넣어도 된다.
 * <b>{@code topics} 없이 고정 문구를 넣으면 그 문구로 기사를 검색해 엉뚱한 결과가 온다</b>
 * (2026-08-05 유림 확인). 두 값은 항상 같이 판단해야 해서 정적 팩토리로만 만들게 했다.
 *
 * @param idempotencyKey {날짜윈도우}-{userId}-{contentType} 규칙 — 스케줄러 재시도·중복 실행에도 Job 1개.
 * @param generationScope 생략 시 기존 단일·다중 주제 생성. {@code INTEREST_BUNDLE}이면 현재 활성
 *                       Wiki 관심사와 연결 노드 묶음을 사용한다.
 * @param interestId     {@code INTEREST_BUNDLE}에서 사용할 현재 활성 Wiki 관심사 UUID.
 * @param topic          topics 가 없으면 실제 검색어(1~500자), 있으면 카드 제목용 문구.
 * @param topics         한 리포트가 함께 다룰 주제 목록(최대 5개). 순서가 곧 리포트 섹션 순서다.
 *                       비어 있으면 직렬화에서 생략돼 기존 단일 주제 동작 그대로다.
 * @param contentType    기본 interest_news_card.
 * @param briefingDate   준비 Snapshot을 조회할 KST 날짜. 아침 브리핑 요청에서만 명시한다.
 * @param language       생략 시 컨텍스트의 선호 언어.
 * @param scheduledAt    실행 예약 시각. **시간대 필수** (예: 2026-07-30T07:00:00+09:00). null 이면 즉시 실행 대상.
 * @param reportType     생성 유형 (2026-08-06 계약: MORNING_BRIEFING | ON_DEMAND | ONBOARDING).
 *                       agent 가 발행 snapshot(PublishItem)에 그대로 실어 돌려주고, service 가 claim 에서 저장한다.
 *                       ONBOARDING 은 agent 자동 경로라 service 트리거는 앞 2개만 보낸다.
 * @param changeHistoryEnabled 변경점(Delta) 추적 (agent-api #12 김기용). 켜면 직전 보고서 이후의
 *                       신규·갱신 사실을 갈라 정리한 통합 보고서를 만든다 — <b>기존 생성 경로를 대체한다.</b>
 *                       <b>끌 때는 false 가 아니라 null 을 넣는다</b> — agent 기본값이 false 라
 *                       보낼 이유가 없고, null 이어야 직렬화에서 빠져 기존 요청 본문과 완전히 같아진다.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)   // language/scheduled_at/report_type/topics 빈 값은 직렬화 생략
public record GenerationRequest(
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("generation_scope") String generationScope,
        @JsonProperty("interest_id") String interestId,
        @JsonProperty("topic") String topic,
        @JsonProperty("topics") List<String> topics,
        @JsonProperty("content_type") String contentType,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @JsonProperty("briefing_date") LocalDate briefingDate,
        @JsonProperty("language") String language,
        @JsonProperty("scheduled_at") OffsetDateTime scheduledAt,
        @JsonProperty("report_type") String reportType,
        @JsonProperty("change_history_enabled") Boolean changeHistoryEnabled) {

    /**
     * 단일 주제 요청 — {@code topic} 이 <b>실제 검색어</b>다. 온디맨드가 쓴다.
     * 여기에는 절대 고정 문구를 넣지 않는다(그 문구로 검색한다).
     */
    public static GenerationRequest singleTopic(String idempotencyKey, String searchTopic,
                                                String contentType, String reportType) {
        return singleTopic(idempotencyKey, searchTopic, contentType, reportType, false);
    }

    /**
     * 변경점(Delta) 추적을 선택할 수 있는 단일 주제 요청.
     *
     * <p>{@code changeHistory} 가 false 면 필드를 <b>아예 싣지 않는다</b>(null → NON_EMPTY 로 생략).
     * agent 기본값이 false 라 결과는 같지만, 보내지 않아야 기존 요청 본문과 바이트 단위로 동일해
     * 이 변경이 기존 온디맨드 동작에 영향을 주지 않는 것이 명확해진다.
     */
    public static GenerationRequest singleTopic(String idempotencyKey, String searchTopic,
                                                String contentType, String reportType,
                                                boolean changeHistory) {
        return new GenerationRequest(idempotencyKey, null, null, searchTopic, List.of(), contentType,
                null, null, null, reportType, changeHistory ? Boolean.TRUE : null);
    }

    /**
     * 여러 주제를 한 장에 묶는 요청 — 본문 주제는 {@code topics} 가 정하고 {@code titleTopic} 은
     * 카드 제목용 문구다. 아침 브리핑이 쓴다.
     *
     * <p>{@code topics} 가 비면 {@code titleTopic} 이 검색어로 되살아나 엉뚱한 기사를 물어오므로
     * 호출부가 <b>보내기 전에</b> 걸러야 한다. 여기서도 마지막으로 막는다.
     */
    public static GenerationRequest multiTopic(String idempotencyKey, String titleTopic,
                                               List<String> topics, String contentType,
                                               String reportType) {
        return multiTopic(idempotencyKey, titleTopic, topics, contentType, reportType, false);
    }

    /**
     * 변경점(Delta) 추적을 선택할 수 있는 다중 주제 요청.
     *
     * <p><b>2026-08-12 이전에는 여기서 Delta 를 항상 꺼뒀다.</b> 당시 주석은 "Delta 와 topics[]
     * 둘 다 기존 생성 경로를 대체라 어느 쪽이 이기는지 계약에 정의가 없다(agent-api #12/#20)"
     * 였는데, 그 뒤 agent 가 <b>주제마다 Delta 를 따로 돌려 합치는 경로</b>를 구현하면서
     * (agent {@code _multi_topic_change_history}: 비교축이 (user_id, topic)이라 주제별로 나눠
     * 돌린다) 공백이 없어졌다. 근거를 확보한 주제만 비교하고, 실패하면 일반 생성으로 되돌린다.
     *
     * <p>그래서 "설정을 켜면 <b>모든 보고서</b>가 변경점 형식"(2026-08-12 여진 요구)을
     * 아침 브리핑에도 적용할 수 있다.
     */
    public static GenerationRequest multiTopic(String idempotencyKey, String titleTopic,
                                               List<String> topics, String contentType,
                                               String reportType, boolean changeHistory) {
        if (topics == null || topics.isEmpty()) {
            throw new IllegalArgumentException(
                    "topics 없이 제목용 문구를 topic 으로 보내면 그 문구로 검색된다 — 호출 전에 걸러야 한다.");
        }
        return new GenerationRequest(idempotencyKey, null, null, titleTopic,
                List.copyOf(topics), contentType, null, null, null, reportType,
                changeHistory ? Boolean.TRUE : null);
    }

    /** 준비 Snapshot 날짜를 고정한 아침 브리핑 다중 주제 요청을 만든다. */
    public static GenerationRequest morningBriefing(
            String idempotencyKey,
            String titleTopic,
            List<String> topics,
            String contentType,
            String reportType,
            LocalDate briefingDate) {
        return morningBriefing(
                idempotencyKey, titleTopic, topics, contentType, reportType, briefingDate, false);
    }

    /** 변경점(Delta) 설정을 실어 보내는 아침 브리핑 요청. */
    public static GenerationRequest morningBriefing(
            String idempotencyKey,
            String titleTopic,
            List<String> topics,
            String contentType,
            String reportType,
            LocalDate briefingDate,
            boolean changeHistory) {
        if (briefingDate == null) {
            throw new IllegalArgumentException("아침 브리핑 생성에는 briefingDate가 필요하다.");
        }
        GenerationRequest request = multiTopic(
                idempotencyKey, titleTopic, topics, contentType, reportType, changeHistory);
        return new GenerationRequest(
                request.idempotencyKey(), request.generationScope(), request.interestId(),
                request.topic(), request.topics(), request.contentType(), briefingDate,
                request.language(), request.scheduledAt(), request.reportType(),
                request.changeHistoryEnabled());
    }

    /**
     * 현재 활성 Wiki 관심사와 1홉 연결 노드를 묶는 관심사 리포트 요청.
     * 주제는 Agent가 관심사 루트에서 확정하므로 {@code topic}/{@code topics}를 함께 보내지 않는다.
     */
    public static GenerationRequest interestBundle(String idempotencyKey, String interestId,
                                                    String contentType, String reportType) {
        return interestBundle(idempotencyKey, interestId, contentType, reportType, false);
    }

    /**
     * 변경점(Delta) 설정을 실어 보내는 관심사 리포트 요청.
     *
     * <p>2026-08-12 이전에는 이 팩토리에 파라미터 자체가 없어 설정을 켜도 늘 꺼진 채 나갔다
     * (아침 브리핑과 달리 의도한 제외가 아니라 누락이었다).
     */
    public static GenerationRequest interestBundle(String idempotencyKey, String interestId,
                                                    String contentType, String reportType,
                                                    boolean changeHistory) {
        if (interestId == null || interestId.isBlank()) {
            throw new IllegalArgumentException("INTEREST_BUNDLE에는 현재 활성 interestId가 필요하다.");
        }
        return new GenerationRequest(idempotencyKey, "INTEREST_BUNDLE", interestId.strip(),
                null, List.of(), contentType, null, null, null, reportType,
                changeHistory ? Boolean.TRUE : null);
    }
}
