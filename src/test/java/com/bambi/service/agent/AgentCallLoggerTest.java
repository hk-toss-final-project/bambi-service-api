package com.bambi.service.agent;

import com.bambi.service.admin.AiRequestLog;
import com.bambi.service.admin.AiRequestLogRepository;
import com.bambi.service.admin.AiResponseLog;
import com.bambi.service.admin.AiResponseLogRepository;
import com.bambi.service.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentCallLogger} — 본문을 {@code jsonb} 에 넣어도 안전한 값으로 바꾸는지.
 *
 * <p>배경: 두 본문 컬럼이 {@code jsonb} 인데 실패 경로가 평문을 넘긴다. 그대로 저장하면
 * Postgres 파싱 오류가 나고, 그 예외가 원래 던지려던 agent 오류를 덮어써
 * AGENT_UNAVAILABLE 대신 INTERNAL_ERROR 500 이 나갔다(2026-08-08 여진 발견).
 */
class AgentCallLoggerTest {

    private final AiRequestLogRepository requestLogs = mock(AiRequestLogRepository.class);
    private final AiResponseLogRepository responseLogs = mock(AiResponseLogRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AgentCallLogger logger =
            new AgentCallLogger(requestLogs, responseLogs, users, new ObjectMapper());

    @Test
    void 연결_실패_평문은_raw_로_감싸_저장한다() {
        // 여진님이 실제로 만난 값 — 이게 그대로 들어가서 500 이 났다.
        logger.logResponse(1L, null, 12, "I/O error on POST request for \"http://agent:8000\": Connection refused");

        String saved = capturedResponseBody();
        assertThat(saved).startsWith("{\"raw\":");
        assertThat(saved).contains("Connection refused");
        assertThatIsValidJson(saved);
    }

    @Test
    void 정상_JSON_응답은_원문_그대로_보존한다() {
        // 관리자 화면(#57)이 이 값을 원문으로 내려주므로 감싸면 안 된다.
        logger.logResponse(1L, 200, 30, "{\"status\":\"ok\"}");

        assertThat(capturedResponseBody()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void 본문_없는_4xx_5xx_의_빈_문자열은_null_로_저장한다() {
        // 빈 문자열도 jsonb 가 거절한다. agent 가 연결된 배포에서도 502/504 로 실제 발생한다.
        logger.logResponse(1L, 502, 5, "");

        assertThat(capturedResponseBody()).isNull();
    }

    @Test
    void 공백뿐인_본문도_null_로_저장한다() {
        logger.logResponse(1L, 504, 5, "   \n ");

        assertThat(capturedResponseBody()).isNull();
    }

    @Test
    void 프록시가_돌려준_HTML_에러페이지도_감싸_저장한다() {
        logger.logResponse(1L, 502, 8, "<html><body>502 Bad Gateway</body></html>");

        String saved = capturedResponseBody();
        assertThat(saved).contains("502 Bad Gateway");
        assertThatIsValidJson(saved);
    }

    @Test
    void 앞부분만_JSON_처럼_보이는_값도_감싼다() {
        // 기본 readTree 는 앞의 123 만 읽고 통과시키는데 Postgres 는 통째로 거절한다.
        logger.logResponse(1L, null, 3, "123 Connection refused");

        String saved = capturedResponseBody();
        assertThat(saved).startsWith("{\"raw\":");
        assertThatIsValidJson(saved);
    }

    @Test
    void 따옴표와_역슬래시가_섞여도_유효한_JSON_으로_만든다() {
        logger.logResponse(1L, null, 3, "I/O error on \"POST\" \\ path");

        assertThatIsValidJson(capturedResponseBody());
    }

    @Test
    void 요청_본문도_같은_규칙으로_정규화한다() {
        when(requestLogs.save(any(AiRequestLog.class))).thenReturn(mock(AiRequestLog.class));

        logger.logRequest(null, "/internal/v1/users/1/context", "직렬화되지 않은 평문");

        ArgumentCaptor<AiRequestLog> captor = ArgumentCaptor.forClass(AiRequestLog.class);
        verify(requestLogs).save(captor.capture());
        assertThatIsValidJson(captor.getValue().getRequestBody());
    }

    @Test
    void 요청_로그_id_가_없으면_응답_로그를_남기지_않는다() {
        logger.logResponse(null, 500, 10, "무엇이든");

        verify(responseLogs, never()).save(any());
    }

    private String capturedResponseBody() {
        ArgumentCaptor<AiResponseLog> captor = ArgumentCaptor.forClass(AiResponseLog.class);
        verify(responseLogs).save(captor.capture());
        return captor.getValue().getResponseBody();
    }

    private void assertThatIsValidJson(String value) {
        try {
            new ObjectMapper().readTree(value);
        } catch (Exception e) {
            throw new AssertionError("jsonb 에 넣을 수 없는 값이다: " + value, e);
        }
    }
}
