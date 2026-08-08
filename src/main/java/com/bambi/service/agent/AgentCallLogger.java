package com.bambi.service.agent;

import com.bambi.service.admin.AiRequestLog;
import com.bambi.service.admin.AiRequestLogRepository;
import com.bambi.service.admin.AiResponseLog;
import com.bambi.service.admin.AiResponseLogRepository;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * agent 호출 1건을 service.ai_request_logs / ai_response_logs 에 적재한다.
 *
 * <p>요청/응답을 각각 REQUIRES_NEW 트랜잭션으로 커밋한다 — agent 호출이 실패해
 * 상위 로직이 롤백/예외로 끝나도 "무엇을 호출했고 어떻게 실패했는지"는 로그로 남긴다.
 *
 * <p><b>두 본문 컬럼이 모두 {@code jsonb} 다.</b> 그래서 JSON 이 아닌 값을 그대로 저장하면
 * Postgres 파싱 오류가 나고, 그 예외가 <b>원래 던지려던 agent 오류를 덮어쓴다</b>
 * (AGENT_UNAVAILABLE 이 나가야 할 자리에 INTERNAL_ERROR 500 이 나간다 — 2026-08-08 여진 발견).
 * 실패 경로가 넘기는 값은 JSON 이 아닌 경우가 많다.
 *
 * <ul>
 *   <li>연결 실패: {@code "I/O error on POST request for ...: Connection refused"} — 평문</li>
 *   <li>본문 없는 4xx/5xx: {@code ""} — 빈 문자열도 유효한 JSON 이 아니다</li>
 *   <li>프록시가 끼어든 502/504: HTML 에러 페이지</li>
 * </ul>
 *
 * <p>그래서 저장 전에 항상 {@link #asJsonOrWrap} 를 태운다. 컬럼 타입은 {@code jsonb} 로 둔다 —
 * 관리자 화면(#57)이 이 값을 원문으로 내려주고 있어 {@code text} 로 바꾸면 그쪽이 영향받는다.
 */
@Component
public class AgentCallLogger {

    private final AiRequestLogRepository requestLogRepository;
    private final AiResponseLogRepository responseLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    /** 뒤에 붙은 잡음까지 잡는다 — {@code "123 Connection refused"} 는 Postgres 가 거절하는데
     *  기본 readTree 는 앞의 123 만 읽고 통과시킨다. */
    private final ObjectReader strictJsonReader;

    public AgentCallLogger(AiRequestLogRepository requestLogRepository,
                           AiResponseLogRepository responseLogRepository,
                           UserRepository userRepository,
                           ObjectMapper objectMapper) {
        this.requestLogRepository = requestLogRepository;
        this.responseLogRepository = responseLogRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.strictJsonReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * 호출 직전 요청 로그를 남기고 그 id 를 돌려준다. 응답 로그와 이 id 로 이어진다.
     *
     * @param userId      요청 주체 사용자 id (사용자와 무관한 호출이면 null)
     * @param endpoint    호출한 agent 경로
     * @param requestBody 요청 본문 JSON 문자열 (없으면 null)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long logRequest(Long userId, String endpoint, String requestBody) {
        // getReferenceById: 프록시만 얻어 FK(user_id)만 채운다 — User 를 실제로 로드하지 않는다.
        User user = (userId == null) ? null : userRepository.getReferenceById(userId);
        AiRequestLog saved = requestLogRepository.save(
                new AiRequestLog(user, endpoint, asJsonOrWrap(requestBody)));
        return saved.getId();
    }

    /**
     * 호출 결과(응답/실패)를 남긴다.
     *
     * @param requestId    {@link #logRequest} 가 돌려준 id (null 이면 기록 건너뜀)
     * @param statusCode   HTTP 상태코드 (연결 실패 등으로 없으면 null)
     * @param latencyMs    소요 시간(ms)
     * @param responseBody 응답 본문 또는 에러 메시지 (없으면 null). JSON 이 아니어도 된다 —
     *                     {@code {"raw": ...}} 로 감싸 저장한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logResponse(Long requestId, Integer statusCode, Integer latencyMs, String responseBody) {
        if (requestId == null) {
            return;
        }
        responseLogRepository.save(
                new AiResponseLog(requestId, statusCode, latencyMs, asJsonOrWrap(responseBody)));
    }

    /**
     * {@code jsonb} 에 넣어도 안전한 값으로 바꾼다.
     *
     * <ul>
     *   <li>유효한 JSON 이면 그대로 — 정상 응답 본문은 원문이 보존된다(관리자 화면이 읽는다)</li>
     *   <li>비어 있으면 {@code null} — 빈 문자열은 {@code jsonb} 가 거절한다</li>
     *   <li>그 외(평문 에러 메시지·HTML 등)는 {@code {"raw": "원문"}} 으로 감싼다.
     *       버리지 않는 이유는 <b>agent 가 왜 실패했는지가 바로 이 문자열에 있기 때문</b>이다</li>
     * </ul>
     */
    private String asJsonOrWrap(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            strictJsonReader.readTree(trimmed);
            return trimmed;
        } catch (Exception notJson) {
            // 문자열 하나를 담는 노드라 직렬화가 실패할 수 없다(이스케이프는 Jackson 이 처리).
            return objectMapper.createObjectNode().put("raw", value).toString();
        }
    }
}
