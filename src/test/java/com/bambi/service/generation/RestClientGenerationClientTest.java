package com.bambi.service.generation;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.generation.dto.GenerationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link RestClientGenerationClient} — 생성 요청 와이어 포맷과 에러 매핑을 MockRestServiceServer 로 잠근다.
 * 검증: (1) POST 경로·snake_case 본문 (2) 202 접수 정상 처리
 *      (3) agent 오류(409 USER_CONTEXT_REQUIRED 포함) → AGENT_UNAVAILABLE.
 */
class RestClientGenerationClientTest {

    private MockRestServiceServer server;
    private RestClientGenerationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.local");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientGenerationClient(builder.build(), "/internal/v1",
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    @DisplayName("생성 요청: userId 경로로 POST 하고 snake_case 본문을 싣는다(202 접수)")
    void requestSendsSnakeAndAccepts() {
        server.expect(requestTo("http://agent.local/internal/v1/users/23/generations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.idempotency_key").value("2026-08-04-23-interest_news_card"))
                .andExpect(jsonPath("$.topic").value("오늘의 관심사 뉴스"))
                .andExpect(jsonPath("$.content_type").value("interest_news_card"))
                // language/scheduled_at 은 null 이라 직렬화 생략(@JsonInclude NON_NULL)
                .andExpect(jsonPath("$.language").doesNotExist())
                .andExpect(jsonPath("$.scheduled_at").doesNotExist())
                .andExpect(jsonPath("$.report_type").value("MORNING_BRIEFING"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .body("{\"job_id\":\"job-1\",\"status\":\"queued\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GenerationRequest request = new GenerationRequest(
                "2026-08-04-23-interest_news_card", "오늘의 관심사 뉴스", "interest_news_card", null, null, "MORNING_BRIEFING");

        String jobId = client.requestGeneration(23L, request);

        assertThat(jobId).isEqualTo("job-1");   // 202 body 의 job_id 반환
        server.verify();
    }

    @Test
    @DisplayName("생성 요청: 컨텍스트 없는 사용자(409)는 AGENT_UNAVAILABLE 로 변환한다")
    void contextMissingMapsToUnavailable() {
        server.expect(requestTo("http://agent.local/internal/v1/users/9/generations"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("{\"code\":\"USER_CONTEXT_REQUIRED\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GenerationRequest request = new GenerationRequest(
                "2026-08-04-9-interest_news_card", "오늘의 관심사 뉴스", "interest_news_card", null, null, "MORNING_BRIEFING");

        assertThatThrownBy(() -> client.requestGeneration(9L, request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
    }

    @Test
    @DisplayName("생성 요청: agent 오류(503)는 AGENT_UNAVAILABLE 로 변환한다")
    void serverErrorMapsToUnavailable() {
        server.expect(requestTo("http://agent.local/internal/v1/users/1/generations"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        GenerationRequest request = new GenerationRequest(
                "2026-08-04-1-interest_news_card", "오늘의 관심사 뉴스", "interest_news_card", null, null, "MORNING_BRIEFING");

        assertThatThrownBy(() -> client.requestGeneration(1L, request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
    }
}

