package com.bambi.service.generation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
 * @param topic          topics 가 없으면 실제 검색어(1~500자), 있으면 카드 제목용 문구.
 * @param topics         한 리포트가 함께 다룰 주제 목록(최대 5개). 순서가 곧 리포트 섹션 순서다.
 *                       비어 있으면 직렬화에서 생략돼 기존 단일 주제 동작 그대로다.
 * @param contentType    기본 interest_news_card.
 * @param language       생략 시 컨텍스트의 선호 언어.
 * @param scheduledAt    실행 예약 시각. **시간대 필수** (예: 2026-07-30T07:00:00+09:00). null 이면 즉시 실행 대상.
 * @param reportType     생성 유형 (2026-08-06 계약: MORNING_BRIEFING | ON_DEMAND | ONBOARDING).
 *                       agent 가 발행 snapshot(PublishItem)에 그대로 실어 돌려주고, service 가 claim 에서 저장한다.
 *                       ONBOARDING 은 agent 자동 경로라 service 트리거는 앞 2개만 보낸다.
 * @param changeHistoryEnabled 변경점(Delta) 추적 (agent-api #12 김기용). 켜면 직전 보고서 이후의
 *                       신규·갱신 사실을 갈라 정리한 통합 보고서를 만든다 — <b>기존 생성 경로를 대체한다.</b>
 *                       <b>끌 때는 false 가 아니라 null 을 넣는다</b> — agent 기본값이 false 라
 *                       보낼 이유가 없고, null 이어야 직렬화에서 빠져 기존 요청 본문과 완전히 같아진다.
 * @param generationScope 검색 범위 (agent 계약). null(기본)이면 SINGLE_TOPIC 동작 그대로다.
 *                       {@code INTEREST_BUNDLE} 이면 활성 LLM Wiki 관심사와 연결 노드 묶음으로
 *                       깊게 파는 리포트를 만든다 — 이때 {@code topic}·{@code topics} 는 보내지 않고
 *                       (topics 동시 전송은 agent 가 422 로 거부), 루트 키워드는 agent 가 관심사에서 정한다.
 * @param interestId     INTEREST_BUNDLE 의 루트 관심사 UUID = {@code GET /api/wiki/tags} 의
 *                       {@code tagId} (계약 문서 대응표: agent {@code interest_id} 리네임 값).
 *                       INTEREST_BUNDLE 에서만 필수, 그 외엔 null(직렬화 생략).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)   // language/scheduled_at/report_type/topics 빈 값은 직렬화 생략
public record GenerationRequest(
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("topic") String topic,
        @JsonProperty("topics") List<String> topics,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("language") String language,
        @JsonProperty("scheduled_at") OffsetDateTime scheduledAt,
        @JsonProperty("report_type") String reportType,
        @JsonProperty("change_history_enabled") Boolean changeHistoryEnabled,
        @JsonProperty("generation_scope") String generationScope,
        @JsonProperty("interest_id") String interestId) {

    /** agent {@code GenerationScope}(mvp.py) 의 범주 묶음 값. report_type 이 아니다 — 새 report_type 값 금지(계약 #20). */
    public static final String SCOPE_INTEREST_BUNDLE = "INTEREST_BUNDLE";

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
        return new GenerationRequest(idempotencyKey, searchTopic, List.of(), contentType,
                null, null, reportType, changeHistory ? Boolean.TRUE : null, null, null);
    }

    /**
     * 관심사 범주(깊게 파기) 요청 — 루트 관심사와 위키 연결 노드 묶음으로 통합 리포트를 만든다
     * (agent-api #16, 프롬프트 가드 f0e3bed·c5e63ce + 벤치마크 10/10 검증 후 채택, 2026-08-10 우석·기용).
     *
     * <p>{@code topic}·{@code topics} 는 싣지 않는다 — 루트 키워드는 agent 가 관심사에서 정하고,
     * {@code topics} 동시 전송은 agent model_validator 가 422 로 거부한다. Delta 와의 조합도
     * 계약에 정의가 없어 싣지 않는다(호출부가 먼저 거른다).
     *
     * @param interestTagId {@code GET /api/wiki/tags} 응답의 {@code tagId}. 형식 검증은 호출부 몫.
     */
    public static GenerationRequest interestBundle(String idempotencyKey, String contentType,
                                                   String reportType, String interestTagId) {
        return new GenerationRequest(idempotencyKey, null, List.of(), contentType,
                null, null, reportType, null, SCOPE_INTEREST_BUNDLE, interestTagId);
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
        if (topics == null || topics.isEmpty()) {
            throw new IllegalArgumentException(
                    "topics 없이 제목용 문구를 topic 으로 보내면 그 문구로 검색된다 — 호출 전에 걸러야 한다.");
        }
        // 아침 브리핑에는 Delta 를 켜지 않는다. 둘 다 "기존 생성 경로를 대체"라서 어느 쪽이
        // 이기는지 계약에 정의가 없다(agent-api #12 / #20). 온디맨드에서만 선택한다.
        return new GenerationRequest(idempotencyKey, titleTopic, List.copyOf(topics), contentType,
                null, null, reportType, null, null, null);
    }
}
